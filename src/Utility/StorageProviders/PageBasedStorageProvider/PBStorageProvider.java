package Utility.StorageProviders.PageBasedStorageProvider;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexLock;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.LruCache;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PBOffSets;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Page;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PageHeader;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;

/*
Thread safety: one instance can be shared by any number of threads. Writes and
deletes take an exclusive lock; reads share a lock, so they run in parallel
with each other but never alongside a write.

One instance per storage: opening locks the .dex folder (dex.lock), so a
second instance, in this program or another one, gets StorageInUseException
until the first is closed. close() releases the lock; use try-with-resources:

    try (PBKeyStorageProvider<Integer> users = new PBKeyStorageProvider<>("data/users", Integer.class)) {
        users.write(1, data);
    }

After close(), every operation returns PBFail.STORAGE_CLOSED.

Space: deleting a record only marks its slot deleted; the bytes stay in the
file until a later write reuses the slot. getDeadBytes() / getFragmentation()
report how much of the file is wasted this way, and compact() rewrites the file
without it. Pages at the end of the file that hold only deleted records are cut
off automatically when their last record is deleted.

Errors: operations never throw. A failure caused by an exception (an I/O error,
a corrupt page) returns READ_ERROR / WRITE_ERROR / DELETE_ERROR / COMPACT_ERROR
with that exception in result.getError().
*/
public class PBStorageProvider implements Closeable {
    /*
    Data pages kept in memory at most; older ones are dropped and re-read from
    disk when needed. 1024 pages = about 4 MB of page data.
    */
    public static final int DEFAULT_CACHE_PAGES = 1024;

    private static final byte[] NO_KEY = new byte[0];

    /*
    Exclusive for write / delete, shared for read.
    */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /*
    The last 8 bytes of the file header hold a random id identifying this data
    file, so an index file can tell whether it belongs to it.
    */
    private static final int FILE_ID_OFFSET = PBOffSets.FILE_HEADER.getValue() - Long.BYTES;

    /*
    Just before the file id: the dead byte count (8 bytes), preceded by a marker
    (4 bytes) saying the count is present. Files written before it existed have
    zeros there; their count is worked out once, on open.

    File header (256 bytes):
        keyTypeLength(2) keyTypeName(<= 234) ... marker(4) deadBytes(8) fileId(8)
    */
    private static final int DEAD_BYTES_OFFSET = FILE_ID_OFFSET - Long.BYTES;
    private static final int STATS_MARKER_OFFSET = DEAD_BYTES_OFFSET - Integer.BYTES;
    private static final int STATS_MARKER = 0x44584431; // "DXD1"
    private static final int MAX_KEY_TYPE_NAME = STATS_MARKER_OFFSET - Short.BYTES;

    /*
    Number of pages in the file. Pages themselves are loaded on demand.
    */
    private int pageCount;

    /*
    Recently used pages, page number -> page. Writes go straight to disk, so an
    evicted page never needs saving.
    */
    private final LruCache<Integer, Page> pageCache;

    /*
    Class name of the key type stored in the file header, or null if none.
    */
    private String storedKeyType;

    private long fileId;

    /*
    Bytes inside pages that hold no live data: the whole fullLength of every
    deleted slot, plus the unused tail (fullLength - length) of slots reused
    for a smaller record. Kept in the file header.
    */
    private long deadBytes;

    private RandomAccessFileService randomAccessFileService;

    /*
    The storage's .dex folder.
    */
    private final Path folder;

    /*
    Held from the constructor until close(): keeps other instances and other
    programs out of the folder.
    */
    private final DexLock dexLock;

    /*
    Set by close(). Read under the instance lock; volatile for isClosed().
    */
    private volatile boolean closed;

    /*
    Opens (or creates) the storage: the folder path + ".dex" with the data file
    data.pb inside ("data/users" and "data/users.dex" are the same storage).
    Only the file header is read; pages are loaded when first used, so opening
    takes the same time for any file size.
    Throws StorageInUseException if the storage is already open (in this
    program or another one). If opening fails, nothing stays locked or open.
    */
    public PBStorageProvider(String path) throws Exception {
        this.folder = DexBundle.createFolder(path);
        this.dexLock = DexLock.acquire(this.folder);
        this.pageCache = new LruCache<>(DEFAULT_CACHE_PAGES);

        try {
            DexBundle.prepare(path, this.folder);
            this.randomAccessFileService = new RandomAccessFileService(DexBundle.dataPath(this.folder), Extension.PB);
            this.pageCount = this.countPages();
            this.loadFileHeader();
        } catch (Exception e) {
            this.closeAfterFailedOpen(e);
            throw e;
        }
    }

