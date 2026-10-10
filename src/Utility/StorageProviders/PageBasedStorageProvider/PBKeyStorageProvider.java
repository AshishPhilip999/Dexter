package Utility.StorageProviders.PageBasedStorageProvider;

import java.io.Serializable;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.Constants.Extension;
import Utility.Encoding.KeyCodec;
import Utility.StorageProviders.PageBasedStorageProvider.Index.BPlusTree;
import Utility.StorageProviders.PageBasedStorageProvider.Index.IndexFile;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Page;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;

/*
Key based variant of PBStorageProvider. Page / slot handling, file IO and the
keyless write() are inherited from the parent; this class adds a key index on
top, and read() returns every record.

Both files live in the storage's .dex folder (see DexBundle). Records are
stored as [encoded key][data] in data.pb; the index lives in index.pbi as two
disk-based B+ trees:

    key index    encoded key -> (page, slot) of the key's live record
    free space   (fullLength, page, slot) of every deleted slot, smallest first,
                 so a write can find the best-fitting slot to reuse

Space: see PBStorageProvider. compact() also rebuilds the index file, which
otherwise only grows (B+ tree nodes are not merged on delete).

Thread safety: as for PBStorageProvider. write / delete / compact / reconstructKeyIndex
are exclusive; read / containsKey run in parallel with each other.

Only the pages a call touches are read, through small page caches, so memory
use does not grow with the number of records and opening a file only reads its
headers. The index file records the data file's id; if it is missing or
belongs to another data file it is rebuilt from the data pages on open.

The key type is given at initialisation:
    new PBKeyStorageProvider<>(path, Integer.class)
and stored in the data file's header. Opening an existing file with a different
key type throws IllegalStateException. The generic K makes a wrong key a
compile error; the Class<K> argument keeps the type at runtime so raw-typed /
unchecked callers are still rejected.
*/
public class PBKeyStorageProvider<K extends Serializable> extends PBStorageProvider {
    /*
    Largest encoded key the index accepts (KEY_TOO_LARGE above this).
    */
    public static final int MAX_KEY_BYTES = BPlusTree.MAX_KEY_SIZE;

    private static final int KEY_INDEX_TREE = 0;
    private static final int FREE_SPACE_TREE = 1;
    private static final byte[] NO_VALUE = new byte[0];

    /*
    The single key type this instance accepts.
    */
    private final Class<?> keyType;

    /*
    The .pbi file holding both trees.
    */
    private final IndexFile indexFile;

    /*
    Encoded key -> RecordID (page number + slot number) of its live record.
    */
    private final BPlusTree keyIndex;

    /*
    Deleted slots ordered by (fullLength, page, slot), for best-fit reuse.
    */
    private final BPlusTree freeSpace;

    public PBKeyStorageProvider(String path, Class<K> keyType) throws Exception {
        super(path);
        if (keyType == null) {
            throw new IllegalArgumentException("Key type cannot be null");
        }

        /*
        Keys always arrive boxed (write(5, ...) passes an Integer), and a primitive
        class never matches an object via isInstance. So map int.class -> Integer.class
        etc.: MethodType "() -> int" .wrap() gives "() -> Integer", whose return type
        is the wrapper class.
        */
        this.keyType = keyType.isPrimitive()
            ? MethodType.methodType(keyType).wrap().returnType()
            : keyType;

        this.bindStoredKeyType();

        this.indexFile = new IndexFile(DexBundle.indexPath(this.getFolder()), Extension.PBI);
        this.keyIndex = new BPlusTree(this.indexFile, KEY_INDEX_TREE);
        this.freeSpace = new BPlusTree(this.indexFile, FREE_SPACE_TREE);

        if (this.indexFile.getDataFileId() != this.getFileId()) {
            this.reconstructKeyIndex();
        }
    }

