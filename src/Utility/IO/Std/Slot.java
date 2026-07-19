package Utility.IO.Std;

public class Slot {

    private short offset;
    private short length;
    private boolean deleted;

    public Slot(short offset, short length) {
        this.offset = offset;
        this.length = length;
        this.deleted = false;
    }

    public short getOffset() {
        return offset;
    }

    public void setOffset(short offset) {
        this.offset = offset;
    }

    public short getLength() {
        return length;
    }

    public void setLength(short length) {
        this.length = length;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}