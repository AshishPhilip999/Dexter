package Utility.StorageProviders.PageBasedStorageProvider;

import java.io.Serializable;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import StorageStatus.PB.OperationStatus;
import Utility.Encoding.KeyCodec;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Page;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;

/*
Key based variant of PBStorageProvider. Page / slot orchestration, file IO and
the keyless write() is inherited from the parent; this class adds the
key -> RecordID index on top, and read() returns every record.

Each keyed record is stored as [encoded key][data], with the key's byte
size kept in the slot (keyLength). Keys are encoded compactly by KeyCodec.
On startup the index is rebuilt from those stored keys, so keyed reads work
across program restarts.

The key type is given twice in one place, at initialisation:
    new PBKeyStorageProvider<>(path, Integer.class)
The key type is stored in the file header. Opening an existing file with a
different key type throws IllegalStateException.
The generic K makes a wrong key a compile error; the Class<K> argument keeps the
type at runtime so raw-typed / unchecked callers are still rejected.
*/
public class PBKeyStorageProvider<K extends Serializable> extends PBStorageProvider {
    /*
    Key -> location (page, slot) of the live record for that key.
    */
    private final Map<K, RecordID> keyIndex;

    /*
    The single key type this instance accepts.
    */
    private final Class<?> keyType;

    public PBKeyStorageProvider(String path, Class<K> keyType) throws Exception {
        super(path);
        if (keyType == null) {
            throw new IllegalArgumentException("Key type cannot be null");
        }
        this.keyIndex = new HashMap<>();

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
        this.reconstructKeyIndex();
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
    Rebuilds the key -> RecordID map by walking every slot of every page and
    reading the key stored in front of each live record. Deleted slots (old
    versions of overwritten keys) and keyless slots are skipped.
    */
    @SuppressWarnings("unchecked")
    public void reconstructKeyIndex() throws Exception {
        this.keyIndex.clear();

        for (Page page : this.getPages()) {
            List<Slot> slots = page.getSlots();

            for (short slotNumber = 0; slotNumber < slots.size(); slotNumber++) {
                Slot slot = slots.get(slotNumber);
                if (slot.isDeleted() || slot.getKeyLength() == 0) {
                    continue;
                }

                Object key = KeyCodec.decode(this.readSlotKey(slot));
                if (!this.keyType.isInstance(key)) {
                    throw new IllegalStateException(
                        "Stored key of type " + key.getClass().getName()
                        + " does not match this storage's key type " + this.keyType.getName()
                    );
                }

                this.keyIndex.put((K) key, new RecordID(page.getPageNumber(), slotNumber));
            }
        }
    }

    /*
    Keyless write behaves exactly like the parent: all existing pages are dropped
    and a single new page is written with this data. The key index is cleared
    too, otherwise old keys would point at RecordIDs (e.g. page 0, slot 0) that
    now hold the keyless data.
    SUCCESS: PBSuccess.WRITE, getData() holds the written data
    FAIL:    NULL_WRITE, DATA_TOO_LARGE, WRITE_ERROR
    */
    @Override
    public PBStatus write(byte[] data) {
        PBStatus status = super.write(data);
        if (status.getStatus() == OperationStatus.SUCCESS) {
            this.keyIndex.clear();
        }
        return status;
    }

    /*
    Writes data against a key. The record [encoded key][data] goes into the
    best-fitting deleted slot if one is big enough; otherwise it is appended to
    the last page (slot directory grows forward from the header, data grows
    backward from the page end), or to a new page if that one is full.
    Writing an existing key appends the new record first, then marks the old
    slot as deleted (upsert).
    SUCCESS: PBSuccess.WRITE, getData() holds the written data
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, NULL_WRITE, KEY_ENCODE_ERROR,
             DATA_TOO_LARGE, WRITE_ERROR
    */
    public PBStatus write(K key, byte[] data) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return invalidKey;
        }
        if (data == null || data.length == 0) {
            return PBFail.NULL_WRITE;
        }

        byte[] keyBytes;
        try {
            keyBytes = KeyCodec.encode(key);
        } catch (Exception e) {
            return PBFail.KEY_ENCODE_ERROR;
        }

        PBFail invalidRecord = this.validateRecord(keyBytes.length, data);
        if (invalidRecord != null) {
            return invalidRecord;
        }

        try {
            RecordID existing = this.keyIndex.get(key);

            RecordID placed = this.placeRecord(keyBytes, data);

            if (existing != null) {
                this.markSlotDeleted(existing);
            }

            this.keyIndex.put(key, placed);
        } catch (Exception e) {
            return PBFail.WRITE_ERROR;
        }

        return PBSuccess.write(data);
    }

    /*
    Reads the data written against a key.
    SUCCESS: PBSuccess.READ, getData() holds the record
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, READ_ERROR
    */
    public PBStatus read(K key) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return invalidKey;
        }

        RecordID recordID = this.keyIndex.get(key);
        if (recordID == null) {
            return PBFail.KEY_NOT_FOUND;
        }

        Slot slot = this.getSlot(recordID);
        if (slot == null || slot.isDeleted()) {
            return PBFail.KEY_NOT_FOUND;
        }

        try {
            return PBSuccess.read(this.readSlot(slot));
        } catch (Exception e) {
            return PBFail.READ_ERROR;
        }
    }

    /*
    Every live record (all keys), one entry per record in write order, each
    deserializable back into its own object. Overrides the parent's read(),
    which returns only the first record.
    SUCCESS: PBSuccess.READ, getDataArray() holds the records
    FAIL:    NULL_READ (nothing stored), READ_ERROR
    */
    @Override
    public PBStatus read() {
        byte[][] records;
        try {
            records = this.readAllRecords();
        } catch (Exception e) {
            return PBFail.READ_ERROR;
        }

        if (records.length == 0) {
            return PBFail.NULL_READ;
        }
        return PBSuccess.readAll(records);
    }

    /*
    Deletes the record of one key: its slot is marked deleted (and becomes
    reusable by later writes) and the key is removed from the index.
    SUCCESS: PBSuccess.DELETE
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, DELETE_ERROR
    */
    public PBStatus delete(K key) {
        PBFail invalidKey = this.validateKey(key);
        if (invalidKey != null) {
            return invalidKey;
        }

        RecordID recordID = this.keyIndex.get(key);
        if (recordID == null) {
            return PBFail.KEY_NOT_FOUND;
        }

        try {
            this.markSlotDeleted(recordID);
        } catch (Exception e) {
            return PBFail.DELETE_ERROR;
        }

        this.keyIndex.remove(key);
        return PBSuccess.DELETE;
    }

    /*
    Erases everything, like the parent, and clears the key index.
    SUCCESS: PBSuccess.DELETE
    FAIL:    DELETE_ERROR
    */
    @Override
    public PBStatus delete() {
        PBStatus status = super.delete();
        if (status.getStatus() == OperationStatus.SUCCESS) {
            this.keyIndex.clear();
        }
        return status;
    }

    /*
    false for a null key or a key of the wrong type.
    */
    public boolean containsKey(K key) {
        return this.validateKey(key) == null && this.keyIndex.containsKey(key);
    }

    public Class<?> getKeyType() {
        return this.keyType;
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
}
