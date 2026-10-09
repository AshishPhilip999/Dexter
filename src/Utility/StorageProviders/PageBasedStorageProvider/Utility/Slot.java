package Utility.StorageProviders.PageBasedStorageProvider.Utility;

/*
Slot entry on disk:
    offset(8) + fullLength(2) + length(2) + keyLength(4) + deleted(1) = 17 bytes.

The record at offset is [key bytes][data bytes].
- fullLength: bytes reserved for this slot when it was first created. Never
  changes, so a deleted slot can be reused for any record up to this size.
- length:     bytes of the record currently in the slot (key + data); never
  exceeds fullLength.
- keyLength:  0 for keyless records.
*/
public class Slot {
    private long offset;
    private short fullLength;
    private short length;
    private int keyLength;
    private boolean deleted;

    public Slot(long offset, short dataLength) {
        this(offset, dataLength, dataLength, 0, false);
    }

    public Slot(long offset, short fullLength, short length, int keyLength, boolean deleted) {
        if (length > fullLength) {
            throw new IllegalArgumentException(
                "Slot length " + length + " exceeds its full length " + fullLength
            );
        }
        this.offset = offset;
        this.fullLength = fullLength;
        this.length = length;
        this.keyLength = keyLength;
        this.deleted = deleted;
    }

    public long getOffset() {
        return offset;
    }

    /*
    Bytes reserved for this slot; the capacity checked when reusing it.
    */
    public short getFullLength() {
        return fullLength;
    }

    /*
    Current record length: key bytes + data bytes.
    */
    public short getLength() {
        return length;
    }

    public int getKeyLength() {
        return keyLength;
    }

    public int getDataLength() {
        return length - keyLength;
    }

    public long getDataOffset() {
        return offset + keyLength;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void deletePage(boolean set) {
        this.deleted = set;
    }

    /*
    Puts a new record into this (deleted) slot: same offset and fullLength,
    new length / keyLength, no longer deleted.
    */
    public void reuse(short length, int keyLength) {
        if (length > this.fullLength) {
            throw new IllegalArgumentException(
                "Record of " + length + " bytes does not fit slot of " + this.fullLength + " bytes"
            );
        }
        this.length = length;
        this.keyLength = keyLength;
        this.deleted = false;
    }
}