    /*
    Closes the files and releases the storage's lock, so it can be opened again
    (by this program or another one). Waits for operations running on other
    threads to finish. Safe to call more than once.
    After close(), operations return PBFail.STORAGE_CLOSED, containsKey returns
    false, getFileSize / reconstructKeyIndex throw IllegalStateException;
    getDeadBytes / getFragmentation keep their last values.
    If closing a file fails, the lock is still released and the first error is
    thrown.
    */
    @Override
    public void close() throws IOException {
        this.writeLock().lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;

            IOException failure = null;
            try {
                this.onClose();
            } catch (IOException e) {
                failure = e;
            }
            try {
                if (this.randomAccessFileService != null) {
                    this.randomAccessFileService.close();
                }
            } catch (IOException e) {
                failure = addFailure(failure, e);
            }
            this.pageCache.clear();
            try {
                this.dexLock.close();
            } catch (IOException e) {
                failure = addFailure(failure, e);
            }

            if (failure != null) {
                throw failure;
            }
        } finally {
            this.writeLock().unlock();
        }
    }

    /*
    True once close() has been called.
    */
    public boolean isClosed() {
        return this.closed;
    }

    /*
    For constructors: closes whatever was opened and releases the lock after
    opening failed, keeping the original error.
    */
    protected final void closeAfterFailedOpen(Exception cause) {
        try {
            this.close();
        } catch (Exception e) {
            cause.addSuppressed(e);
        }
    }

    /*
    For methods that throw rather than return a PBResult.
    */
    protected void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Storage " + this.folder + " is closed");
        }
    }

    /*
    Hook: close the subclass's own files. Runs inside close(), before the data
    file is closed and the lock released. May run on a partly built instance
    (when a constructor failed), so check fields for null.
    */
    protected void onClose() throws IOException {
    }

    private static IOException addFailure(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /*
    Drops all existing pages (in memory and on disk) and writes a single new page
    with this data.
    SUCCESS: PBSuccess.WRITE, result.getData() holds the written data
    FAIL:    NULL_WRITE, DATA_TOO_LARGE, WRITE_ERROR
    */
    public PBResult write(byte[] data) {
        PBFail invalid = this.validateRecord(0, data);
        if (invalid != null) {
            return PBResult.of(invalid);
        }

        this.writeLock().lock();
        try {
            if (this.closed) {
                return PBResult.of(PBFail.STORAGE_CLOSED);
            }
            this.createNewPage(data);
        } catch (Exception e) {
            return PBResult.ofError(PBFail.WRITE_ERROR, e);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.WRITE, data);
    }

    /*
    Erases everything: every page and record is removed from memory and the
    file is truncated back to just its header (the stored key type is kept).
    SUCCESS: PBSuccess.DELETE
    FAIL:    DELETE_ERROR
    */
    public PBResult delete() {
        this.writeLock().lock();
        try {
            if (this.closed) {
                return PBResult.of(PBFail.STORAGE_CLOSED);
            }
            this.clearAll();
        } catch (Exception e) {
            return PBResult.ofError(PBFail.DELETE_ERROR, e);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.DELETE);
    }

    /*
    The data written by write(data): the first live record in the file.
    SUCCESS: PBSuccess.READ, result.getData() holds the record
    FAIL:    NULL_READ (nothing stored), READ_ERROR
    */
    public PBResult read() {
        this.readLock().lock();
        try {
            if (this.closed) {
                return PBResult.of(PBFail.STORAGE_CLOSED);
            }
            for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
                for (Slot slot : this.getPage(pageNumber).getSlots()) {
                    if (!slot.isDeleted()) {
                        return PBResult.of(PBSuccess.READ, this.readSlot(slot));
                    }
                }
            }
        } catch (Exception e) {
            return PBResult.ofError(PBFail.READ_ERROR, e);
        } finally {
            this.readLock().unlock();
        }

        return PBResult.of(PBFail.NULL_READ);
    }

    /*
    Rewrites the data file with only the live records, packed tightly in their
    current order, so the space of deleted records (getDeadBytes()) is given back
    and the file shrinks. Keyed storages get a freshly built, compact index too.

    1. The live records are copied into data-compact.pb in the .dex folder, and
       that file is flushed to disk.
    2. It is renamed over data.pb in one step: the folder holds either the old
       file or the new one, never a mix.
    3. Subclass work (PBKeyStorageProvider rebuilds its index for the new
       record positions).

    Interrupted before 2, data.pb is untouched and the leftover temp file is
    deleted on the next open. Interrupted after 2, the new data.pb is complete
    and has a new file id, so an index that wasn't rebuilt yet no longer
    matches it and is rebuilt on the next open.

    Takes time proportional to the live data, needs free disk space for one
    more copy of it, and blocks reads and writes while it runs. Worth calling
    when getFragmentation() is high, e.g.
        if (storage.getFragmentation() > 0.5) storage.compact();
    SUCCESS: PBSuccess.COMPACT
    FAIL:    COMPACT_ERROR
    */
    public PBResult compact() {
        this.writeLock().lock();
        try {
            if (this.closed) {
                return PBResult.of(PBFail.STORAGE_CLOSED);
            }
            this.rewriteCompacted();
            this.onCompacted();
        } catch (Exception e) {
            return PBResult.ofError(PBFail.COMPACT_ERROR, e);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.COMPACT);
    }

    /*
    Bytes in the file's pages that hold no live data and would be given back
    by compact(): deleted records, and the unused part of slots reused for a
    smaller record.
    */
    public long getDeadBytes() {
        this.readLock().lock();
        try {
            return this.deadBytes;
        } finally {
            this.readLock().unlock();
        }
    }

    /*
    Share of the file's pages that is dead space, from 0.0 (none) to 1.0.
    */
    public double getFragmentation() {
        this.readLock().lock();
        try {
            long pagesBytes = (long) this.pageCount * PBOffSets.PAGE.getValue();
            return pagesBytes == 0 ? 0.0 : (double) this.deadBytes / pagesBytes;
        } finally {
            this.readLock().unlock();
        }
    }

    /*
    Current size of the data file in bytes.
    */
    public long getFileSize() throws Exception {
        this.readLock().lock();
        try {
            this.ensureOpen();
            return this.randomAccessFileService.length();
        } finally {
            this.readLock().unlock();
        }
    }

    /*
    Shared lock for operations that only read. Subclasses wrap their read
    operations in it.
    */
    protected Lock readLock() {
        return this.lock.readLock();
    }

    /*
    Exclusive lock for operations that change the storage. Reentrant, so a
    subclass holding it can call the parent's write / delete.
    */
    protected Lock writeLock() {
        return this.lock.writeLock();
    }

    /*
    Data of every live slot, in page and slot order, one entry per record, so
    each entry is exactly the byte[] that was written and can be deserialized
    back into its own object. Key bytes of keyed records are not included;
    deleted slots (old versions of overwritten keys) are skipped.
    */
    protected byte[][] readAllRecords() throws Exception {
        List<byte[]> records = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
            for (Slot slot : this.getPage(pageNumber).getSlots()) {
                if (!slot.isDeleted()) {
                    records.add(this.readSlot(slot));
                }
            }
        }

        return records.toArray(new byte[0][]);
    }

    /*
    Key type class name stored in the file header, or null if none was stored.
    */
    protected String getStoredKeyType() {
        return this.storedKeyType;
    }

    /*
    Stores the key type class name in the file header.
    */
    protected void setStoredKeyType(String keyTypeName) throws Exception {
        this.storedKeyType = keyTypeName;
        this.writeFileHeader();
    }

    /*
    The storage's .dex folder.
    */
    public Path getFolder() {
        return this.folder;
    }

    /*
    Random id of this data file, kept in the file header (never 0).
    */
    protected long getFileId() {
        return this.fileId;
    }

    protected int getPageCount() {
        return this.pageCount;
    }

    /*
    Data pages currently held in memory.
    */
    protected int getCachedPageCount() {
        return this.pageCache.size();
    }

    /*
    The page, from the cache or, if not cached, read from disk in one call.
    */
    protected Page getPage(int pageNumber) throws Exception {
        if (pageNumber < 0 || pageNumber >= this.pageCount) {
            return null;
        }

        Page page = this.pageCache.get(pageNumber);
        if (page == null) {
            page = this.loadPage(pageNumber);
            this.pageCache.put(pageNumber, page);
        }
        return page;
    }

    /*
    Checks that data is present and key + data fit into one page.
    Returns the failure, or null if the record is valid.
    */
    protected PBFail validateRecord(int keyLength, byte[] data) {
        if (data == null || data.length == 0) {
            return PBFail.NULL_WRITE;
        }

        int maxRecordSize = PBOffSets.PAGE.getValue() - PBOffSets.HEADER.getValue() - PBOffSets.SLOT.getValue();
        if (keyLength + data.length > maxRecordSize) {
            return PBFail.DATA_TOO_LARGE;
        }

        return null;
    }

    /*
    Places the record [key bytes][data bytes] and returns where it went:
    1. A deleted slot offered by takeFreeSlot (best fit) is reused in place.
    2. Otherwise appended to the last page: slot entry at freeSpaceStart, data
       just below freeSpaceEnd.
    3. If the last page is full too, a new page is allocated and used.
    Callers must have checked the record fits a page (validateRecord).
    */
    protected RecordID placeRecord(byte[] keyBytes, byte[] data) throws Exception {
        short recordLength = (short) (keyBytes.length + data.length);

        RecordID reused = this.takeFreeSlot(recordLength);
        if (reused != null) {
            Page page = this.getPage(reused.pageNumber);
            Slot slot = this.getSlot(reused);

            slot.reuse(recordLength, keyBytes.length);
            this.writeRecord(slot.getOffset(), keyBytes, data);
            this.writeSlotEntry(this.getSlotOffSet(page.getHeader(), reused.slotNumber), slot);
            this.addDeadBytes(-recordLength);

            return reused;
        }

        Page page = this.getPageWithSpace(recordLength);
        short slotNumber = this.appendSlot(page, keyBytes, data);
        return new RecordID(page.getPageNumber(), slotNumber);
    }

    /*
    Hook: a deleted slot whose fullLength fits recordLength, removed from the
    free list, or null to append instead. The base class keeps no free list
    (keyless storage never frees single slots); PBKeyStorageProvider does.
    The returned slot must be deleted and big enough.
    */
    protected RecordID takeFreeSlot(short recordLength) throws Exception {
        return null;
    }

    /*
    Hook: called after a slot has been marked deleted, so it can be offered for
    reuse later.
    */
    protected void onSlotFreed(RecordID recordID, Slot slot) throws Exception {
    }

    /*
    Hook: a deleted slot is about to disappear because its page is cut off the
    end of the file, so it must no longer be offered for reuse.
    */
    protected void onSlotDropped(RecordID recordID, Slot slot) throws Exception {
    }

    /*
    Hook: compact() has put the packed data file in place; record positions
    have changed. Runs under the write lock.
    */
    protected void onCompacted() throws Exception {
    }

    protected void markSlotDeleted(RecordID recordID) throws Exception {
        Page page = this.getPage(recordID.pageNumber);
        Slot slot = this.getSlot(recordID);
        if (page == null || slot == null) {
            return;
        }

        if (slot.isDeleted()) {
            return;
        }

        slot.deletePage(true);
        this.writeSlotEntry(this.getSlotOffSet(page.getHeader(), recordID.slotNumber), slot);
        this.addDeadBytes(slot.getLength());
        this.onSlotFreed(recordID, slot);

        if (recordID.pageNumber == this.pageCount - 1) {
            this.trimEmptyTailPages();
        }
    }

    /*
    Cuts pages holding only deleted slots off the end of the file. Called when
    a slot on the last page is deleted; stops at the first page (from the end)
    that still has a live record.
    */
    private void trimEmptyTailPages() throws Exception {
        int keep = this.pageCount;
        long freed = 0;

        while (keep > 0) {
            Page page = this.getPage(keep - 1);
            List<Slot> slots = page.getSlots();
            if (!allDeleted(slots)) {
                break;
            }

            for (short slotNumber = 0; slotNumber < slots.size(); slotNumber++) {
                Slot slot = slots.get(slotNumber);
                this.onSlotDropped(new RecordID(keep - 1, slotNumber), slot);
                freed += slot.getFullLength();
            }
            keep--;
        }

        if (keep == this.pageCount) {
            return;
        }

        for (int pageNumber = keep; pageNumber < this.pageCount; pageNumber++) {
            this.pageCache.remove(pageNumber);
        }
        this.pageCount = keep;
        this.addDeadBytes(-freed);
        this.randomAccessFileService.truncate(getPageOffSet(keep));
    }

    private static boolean allDeleted(List<Slot> slots) {
        for (Slot slot : slots) {
            if (!slot.isDeleted()) {
                return false;
            }
        }
        return true;
    }

    private void addDeadBytes(long delta) throws Exception {
        if (delta == 0) {
            return;
        }
        this.deadBytes += delta;
        this.randomAccessFileService.write(DEAD_BYTES_OFFSET, this.getLongBytes(this.deadBytes));
    }

    /*
    Dead bytes worked out from the pages themselves; used once for files
    written before the count was kept in the header.
    */
    private long computeDeadBytes() throws Exception {
        long dead = 0;
        for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
            for (Slot slot : this.getPage(pageNumber).getSlots()) {
                dead += slot.isDeleted() ? slot.getFullLength() : slot.getFullLength() - slot.getLength();
            }
        }
        return dead;
    }

    // ---- compaction -----------------------------------------------------------

    /*
    Steps 1 and 2 of compact(): writes the packed copy, swaps it in and reopens.
    */
    private void rewriteCompacted() throws Exception {
        Path tempFile = DexBundle.compactFile(this.folder);
        long newFileId = newFileId();

        try (RandomAccessFileService out = new RandomAccessFileService(DexBundle.compactPath(this.folder), Extension.PB)) {
            out.truncate();
            out.write(0, this.fileHeaderBytes(newFileId, 0));
            this.copyLiveRecords(out);
            out.force();
        } catch (Exception e) {
            deleteQuietly(tempFile, e);
            throw e;
        }

        this.randomAccessFileService.close();
        try {
            moveReplacing(tempFile, DexBundle.dataFile(this.folder));
        } catch (Exception e) {
            deleteQuietly(tempFile, e);
            throw e;
        } finally {
            this.reopenDataFile();
        }
    }

    /*
    Copies every live record, in page and slot order, into packed pages of out.
    Each source page is read in one call and bypasses the page cache.
    */
    private void copyLiveRecords(RandomAccessFileService out) throws Exception {
        PackedPageWriter writer = new PackedPageWriter(out);

        for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
            long pageOffSet = getPageOffSet(pageNumber);
            byte[] raw = this.readRawPage(pageNumber);
            Page page = parsePage(pageNumber, pageOffSet, raw);

            for (Slot slot : page.getSlots()) {
                if (!slot.isDeleted()) {
                    writer.add(raw, (int) (slot.getOffset() - pageOffSet), slot.getLength(), slot.getKeyLength());
                }
            }
        }

        writer.finish();
    }

    /*
    Opens data.pb again (after compact() replaced it, or failed to) and drops
    everything cached from the old file.
    */
    private void reopenDataFile() throws Exception {
        this.randomAccessFileService = new RandomAccessFileService(DexBundle.dataPath(this.folder), Extension.PB);
        this.pageCache.clear();
        this.pageCount = this.countPages();
        this.loadFileHeader();
    }

    /*
    Deletes compact()'s temp file after a failure. If that fails too, its
    exception is attached to the original as suppressed, so the original cause
    is the one reported.
    */
    private static void deleteQuietly(Path file, Exception cause) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            cause.addSuppressed(e);
        }
    }

    /*
    Rename in one step where the file system supports it (it does on macOS,
    Linux and Windows' NTFS); a plain replacing move otherwise.
    */
    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /*
    Builds pages in memory, filled the same way placeRecord fills them (slot
    entries grow up from the header, records grow down from the page end), and
    writes each one in a single call once it is full. Every slot's fullLength
    is its record length, so the new file has no dead bytes.
    */
    private static final class PackedPageWriter {
        private static final int PAGE = PBOffSets.PAGE.getValue();
        private static final int HEADER = PBOffSets.HEADER.getValue();
        private static final int SLOT = PBOffSets.SLOT.getValue();

        private final RandomAccessFileService out;
        private final byte[] page = new byte[PAGE];

        private int pageNumber = -1;
        private short slotCount;
        private int freeSpaceStart;
        private int freeSpaceEnd;

        PackedPageWriter(RandomAccessFileService out) {
            this.out = out;
        }

        void add(byte[] source, int from, short length, int keyLength) throws Exception {
            if (this.pageNumber < 0 || this.freeSpaceEnd - this.freeSpaceStart < SLOT + length) {
                this.finish();
                this.startPage();
            }

            long pageOffSet = getPageOffSet(this.pageNumber);
            this.freeSpaceEnd -= length;
            System.arraycopy(source, from, this.page, this.freeSpaceEnd, length);

            ByteBuffer.wrap(this.page, this.freeSpaceStart, SLOT)
                      .putLong(pageOffSet + this.freeSpaceEnd)
                      .putShort(length)
                      .putShort(length)
                      .putInt(keyLength)
                      .put((byte) 0);
            this.freeSpaceStart += SLOT;
            this.slotCount++;
        }

        /*
        Writes the page being built, if any.
        */
        void finish() throws Exception {
            if (this.pageNumber < 0) {
                return;
            }

            long pageOffSet = getPageOffSet(this.pageNumber);
            ByteBuffer.wrap(this.page, 0, HEADER)
                      .putLong(pageOffSet)
                      .putShort(this.slotCount)
                      .putLong(pageOffSet + this.freeSpaceStart)
                      .putLong(pageOffSet + this.freeSpaceEnd)
                      .putLong(this.freeSpaceEnd - this.freeSpaceStart);
            this.out.write(pageOffSet, this.page);
        }

        private void startPage() {
            this.pageNumber++;
            Arrays.fill(this.page, (byte) 0);
            this.slotCount = 0;
            this.freeSpaceStart = HEADER;
            this.freeSpaceEnd = PAGE;
        }
    }

    protected Slot getSlot(RecordID recordID) throws Exception {
        Page page = this.getPage(recordID.pageNumber);
        if (page == null) {
            return null;
        }

        List<Slot> slots = page.getSlots();
        if (recordID.slotNumber < 0 || recordID.slotNumber >= slots.size()) {
            return null;
        }
        return slots.get(recordID.slotNumber);
    }

    /*
    Data bytes of a slot (the bytes after the key).
    */
    protected byte[] readSlot(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getDataOffset(), slot.getDataLength());
    }

    /*
    Key bytes of a slot (the first keyLength bytes at its offset).
    */
    protected byte[] readSlotKey(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getOffset(), slot.getKeyLength());
    }

    /*
    The whole record [key bytes][data bytes] of a slot, in one read.
    */
    protected byte[] readRecord(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getOffset(), (int) slot.getLength());
    }

    private Page getPageWithSpace(short recordLength) throws Exception {
        if (this.pageCount > 0) {
            Page lastPage = this.getPage(this.pageCount - 1);
            if (this.hasSpace(lastPage, recordLength)) {
                return lastPage;
            }
        }

        return this.allocatePage();
    }

    private boolean hasSpace(Page page, short recordLength) {
        PageHeader header = page.getHeader();
        long available = header.getFreeSpaceEnd() - header.getFreeSpaceStart();
        return available >= (PBOffSets.SLOT.getValue() + recordLength);
    }

    private Page allocatePage() throws Exception {
        int pageNumber = this.pageCount;
        long pageOffSet = getPageOffSet(pageNumber);

        PageHeader pageHeader = new PageHeader(pageOffSet);
        Page page = new Page(pageNumber);
        page.setPageHeader(pageHeader);

        this.pageCount++;
        this.pageCache.put(pageNumber, page);
        this.writePageHeader(pageHeader);

        return page;
    }

    /*
    Places a new slot into the page with the record [key bytes][data bytes],
    persists the record, the slot entry and the updated header, and returns
    the slot number within the page. keyBytes is empty for keyless records.
    The slot's fullLength is the record length it is created with.
    */
    private short appendSlot(Page page, byte[] keyBytes, byte[] data) throws Exception {
        PageHeader header = page.getHeader();

        short recordLength = (short) (keyBytes.length + data.length);
        short slotNumber = (short) page.getSlots().size();
        long recordOffSet = header.getFreeSpaceEnd() - recordLength;

        Slot slot = new Slot(recordOffSet, recordLength, recordLength, keyBytes.length, false);
        page.addSlot(slot, recordLength);

        this.writeSlotEntry(this.getSlotOffSet(header, slotNumber), slot);
        this.writeRecord(slot.getOffset(), keyBytes, data);
        this.writePageHeader(header);

        return slotNumber;
    }

    private long getSlotOffSet(PageHeader header, short slotNumber) {
        return header.getOffSet() + PBOffSets.HEADER.getValue() + ((long) slotNumber * PBOffSets.SLOT.getValue());
    }

    /*
    Truncates the file back to its header and drops every page, so nothing old
    comes back on the next load. The file gets a new id: any index built for the
    old contents no longer matches it and is rebuilt when next opened.
    */
    private void clearAll() throws Exception {
        this.randomAccessFileService.truncate();
        this.fileId = newFileId();
        this.deadBytes = 0;
        this.writeFileHeader();
        this.pageCount = 0;
        this.pageCache.clear();
    }

    private void createNewPage(byte[] data) throws Exception {
        this.clearAll();

        Page page = this.allocatePage();
        this.appendSlot(page, NO_KEY, data);
    }

    /*
    Pages sit back to back after the file header.
    */
    private static long getPageOffSet(int pageNumber) {
        return PBOffSets.FILE_HEADER.getValue() + ((long) pageNumber * PBOffSets.PAGE.getValue());
    }

    private int countPages() throws Exception {
        long pagesLength = this.randomAccessFileService.length() - PBOffSets.FILE_HEADER.getValue();
        long pageSize = PBOffSets.PAGE.getValue();
        return (int) Math.max(0, (pagesLength + pageSize - 1) / pageSize);
    }

    /*
    A new (empty) file gets a header with a fresh file id; an existing one has
    its key type, dead byte count and id read. Files written before ids or the
    dead byte count existed get them now. pageCount must be set already.
    */
    private void loadFileHeader() throws Exception {
        long fileLength = this.randomAccessFileService.length();
        if (fileLength == 0) {
            this.storedKeyType = null;
            this.fileId = newFileId();
            this.deadBytes = 0;
            this.writeFileHeader();
            return;
        }

        if (fileLength < PBOffSets.FILE_HEADER.getValue()) {
            throw new IllegalStateException("Corrupt or incompatible storage file: missing file header");
        }

        byte[] header = this.randomAccessFileService.read(0, (int) PBOffSets.FILE_HEADER.getValue());
        ByteBuffer buffer = ByteBuffer.wrap(header);
        this.storedKeyType = parseFileHeader(header);
        this.fileId = buffer.getLong(FILE_ID_OFFSET);

        boolean upgrade = false;
        if (this.fileId == 0) {
            this.fileId = newFileId();
            upgrade = true;
        }
        if (buffer.getInt(STATS_MARKER_OFFSET) == STATS_MARKER) {
            this.deadBytes = buffer.getLong(DEAD_BYTES_OFFSET);
        } else {
            this.deadBytes = this.computeDeadBytes();
            upgrade = true;
        }

        if (upgrade) {
            this.writeFileHeader();
        }
    }

    private static long newFileId() {
        SecureRandom random = new SecureRandom();
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        return id;
    }

    private void writeFileHeader() throws Exception {
        this.randomAccessFileService.write(0, this.fileHeaderBytes(this.fileId, this.deadBytes));
    }

    private byte[] fileHeaderBytes(long fileId, long deadBytes) {
        byte[] name = this.storedKeyType == null
            ? new byte[0]
            : this.storedKeyType.getBytes(StandardCharsets.UTF_8);

        if (name.length > MAX_KEY_TYPE_NAME) {
            throw new IllegalArgumentException(
                "Key type name " + this.storedKeyType + " exceeds " + MAX_KEY_TYPE_NAME + " bytes"
            );
        }

        return ByteBuffer.allocate(PBOffSets.FILE_HEADER.getValue())
                         .putShort((short) name.length)
                         .put(name)
                         .putInt(STATS_MARKER_OFFSET, STATS_MARKER)
                         .putLong(DEAD_BYTES_OFFSET, deadBytes)
                         .putLong(FILE_ID_OFFSET, fileId)
                         .array();
    }

    private static String parseFileHeader(byte[] header) {
        ByteBuffer buffer = ByteBuffer.wrap(header);
        short length = buffer.getShort();
        if (length == 0) {
            return null;
        }
        if (length < 0 || length > MAX_KEY_TYPE_NAME) {
            throw new IllegalStateException("Corrupt or incompatible storage file: bad file header");
        }

        byte[] name = new byte[length];
        buffer.get(name);
        return new String(name, StandardCharsets.UTF_8);
    }

    /*
    Reads one page from disk in a single call and rebuilds its header and slots.
    */
    private Page loadPage(int pageNumber) throws Exception {
        return parsePage(pageNumber, getPageOffSet(pageNumber), this.readRawPage(pageNumber));
    }

    /*
    The page's bytes as stored (shorter than a page if the file ends early).
    */
    private byte[] readRawPage(int pageNumber) throws Exception {
        long pageOffSet = getPageOffSet(pageNumber);
        int length = (int) Math.min(PBOffSets.PAGE.getValue(), this.randomAccessFileService.length() - pageOffSet);
        return this.randomAccessFileService.read(pageOffSet, length);
    }

    private static Page parsePage(int pageNumber, long pageOffSet, byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);

        long offSet = buffer.getLong();
        short slotCount = buffer.getShort();
        long freeSpaceStart = buffer.getLong();
        long freeSpaceEnd = buffer.getLong();
        long freeBytes = buffer.getLong();

        if (offSet != pageOffSet) {
            throw new IllegalStateException(
                "Corrupt or incompatible storage file: page at " + pageOffSet + " has header offset " + offSet
            );
        }

        Page page = new Page(pageNumber);
        page.setPageHeader(new PageHeader(offSet, slotCount, freeSpaceStart, freeSpaceEnd, freeBytes));

        for (int slotNumber = 0; slotNumber < slotCount; slotNumber++) {
            buffer.position(PBOffSets.HEADER.getValue() + slotNumber * PBOffSets.SLOT.getValue());
            long slotOffSet = buffer.getLong();
            short fullLength = buffer.getShort();
            short slotLength = buffer.getShort();
            int keyLength = buffer.getInt();
            boolean deleted = buffer.get() != 0;
            page.restoreSlot(new Slot(slotOffSet, fullLength, slotLength, keyLength, deleted));
        }

        return page;
    }

    private void writePageHeader(PageHeader pageHeader) throws Exception {
        long offSetValue = pageHeader.getOffSet();

        byte[] offSet = this.getLongBytes(offSetValue);
        byte[] slotCount = this.getShortBytes(pageHeader.getSlotCount());
        byte[] freeSpaceStart = this.getLongBytes(pageHeader.getFreeSpaceStart());
        byte[] freeSpaceEnd = this.getLongBytes(pageHeader.getFreeSpaceEnd());
        byte[] freeBytes = this.getLongBytes(pageHeader.getFreeBytes());

        byte[][] headerValues = new byte[][] { offSet, slotCount, freeSpaceStart, freeSpaceEnd, freeBytes };

        this.randomAccessFileService.writeInSequence(offSetValue, headerValues);
    }

    private void writeSlotEntry(long offSet, Slot slot) throws Exception {
        byte[] offSetValue = this.getLongBytes(slot.getOffset());
        byte[] fullLength = this.getShortBytes(slot.getFullLength());
        byte[] length = this.getShortBytes(slot.getLength());
        byte[] keyLength = this.getIntBytes(slot.getKeyLength());
        byte[] deleted = this.getBooleanBytes(slot.isDeleted());

        byte[][] slotValues = new byte[][] { offSetValue, fullLength, length, keyLength, deleted };
        this.randomAccessFileService.writeInSequence(offSet, slotValues);
    }

    /*
    Writes the record [key bytes][data bytes] at offSet. Key and data are
    adjacent on disk, so they are copied into one array and written together.
    */
    private void writeRecord(long offSet, byte[] keyBytes, byte[] data) throws Exception {
        if (keyBytes.length == 0) {
            this.writeData(offSet, data);
            return;
        }

        byte[] record = new byte[keyBytes.length + data.length];
        System.arraycopy(keyBytes, 0, record, 0, keyBytes.length);
        System.arraycopy(data, 0, record, keyBytes.length, data.length);
        this.writeData(offSet, record);
    }

    private void writeData(long offSet, byte[] data) throws Exception {
        if (data.length == 0) {
            return;
        }
        this.randomAccessFileService.write(offSet, data);
    }

    /*
    Move this to Utility!!!!
    */
    private byte[] getLongBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                         .putLong(value)
                         .array();
    }

    private byte[] getIntBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                         .putInt(value)
                         .array();
    }

    private byte[] getShortBytes(short value) {
        return ByteBuffer.allocate(Short.BYTES)
                         .putShort(value)
                         .array();
    }

    private byte[] getBooleanBytes(boolean value) {
        return new byte[] { (byte) (value ? 1 : 0) };
    }
}
