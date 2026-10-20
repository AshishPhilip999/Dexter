package Utility.StorageProviders.PageBasedStorageProvider.Journal;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;

/*
A data or index file whose changes go through the Journal instead of straight
to disk.

Writes and truncations made during an operation are kept as "pending": copies
of the 4 KB blocks they touch (so later reads in the same operation see them),
next to each block's content before the operation. When the operation commits,
each block is compared with its earlier content and only the bytes that
changed go into the journal record (one change per run of changed bytes; a
block written ten times is logged once), and the pending blocks become
"committed". Committed blocks are copied into the file itself at the
next checkpoint. Until then reads are answered from the blocks, so the file
on disk can lag behind what callers see.

    reads:   pending block  >  committed block  >  the file on disk

Truncations are tracked as a "floor": bytes on disk at or beyond the lowest
truncation since the last checkpoint no longer count and read as zeros.

Direct mode (used while an index is rebuilt) bypasses all of this and reads
and writes the file itself; it requires that nothing is pending or committed.

Not thread-safe on its own: the providers call it under their instance lock,
with writes exclusive. Reads never change any state.
*/
public final class JournaledFile extends RandomAccessFileService {
    static final int BLOCK = 4096;

    /*
    One change in a journal record. Replayed in order: TRUNCATE (the lowest
    truncation of the operation), then WRITEs, then LENGTH (the final length).
    */
    static final class Change {
        static final byte WRITE = 1;
        static final byte TRUNCATE = 2;
        static final byte LENGTH = 3;

        final byte type;
        final long position;   // WRITE: where; TRUNCATE / LENGTH: a length
        final byte[] data;     // WRITE only

        Change(byte type, long position, byte[] data) {
            this.type = type;
            this.position = position;
            this.data = data;
        }
    }

    private final byte tag;

    private final TreeMap<Long, byte[]> committed = new TreeMap<>();
    private long committedLength;
    private long committedFloor = Long.MAX_VALUE;

    private final TreeMap<Long, byte[]> pending = new TreeMap<>();
    private final Map<Long, byte[]> original = new HashMap<>();   // pending blocks before the operation (read-only)
    private long pendingLength;
    private long pendingFloor = Long.MAX_VALUE;

    private boolean direct;

    JournaledFile(byte tag, String path, Extension extension) throws Exception {
        super(path, extension);
        this.tag = tag;
        this.committedLength = super.length();
        this.pendingLength = this.committedLength;
    }

    byte getTag() {
        return this.tag;
    }

    // ---- the RandomAccessFileService interface --------------------------------------

    /*
    Length as callers see it, including changes not yet on disk.
    */
    @Override
    public long length() throws Exception {
        return this.direct ? super.length() : this.pendingLength;
    }

    @Override
    public void write(long position, byte[] data) throws Exception {
        if (this.direct) {
            super.write(position, data);
            return;
        }
        if (data.length == 0) {
            return;
        }

        int done = 0;
        while (done < data.length) {
            long at = position + done;
            long block = at / BLOCK;
            int inBlock = (int) (at % BLOCK);
            int count = Math.min(BLOCK - inBlock, data.length - done);
            System.arraycopy(data, done, this.pendingBlock(block), inBlock, count);
            done += count;
        }
        this.pendingLength = Math.max(this.pendingLength, position + data.length);
    }