    /*
    New or keyless file: store this key type in its header.
    File with a key type already: it must match this one.
    */
    private void bindStoredKeyType() throws Exception {
        String stored = this.getStoredKeyType();
        if (stored == null) {
            this.setStoredKeyType(this.keyType.getName());
            return;
        }

        if (!stored.equals(this.keyType.getName())) {
            throw new IllegalStateException(
                "Storage file key type " + stored + " does not match this storage's key type " + this.keyType.getName()
            );
        }
    }

    /*
    Rebuilds the index file from the data pages: every live keyed record goes
    into the key index, every deleted slot into the free-space tree. Runs
    automatically on open when the index file is missing or doesn't belong to
    the data file; call it yourself to repair an index after a crash.
    */
    public void reconstructKeyIndex() throws Exception {
        this.writeLock().lock();
        try {
            this.rebuildIndex();
        } finally {
            this.writeLock().unlock();
        }
    }

    private void rebuildIndex() throws Exception {
        this.indexFile.clear();

        for (int pageNumber = 0; pageNumber < this.getPageCount(); pageNumber++) {
            Page page = this.getPage(pageNumber);

            for (short slotNumber = 0; slotNumber < page.getSlots().size(); slotNumber++) {
                Slot slot = page.getSlots().get(slotNumber);
                RecordID recordID = new RecordID(pageNumber, slotNumber);

                if (slot.isDeleted()) {
                    this.onSlotFreed(recordID, slot);
                    continue;
                }
                if (slot.getKeyLength() == 0) {
                    continue;
                }

                byte[] keyBytes = this.readSlotKey(slot);
                Object key = KeyCodec.decode(keyBytes);
                if (!this.keyType.isInstance(key)) {
                    throw new IllegalStateException(
                        "Stored key of type " + key.getClass().getName()
                        + " does not match this storage's key type " + this.keyType.getName()
                    );
                }

                byte[] previous = this.keyIndex.put(keyBytes, encodeRecordID(recordID));
                if (previous != null) {
                    // Two live records for one key (e.g. a crash mid-overwrite): keep the later one.
                    this.markSlotDeleted(decodeRecordID(previous));
                }
            }
        }

        this.indexFile.setDataFileId(this.getFileId());
    }

    /*
    Keyless write behaves exactly like the parent: all existing pages are dropped
    and a single new page is written with this data. The index is cleared too.
    SUCCESS: PBSuccess.WRITE, result.getData() holds the written data
    FAIL:    NULL_WRITE, DATA_TOO_LARGE, WRITE_ERROR
    */
    @Override
    public PBResult write(byte[] data) {
        this.writeLock().lock();
        try {
            PBResult result = super.write(data);
            if (!result.hasSucceeded()) {
                return result;
            }

            this.resetIndex();
        } catch (Exception e) {
            return PBResult.of(PBFail.WRITE_ERROR);
        } finally {
            this.writeLock().unlock();
        }
        return PBResult.of(PBSuccess.WRITE, data);
    }

