package StorageStatus.Writer.SuccessStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IODeleteSuccess implements IOStatus {
    FINISH_DELETE;

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
