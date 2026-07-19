package StorageStatus.Writer.SuccessStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IOReadSuccess implements IOStatus {
    FILE_FOUND,
    FINISH_READ
    ;

    private byte[] data;

    public byte[] getData() {
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public SPStatus getSPStatus() {
        return SPStatus.SUCCESS;
    }
}