    /*
    Writes data against a key. The record [encoded key][data] goes into the
    best-fitting deleted slot if one is big enough; otherwise it is appended to
    the last page, or to a new page if that one is full. The key index then
    points the key at it. Writing an existing key stores the new record first,
    then marks the old slot as deleted (upsert).
    SUCCESS: PBSuccess.WRITE, result.getData() holds the written data
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, NULL_WRITE, KEY_ENCODE_ERROR,
             KEY_TOO_LARGE, DATA_TOO_LARGE, WRITE_ERROR
    */
    public PBResult write(K key, byte[] data) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return PBResult.of(invalidKey);
        }
        if (data == null || data.length == 0) {
            return PBResult.of(PBFail.NULL_WRITE);
        }

        byte[] keyBytes;
        try {
            keyBytes = KeyCodec.encode(key);
        } catch (Exception e) {
            return PBResult.of(PBFail.KEY_ENCODE_ERROR);
        }
        if (keyBytes.length > MAX_KEY_BYTES) {
            return PBResult.of(PBFail.KEY_TOO_LARGE);
        }

        PBFail invalidRecord = this.validateRecord(keyBytes.length, data);
        if (invalidRecord != null) {
            return PBResult.of(invalidRecord);
        }

        this.writeLock().lock();
        try {
            RecordID placed = this.placeRecord(keyBytes, data);
            byte[] previous = this.keyIndex.put(keyBytes, encodeRecordID(placed));

            if (previous != null) {
                this.markSlotDeleted(decodeRecordID(previous));
            }
        } catch (Exception e) {
            return PBResult.of(PBFail.WRITE_ERROR);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.WRITE, data);
    }

    /*
    Reads the data written against a key: index lookup, then the data page.
    SUCCESS: PBSuccess.READ, result.getData() holds the record
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, READ_ERROR
    */
    public PBResult read(K key) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return PBResult.of(invalidKey);
        }

        byte[] keyBytes = this.encodeForLookup(key);
        if (keyBytes == null) {
            return PBResult.of(PBFail.KEY_NOT_FOUND);
        }

        this.readLock().lock();
        try {
            byte[] location = this.keyIndex.get(keyBytes);
            if (location == null) {
                return PBResult.of(PBFail.KEY_NOT_FOUND);
            }

            Slot slot = this.getSlot(decodeRecordID(location));
            if (slot == null || slot.isDeleted() || slot.getKeyLength() != keyBytes.length) {
                return PBResult.of(PBFail.READ_ERROR);
            }

            byte[] record = this.readRecord(slot);
            if (!Arrays.equals(record, 0, keyBytes.length, keyBytes, 0, keyBytes.length)) {
                return PBResult.of(PBFail.READ_ERROR);
            }
            return PBResult.of(PBSuccess.READ, Arrays.copyOfRange(record, keyBytes.length, record.length));
        } catch (Exception e) {
            return PBResult.of(PBFail.READ_ERROR);
        } finally {
            this.readLock().unlock();
        }
    }

    /*
    Every live record (all keys), one entry per record in page and slot order,
    each deserializable back into its own object. Overrides the parent's
    read(), which returns only the first record.
    SUCCESS: PBSuccess.READ, result.getDataArray() holds the records
    FAIL:    NULL_READ (nothing stored), READ_ERROR
    */
    @Override
    public PBResult read() {
        byte[][] records;
        this.readLock().lock();
        try {
            records = this.readAllRecords();
        } catch (Exception e) {
            return PBResult.of(PBFail.READ_ERROR);
        } finally {
            this.readLock().unlock();
        }

        if (records.length == 0) {
            return PBResult.of(PBFail.NULL_READ);
        }
        return PBResult.ofAll(PBSuccess.READ, records);
    }

    /*
    Deletes the record of one key: the key leaves the index, and its slot is
    marked deleted and becomes reusable by later writes.
    SUCCESS: PBSuccess.DELETE
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, DELETE_ERROR
    */
    public PBResult delete(K key) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return PBResult.of(invalidKey);
        }

        byte[] keyBytes = this.encodeForLookup(key);
        if (keyBytes == null) {
            return PBResult.of(PBFail.KEY_NOT_FOUND);
        }

        this.writeLock().lock();
        try {
            byte[] location = this.keyIndex.remove(keyBytes);
            if (location == null) {
                return PBResult.of(PBFail.KEY_NOT_FOUND);
            }
            this.markSlotDeleted(decodeRecordID(location));
        } catch (Exception e) {
            return PBResult.of(PBFail.DELETE_ERROR);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.DELETE);
    }

    /*
    Erases everything, like the parent, and clears the index.
    SUCCESS: PBSuccess.DELETE
    FAIL:    DELETE_ERROR
    */
    @Override
    public PBResult delete() {
        this.writeLock().lock();
        try {
            PBResult result = super.delete();
            if (!result.hasSucceeded()) {
                return result;
            }

            this.resetIndex();
        } catch (Exception e) {
            return PBResult.of(PBFail.DELETE_ERROR);
        } finally {
            this.writeLock().unlock();
        }
        return PBResult.of(PBSuccess.DELETE);
    }

    /*
    false for a null key, a key of the wrong type, or on a read error.
    */
    public boolean containsKey(K key) {
        if (this.validateKey(key) != null) {
            return false;
        }
        byte[] keyBytes = this.encodeForLookup(key);
        if (keyBytes == null) {
            return false;
        }
        this.readLock().lock();
        try {
            return this.keyIndex.get(keyBytes) != null;
        } catch (Exception e) {
            return false;
        } finally {
            this.readLock().unlock();
        }
    }

    public Class<?> getKeyType() {
        return this.keyType;
    }

    /*
    Index pages currently held in memory.
    */
    protected int getCachedIndexPageCount() {
        return this.indexFile.getCachedPageCount();
    }

    // ---- free-space hooks -----------------------------------------------------

    /*
    Best fit: the smallest deleted slot whose fullLength is >= recordLength.
    Stale entries (slot no longer deleted or too small) are dropped.
    */
    @Override
    protected RecordID takeFreeSlot(short recordLength) throws Exception {
        byte[] from = freeSpaceKey(recordLength, 0, (short) 0);

        Map.Entry<byte[], byte[]> entry;
        while ((entry = this.freeSpace.ceiling(from)) != null) {
            byte[] entryKey = entry.getKey();
            this.freeSpace.remove(entryKey);

            ByteBuffer buffer = ByteBuffer.wrap(entryKey);
            buffer.getShort();
            RecordID recordID = new RecordID(buffer.getInt(), buffer.getShort());

            Slot slot = this.getSlot(recordID);
            if (slot != null && slot.isDeleted() && slot.getFullLength() >= recordLength) {
                return recordID;
            }
        }
        return null;
    }

    @Override
    protected void onSlotFreed(RecordID recordID, Slot slot) throws Exception {
        this.freeSpace.put(freeSpaceKey(slot.getFullLength(), recordID.pageNumber, recordID.slotNumber), NO_VALUE);
    }

    /*
    The slot's page was cut off the end of the file: forget it.
    */
    @Override
    protected void onSlotDropped(RecordID recordID, Slot slot) throws Exception {
        this.freeSpace.remove(freeSpaceKey(slot.getFullLength(), recordID.pageNumber, recordID.slotNumber));
    }

    /*
    compact() moved every record: build a new index (which also packs the index
    file, since it starts from empty).
    */
    @Override
    protected void onCompacted() throws Exception {
        this.rebuildIndex();
    }

    // ---- helpers --------------------------------------------------------------

    /*
    Empty index for the (just erased) data file, tagged with its new id.
    */
    private void resetIndex() throws Exception {
        this.indexFile.clear();
        this.indexFile.setDataFileId(this.getFileId());
    }

    /*
    Returns the failure for an invalid key, or null if the key is valid.
    */
    private PBFail validateKey(K key) {
        if (key == null) {
            return PBFail.NULL_KEY;
        }
        if (!this.keyType.isInstance(key)) {
            return PBFail.KEY_TYPE_MISMATCH;
        }
        return null;
    }

    /*
    Encoded key, or null if it can't be encoded or is too large to be stored
    (such a key can never be in the index).
    */
    private byte[] encodeForLookup(K key) {
        try {
            byte[] keyBytes = KeyCodec.encode(key);
            return keyBytes.length > MAX_KEY_BYTES ? null : keyBytes;
        } catch (Exception e) {
            return null;
        }
    }

    /*
    (fullLength, page, slot) as big-endian bytes, so byte order = size order.
    */
    private static byte[] freeSpaceKey(short fullLength, int pageNumber, short slotNumber) {
        return ByteBuffer.allocate(Short.BYTES + Integer.BYTES + Short.BYTES)
                         .putShort(fullLength)
                         .putInt(pageNumber)
                         .putShort(slotNumber)
                         .array();
    }

    private static byte[] encodeRecordID(RecordID recordID) {
        return ByteBuffer.allocate(Integer.BYTES + Short.BYTES)
                         .putInt(recordID.pageNumber)
                         .putShort(recordID.slotNumber)
                         .array();
    }

    private static RecordID decodeRecordID(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new RecordID(buffer.getInt(), buffer.getShort());
    }
}
