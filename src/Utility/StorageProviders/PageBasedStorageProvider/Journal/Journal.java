package Utility.StorageProviders.PageBasedStorageProvider.Journal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;
import Utility.StorageProviders.PageBasedStorageProvider.Durability;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;

/*
Write-ahead journal (journal.wal) that makes every operation all-or-nothing
and lets a storage recover after a crash.

How an operation is made safe:
1. While it runs, its writes are only collected (see JournaledFile).
2. commit(): all of them are appended to journal.wal as ONE record with a
   checksum. With Durability.SYNC the journal is also flushed to disk (fsync)
   before commit() returns.
3. Changes wait in memory (JournaledFile's blocks) until APPLY_BLOCKS blocks
   have changed. Then they are applied: the journal is flushed, the blocks are
   written into data.pb / index.pbi (not flushed), and the memory is freed. The
   journal keeps the records, so those writes are simply replayed if they
   don't survive a crash.
4. At a checkpoint (journal over CHECKPOINT_BYTES, before compact(), on
   close(), or when asked) the journal is flushed, the remaining changes are
   applied, data.pb / index.pbi are flushed, and only then is the journal
   emptied.

Recovery (on open): every complete record is replayed into the files, in
order, and the journal is emptied. A record cut short by a crash fails its
checksum and is ignored, together with anything after it: that operation never
happened. Replaying a record twice writes the same bytes again, so a crash
during a checkpoint or during recovery itself is harmless.

What survives what:
- The program is killed or crashes: every committed operation (the journal is
  in the operating system's cache, which outlives the program).
- Power loss or an OS crash: the files are only written after the journal
  is flushed, and every write in them is in the journal until they have been
  flushed too, so replaying the journal always completes them: the storage
  is never left half-updated.
  With Durability.NORMAL the last operations before the cut may be lost; with
  Durability.SYNC, or after sync(), nothing that returned success is lost.

File format (big-endian):
    header (32 bytes):  magic "DXJ1"(4) version(4) salt(8) padding
    record:             length(4)  [salt(8) seq(8) count(4) changes...]  crc32c(4)
                        length = bytes after the length field, crc included
    change WRITE:       type 1 (1) file(1) position(8) size(4) bytes
    change TRUNCATE:    type 2 (1) file(1) length(8)      cut the file to length (if longer)
    change LENGTH:      type 3 (1) file(1) length(8)      make the file exactly length long
A record holds an operation's net effect per file: its lowest truncation,
the changed bytes of each block it touched, and the final length.
The salt is new after every checkpoint, so records left over from before it are
never replayed; seq numbers each record from 0.
*/
public final class Journal implements Closeable {
    public static final byte DATA_FILE = 1;
    public static final byte INDEX_FILE = 2;

    /*
    Apply waiting changes to the files when this many 4 KB blocks are held in
    memory (16 MB); checkpoint when the journal grows past CHECKPOINT_BYTES.
    The journal's size is also how much a recovery may have to replay.
    */
    public static final int APPLY_BLOCKS = 4096;
    public static final long CHECKPOINT_BYTES = 64L * 1024 * 1024;

    static final int HEADER_SIZE = 32;
    private static final int MAGIC = 0x44584A31; // "DXJ1"
    private static final int VERSION = 1;
    private static final int RECORD_FIXED = 8 + 8 + 4 + 4;
    private static final int MAX_RECORD = 256 * 1024 * 1024;

    private final Path folder;
    private final Durability durability;
    private final RandomAccessFileService log;
    private final Map<Byte, JournaledFile> files = new LinkedHashMap<>();

    private long salt;
    private long nextSeq;
    private long end;
    private final int recovered;

    /*
    Set when an automatic checkpoint failed; the journal keeps everything and
    the next checkpoint tries again.
    */
    private Exception checkpointFailure;

    private Journal(Path folder, Durability durability) throws Exception {
        this.folder = folder;
        this.durability = durability;
        this.log = new RandomAccessFileService(DexBundle.journalPath(folder), Extension.WAL);
        try {
            this.recovered = this.replay();
            this.reset(true);
        } catch (Exception e) {
            this.log.close();
            throw e;
        }
    }

    /*
    Opens the storage's journal, first replaying whatever a previous run left
    in it into data.pb / index.pbi. Call before opening those files.
    */
    public static Journal open(Path folder, Durability durability) throws Exception {
        return new Journal(folder, durability);
    }

    /*
    Opens one of the storage's files through the journal. Replaces an earlier
    one with the same tag (compact() reopens data.pb).
    */
    public JournaledFile openFile(byte tag) throws Exception {
        JournaledFile file = new JournaledFile(tag, pathOf(this.folder, tag), extensionOf(tag));
        this.files.put(tag, file);
        return file;
    }

