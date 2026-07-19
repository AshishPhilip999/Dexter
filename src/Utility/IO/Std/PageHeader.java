package Utility.IO.Std;

public class PageHeader {

    private short slotCount;
    private short freeSpaceStart;
    private short freeSpaceEnd;
    private short freeBytes;

    public PageHeader(int pageSize) {
        this.slotCount = 0;
        this.freeSpaceStart = 0;
        this.freeSpaceEnd = (short) pageSize;
        this.freeBytes = (short) pageSize;
    }

    public short getSlotCount() {
        return slotCount;
    }

    public void incrementSlotCount() {
        slotCount++;
    }

    public short getFreeSpaceStart() {
        return freeSpaceStart;
    }

    public void setFreeSpaceStart(short freeSpaceStart) {
        this.freeSpaceStart = freeSpaceStart;
    }

    public short getFreeSpaceEnd() {
        return freeSpaceEnd;
    }

    public void setFreeSpaceEnd(short freeSpaceEnd) {
        this.freeSpaceEnd = freeSpaceEnd;
    }

    public short getFreeBytes() {
        return freeBytes;
    }

    public void setFreeBytes(short freeBytes) {
        this.freeBytes = freeBytes;
    }
}