    @Override
    public void writeInSequence(long position, byte[][] values) throws Exception {
        int total = 0;
        for (byte[] value : values) {
            total += value.length;
        }
        byte[] joined = new byte[total];
        int at = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, joined, at, value.length);
            at += value.length;
        }
        this.write(position, joined);
    }

    @Override
    public void truncate() throws Exception {
        if (this.direct) {
            super.truncate();
            return;
        }
        this.truncateTo(0);
    }

    /*
    Cuts the file down to length bytes (no-op if it is already shorter).
    */
    @Override
    public void truncate(long length) throws Exception {
        if (this.direct) {
            super.truncate(length);
            return;
        }
        if (length < this.pendingLength) {
            this.truncateTo(length);
        }
    }

    @Override
    public byte[] read(long position, int length) throws Exception {
        if (this.direct) {
            return super.read(position, length);
        }
        if (position < 0 || position + length > this.pendingLength) {
            throw new EOFException("Read past end of file at " + position);
        }
        if (this.readsStraightFromDisk(position, length)) {
            return super.read(position, length);
        }

        byte[] out = new byte[length];
        int done = 0;
        while (done < length) {
            long at = position + done;
            long block = at / BLOCK;
            int inBlock = (int) (at % BLOCK);
            int count = Math.min(BLOCK - inBlock, length - done);
            System.arraycopy(this.viewBlock(block), inBlock, out, done, count);
            done += count;
        }
        return out;
    }

    /*
    The file has no separate durability in journaled mode: the journal makes
    changes durable. In direct mode, flushes the file.
    */
    @Override
    public void force() throws Exception {
        if (this.direct) {
            super.force();
        }
    }

    // ---- journal hooks ---------------------------------------------------------------

    boolean hasPending() {
        return !this.pending.isEmpty() || this.pendingFloor != Long.MAX_VALUE || this.pendingLength != this.committedLength;
    }

    /*
    The operation's net effect, for its journal record: the lowest truncation,
    then for each touched block the range of bytes that differ from before the
    operation, then the final length.
    */
    List<Change> pendingChanges() {
        List<Change> out = new ArrayList<>();
        boolean truncated = this.pendingFloor != Long.MAX_VALUE;
        if (truncated) {
            out.add(new Change(Change.TRUNCATE, this.pendingFloor, null));
        }

        for (Map.Entry<Long, byte[]> entry : this.pending.entrySet()) {
            long start = entry.getKey() * BLOCK;
            if (start >= this.pendingLength) {
                continue;
            }
            byte[] now = entry.getValue();
            byte[] before = this.original.get(entry.getKey());
            int limit = (int) Math.min(BLOCK, this.pendingLength - start);
            if (truncated && this.pendingFloor < start + BLOCK) {
                // Replay truncates first, so bytes at or past the floor count as
                // zero "before" and must be logged if they aren't zero now.
                before = before.clone();
                Arrays.fill(before, (int) Math.max(0, this.pendingFloor - start), BLOCK, (byte) 0);
            }
            addChangedRuns(out, start, now, before, limit);
        }

        if (truncated || this.pendingLength != this.committedLength) {
            out.add(new Change(Change.LENGTH, this.pendingLength, null));
        }
        return out;
    }

    /*
    The operation committed: its blocks become committed.
    */
    void commitPending() {
        if (this.pendingFloor != Long.MAX_VALUE) {
            // Committed blocks wholly past the truncation are gone; the block
            // holding the cut was copied into pending (zero-filled) by truncateTo.
            this.committed.tailMap(firstBlockAtOrAfter(this.pendingFloor), true).clear();
            this.committedFloor = Math.min(this.committedFloor, this.pendingFloor);
        }
        this.committed.putAll(this.pending);
        this.committedLength = this.pendingLength;
        this.clearPending();
    }

    /*
    The operation failed: forget its changes.
    */
    void rollbackPending() {
        this.clearPending();
        this.pendingLength = this.committedLength;
    }

    int committedBlocks() {
        return this.committed.size();
    }

    boolean hasCommitted() {
        return !this.committed.isEmpty() || this.committedFloor != Long.MAX_VALUE
            || this.committedLength != this.diskLengthQuietly();
    }

    /*
    Checkpoint, step 1: makes the file on disk match the committed state.
    Safe to repeat (it writes the same bytes again).
    */
    void applyToDisk() throws Exception {
        if (this.committedFloor < super.length()) {
            super.truncate(this.committedFloor);
        }
        for (Map.Entry<Long, byte[]> entry : this.committed.entrySet()) {
            long start = entry.getKey() * BLOCK;
            if (start >= this.committedLength) {
                continue;
            }
            int count = (int) Math.min(BLOCK, this.committedLength - start);
            byte[] block = entry.getValue();
            super.write(start, count == BLOCK ? block : Arrays.copyOf(block, count));
        }

        long onDisk = super.length();
        if (onDisk > this.committedLength) {
            super.truncate(this.committedLength);
        } else if (onDisk < this.committedLength) {
            // The gap was never written, so it reads as zeros; extend the file.
            super.write(this.committedLength - 1, new byte[1]);
        }
    }

    void forceDisk() throws Exception {
        super.force();
    }

    /*
    Checkpoint, step 2 (after the journal is emptied): the disk is now the
    committed state.
    */
    void clearCommitted() throws Exception {
        this.committed.clear();
        this.committedFloor = Long.MAX_VALUE;
        this.committedLength = super.length();
        this.pendingLength = this.committedLength;
    }

    /*
    Direct mode: reads and writes go straight to the file. Only allowed when
    nothing is pending or waiting for a checkpoint.
    */
    void setDirect(boolean direct) throws Exception {
        if (direct && (this.hasPending() || !this.committed.isEmpty() || this.committedFloor != Long.MAX_VALUE)) {
            throw new IllegalStateException("Journaled changes must be checkpointed before direct mode");
        }
        if (!direct && this.direct) {
            this.committedLength = super.length();
            this.pendingLength = this.committedLength;
        }
        this.direct = direct;
    }

    // ---- blocks -----------------------------------------------------------------------

    /*
    Runs of bytes closer together than this are logged as one change (each
    change costs 14 bytes of framing).
    */
    private static final int MERGE_GAP = 16;

    /*
    One WRITE change per run of bytes that differ between now and before
    (within the first limit bytes of the block at start).
    */
    private static void addChangedRuns(List<Change> out, long start, byte[] now, byte[] before, int limit) {
        int at = 0;
        int runStart = -1;
        int runEnd = -1;
        while (at < limit) {
            int offset = Arrays.mismatch(now, at, limit, before, at, limit);
            if (offset < 0) {
                break;
            }
            int from = at + offset;
            int to = from + 1;
            while (to < limit && now[to] != before[to]) {
                to++;
            }
            if (runStart >= 0 && from - runEnd <= MERGE_GAP) {
                runEnd = to;
            } else {
                if (runStart >= 0) {
                    out.add(new Change(Change.WRITE, start + runStart, Arrays.copyOfRange(now, runStart, runEnd)));
                }
                runStart = from;
                runEnd = to;
            }
            at = to;
        }
        if (runStart >= 0) {
            out.add(new Change(Change.WRITE, start + runStart, Arrays.copyOfRange(now, runStart, runEnd)));
        }
    }

    private void truncateTo(long length) throws Exception {
        if (length % BLOCK != 0) {
            byte[] block = this.pendingBlock(length / BLOCK);
            Arrays.fill(block, (int) (length % BLOCK), BLOCK, (byte) 0);
        }
        this.pending.tailMap(firstBlockAtOrAfter(length), true).clear();
        this.original.keySet().removeIf(block -> block >= firstBlockAtOrAfter(length));
        this.pendingFloor = Math.min(this.pendingFloor, length);
        this.pendingLength = length;
    }

    private void clearPending() {
        this.pending.clear();
        this.original.clear();
        this.pendingFloor = Long.MAX_VALUE;
    }

    /*
    True when no journaled change touches the range, so the disk has it as is.
    */
    private boolean readsStraightFromDisk(long position, int length) throws Exception {
        long end = position + length;
        if (end > this.pendingFloor || end > this.committedFloor) {
            return false;
        }
        long first = position / BLOCK;
        long last = (end - 1) / BLOCK;
        return (this.pending.isEmpty() || this.pending.subMap(first, true, last, true).isEmpty())
            && (this.committed.isEmpty() || this.committed.subMap(first, true, last, true).isEmpty())
            && end <= super.length();
    }

    /*
    A block's current content, for reading only.
    */
    private byte[] viewBlock(long block) throws Exception {
        byte[] pendingBlock = this.pending.get(block);
        return pendingBlock != null ? pendingBlock : this.baseBlock(block, false);
    }

    /*
    The pending copy of a block, created from its state before this operation.
    */
    private byte[] pendingBlock(long block) throws Exception {
        byte[] existing = this.pending.get(block);
        if (existing == null) {
            // The "before" copy: a committed block can be shared (committed arrays
            // are never changed in place, only replaced), anything else is new.
            byte[] before = this.baseBlock(block, false);
            existing = before.clone();
            this.original.put(block, before);
            this.pending.put(block, existing);
        }
        return existing;
    }

    /*
    A block as it was before the current operation: committed copy, else disk
    (with truncated bytes read as zeros). copy = return an array safe to change.
    */
    private byte[] baseBlock(long block, boolean copy) throws Exception {
        long start = block * BLOCK;
        byte[] content = this.committed.get(block);

        if (content == null) {
            content = new byte[BLOCK];
            long valid = Math.min(super.length(), this.committedFloor) - start;
            if (valid > 0) {
                byte[] onDisk = super.read(start, (int) Math.min(BLOCK, valid));
                System.arraycopy(onDisk, 0, content, 0, onDisk.length);
            }
        } else if (copy || this.pendingFloor < start + BLOCK) {
            content = content.clone();
        }

        if (this.pendingFloor < start + BLOCK) {
            int from = (int) Math.max(0, this.pendingFloor - start);
            Arrays.fill(content, from, BLOCK, (byte) 0);
        }
        return content;
    }

    private static long firstBlockAtOrAfter(long position) {
        return (position + BLOCK - 1) / BLOCK;
    }

    private long diskLengthQuietly() {
        try {
            return super.length();
        } catch (Exception e) {
            return -1;
        }
    }
}