    /*
    Operations replayed from the journal when the storage was opened: the work
    of a previous run that hadn't reached the files yet.
    */
    public int getRecoveredOperations() {
        return this.recovered;
    }

    public Durability getDurability() {
        return this.durability;
    }

    /*
    Bytes in journal.wal, header included.
    */
    public long size() {
        return this.end;
    }

    // ---- operations ---------------------------------------------------------------

    /*
    Writes the current operation's changes as one record. Nothing to do if it
    made none. May run a checkpoint afterwards; a failed checkpoint doesn't
    undo the commit (the record is in the journal) and is retried later.
    If writing the record fails, the operation's changes are still pending:
    the caller must rollback().
    */
    public void commit() throws Exception {
        List<JournaledFile> touched = new ArrayList<>();
        Map<JournaledFile, List<JournaledFile.Change>> changesOf = new HashMap<>();
        int count = 0;
        int size = RECORD_FIXED;
        for (JournaledFile file : this.files.values()) {
            if (file.hasPending()) {
                touched.add(file);
                List<JournaledFile.Change> changes = file.pendingChanges();
                changesOf.put(file, changes);
                for (JournaledFile.Change change : changes) {
                    count++;
                    size += change.type == JournaledFile.Change.WRITE ? 1 + 1 + 8 + 4 + change.data.length : 1 + 1 + 8;
                }
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        if (count == 0) {
            // Writes that changed nothing: nothing to log.
            for (JournaledFile file : touched) {
                file.commitPending();
            }
            return;
        }

        ByteBuffer record = ByteBuffer.allocate(4 + size);
        record.putInt(size).putLong(this.salt).putLong(this.nextSeq).putInt(count);
        for (JournaledFile file : touched) {
            for (JournaledFile.Change change : changesOf.get(file)) {
                record.put(change.type).put(file.getTag()).putLong(change.position);
                if (change.type == JournaledFile.Change.WRITE) {
                    record.putInt(change.data.length).put(change.data);
                }
            }
        }
        CRC32C crc = new CRC32C();
        crc.update(record.array(), 4, size - 4);
        record.putInt((int) crc.getValue());

        try {
            this.log.write(this.end, record.array());
            if (this.durability == Durability.SYNC) {
                this.log.force();
            }
        } catch (Exception e) {
            // The record may be partly (or even wholly) on disk; make sure a
            // replay stops before it, since the operation is being rolled back.
            try {
                this.log.write(this.end, new byte[4]);
            } catch (Exception ignored) {
                e.addSuppressed(ignored);
            }
            throw e;
        }
        this.end += record.capacity();
        this.nextSeq++;

        for (JournaledFile file : touched) {
            file.commitPending();
        }
        this.checkpointIfDue();
    }

    /*
    Forgets the current operation's changes.
    */
    public void rollback() {
        for (JournaledFile file : this.files.values()) {
            file.rollbackPending();
        }
    }

    public boolean hasPending() {
        for (JournaledFile file : this.files.values()) {
            if (file.hasPending()) {
                return true;
            }
        }
        return false;
    }

    /*
    Flushes the journal to disk: every committed operation now survives a power
    cut. Cheap compared with a checkpoint.
    */
    public void sync() throws Exception {
        this.log.force();
    }

    /*
    Applies every committed change to the files, flushes them, and empties the
    journal. Pending (uncommitted) changes are not allowed.
    */
    public void checkpoint() throws Exception {
        if (this.hasPending()) {
            throw new IllegalStateException("Commit or roll back the current operation before a checkpoint");
        }
        if (this.end == HEADER_SIZE) {
            this.checkpointFailure = null;
            return;
        }

        this.log.force();                  // 1. the journal is durable
        for (JournaledFile file : this.files.values()) {
            file.applyToDisk();            // 2. the files get the changes
        }
        for (JournaledFile file : this.files.values()) {
            file.forceDisk();              // 3. ... durably
        }
        this.reset(false);                 // 4. only then is the journal emptied
        for (JournaledFile file : this.files.values()) {
            file.clearCommitted();
        }
        this.checkpointFailure = null;
    }

    /*
    The error of the last automatic apply or checkpoint, if it failed and
    hasn't succeeded since.
    */
    public Exception getCheckpointFailure() {
        return this.checkpointFailure;
    }

    /*
    Direct mode for every file (see JournaledFile). Checkpoints first.
    */
    public void setDirect(boolean direct) throws Exception {
        if (direct) {
            this.checkpoint();
        }
        for (JournaledFile file : this.files.values()) {
            file.setDirect(direct);
        }
    }

    /*
    Flushes the files themselves (used after direct-mode work).
    */
    public void forceFiles() throws Exception {
        for (JournaledFile file : this.files.values()) {
            file.forceDisk();
        }
    }

    /*
    Closes journal.wal. Checkpoint first to leave it empty; the files are closed
    by their owners.
    */
    @Override
    public void close() throws IOException {
        this.log.close();
    }

    // ---- internals ----------------------------------------------------------------

    private void checkpointIfDue() {
        try {
            if (this.end >= CHECKPOINT_BYTES) {
                this.checkpoint();
                return;
            }
            int blocks = 0;
            for (JournaledFile file : this.files.values()) {
                blocks += file.committedBlocks();
            }
            if (blocks >= APPLY_BLOCKS) {
                this.applyToFiles();
            }
            this.checkpointFailure = null;
        } catch (Exception e) {
            this.checkpointFailure = e;
        }
    }

    /*
    Writes the waiting changes into the files without flushing them, and frees
    the memory they held. The journal is flushed first and keeps the records,
    so if the writes don't survive a power cut, recovery replays them.
    */
    private void applyToFiles() throws Exception {
        this.log.force();
        for (JournaledFile file : this.files.values()) {
            file.applyToDisk();
        }
        for (JournaledFile file : this.files.values()) {
            file.clearCommitted();
        }
    }

    /*
    Empties the journal: a new salt in the header, everything after it cut off.
    At a checkpoint this isn't flushed on its own: if it doesn't reach the disk,
    the old records are replayed onto files that already contain them, which
    changes nothing; and the next checkpoint flushes the journal before it
    touches the files again.
    */
    private void reset(boolean force) throws Exception {
        this.salt = newSalt();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.putInt(MAGIC).putInt(VERSION).putLong(this.salt);
        this.log.write(0, header.array());
        this.log.truncate(HEADER_SIZE);
        if (force) {
            this.log.force();
        }
        this.end = HEADER_SIZE;
        this.nextSeq = 0;
    }

    /*
    Applies every complete record left in the journal to the files on disk and
    flushes them. Returns how many records (operations) were replayed.
    */
    private int replay() throws Exception {
        long length = this.log.length();
        if (length < HEADER_SIZE) {
            return 0;
        }
        ByteBuffer header = ByteBuffer.wrap(this.log.read(0, HEADER_SIZE));
        if (header.getInt() != MAGIC || header.getInt() != VERSION) {
            return 0;   // not a journal we wrote (or a torn header): nothing to replay
        }
        long headerSalt = header.getLong();

        Map<Byte, RandomAccessFileService> targets = new HashMap<>();
        int replayed = 0;
        try {
            long position = HEADER_SIZE;
            long expectedSeq = 0;
            while (position + 4 <= length) {
                int size = ByteBuffer.wrap(this.log.read(position, 4)).getInt();
                if (size < RECORD_FIXED || size > MAX_RECORD || position + 4 + size > length) {
                    break;
                }
                byte[] body = this.log.read(position + 4, size);
                ByteBuffer record = ByteBuffer.wrap(body);

                CRC32C crc = new CRC32C();
                crc.update(body, 0, size - 4);
                if ((int) crc.getValue() != record.getInt(size - 4)) {
                    break;
                }
                if (record.getLong() != headerSalt || record.getLong() != expectedSeq) {
                    break;
                }

                int count = record.getInt();
                for (int i = 0; i < count; i++) {
                    byte type = record.get();
                    byte tag = record.get();
                    long at = record.getLong();
                    RandomAccessFileService target = targets.get(tag);
                    if (target == null) {
                        target = new RandomAccessFileService(pathOf(this.folder, tag), extensionOf(tag));
                        targets.put(tag, target);
                    }
                    if (type == JournaledFile.Change.WRITE) {
                        byte[] data = new byte[record.getInt()];
                        record.get(data);
                        target.write(at, data);
                    } else if (type == JournaledFile.Change.TRUNCATE) {
                        target.truncate(at);
                    } else if (type == JournaledFile.Change.LENGTH) {
                        long current = target.length();
                        if (current > at) {
                            target.truncate(at);
                        } else if (current < at) {
                            target.write(at - 1, new byte[1]);   // the gap reads as zeros
                        }
                    } else {
                        throw new IllegalStateException("Corrupt journal record: change type " + type);
                    }
                }

                replayed++;
                expectedSeq++;
                position += 4 + size;
            }

            for (RandomAccessFileService target : targets.values()) {
                target.force();
            }
        } finally {
            for (RandomAccessFileService target : targets.values()) {
                target.close();
            }
        }
        return replayed;
    }

    private static String pathOf(Path folder, byte tag) {
        switch (tag) {
            case DATA_FILE:
                return DexBundle.dataPath(folder);
            case INDEX_FILE:
                return DexBundle.indexPath(folder);
            default:
                throw new IllegalStateException("Corrupt journal record: unknown file " + tag);
        }
    }

    private static Extension extensionOf(byte tag) {
        return tag == DATA_FILE ? Extension.PB : Extension.PBI;
    }

    private static long newSalt() {
        return new SecureRandom().nextLong();
    }
}
