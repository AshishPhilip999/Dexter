package Utility.StorageProviders.PageBasedStorageProvider.Utility;

public class PageHeader {
    private long offSet;
    private short slotCount;
    private long freeSpaceStart;
    private long freeSpaceEnd;
    private long freeBytes;

    public PageHeader(long offSet) {
        this.offSet = offSet;
        this.slotCount = 0;
        this.freeSpaceStart = offSet + PBOffSets.HEADER.getValue();
        this.freeSpaceEnd = offSet + PBOffSets.PAGE.getValue();
        this.freeBytes = PBOffSets.PAGE.getValue() - PBOffSets.HEADER.getValue();
    }

    /*
    Restores a header read back from disk.
    */
    public PageHeader(long offSet, short slotCount, long freeSpaceStart, long freeSpaceEnd, long freeBytes) {
        this.offSet = offSet;
        this.slotCount = slotCount;
        this.freeSpaceStart = freeSpaceStart;
        this.freeSpaceEnd = freeSpaceEnd;
        this.freeBytes = freeBytes;
    }

    public long getOffSet() {
        return this.offSet;
    }

    public short getSlotCount() {
        return slotCount;
    }

    public void incrementSlotCount() {
        slotCount++;
    }

    public long getFreeSpaceStart() {
        return freeSpaceStart;
    }

    public void setFreeSpaceStart(short freeSpaceStart) {
        this.freeSpaceStart = freeSpaceStart;
    }

    public long getFreeSpaceEnd() {
        return freeSpaceEnd;
    }

    public long getFreeBytes() {
        return freeBytes;
    }

    public void setFreeBytes(short freeBytes) {
        this.freeBytes = freeBytes;
    }

    public short getObjectSize() {
        return (Long.BYTES + (Short.BYTES * 4));
    }

    public void updateFreeSace(short dataLength) {
        this.freeSpaceStart += PBOffSets.SLOT.getValue();
        this.freeSpaceEnd -= dataLength;
        this.freeBytes -= (PBOffSets.SLOT.getValue() + dataLength);
    }
}