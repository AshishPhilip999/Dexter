package StorageStatus.PB;

import StorageStatus.SPStatus;

public enum PBSuccess implements PBStatus {
    READ,
    WRITE,
    DELETE
    ;

    /*
    READ:  the record read by read() / read(key).
    WRITE: the data that was written.
    */
    private byte[] data;

    /*
    READ: every record read by the keyed read(), one entry per record.
    */
    private byte[][] dataArray;

    public byte[] getData() {
        return this.data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public byte[][] getDataArray() {
        return this.dataArray;
    }

    public void setDataArray(byte[][] dataArray) {
        this.dataArray = dataArray;
    }

    /*
    READ with a single record set (dataArray cleared).
    */
    public static PBSuccess read(byte[] data) {
        READ.setData(data);
        READ.setDataArray(null);
        return READ;
    }

    /*
    READ with every record set (data cleared).
    */
    public static PBSuccess readAll(byte[][] dataArray) {
        READ.setData(dataArray[0]);
        READ.setDataArray(dataArray);
        return READ;
    }

    /*
    WRITE with the written data set (dataArray cleared).
    */
    public static PBSuccess write(byte[] data) {
        WRITE.setData(data);
        WRITE.setDataArray(null);
        return WRITE;
    }

    @Override
    public OperationStatus getStatus() {
        return OperationStatus.SUCCESS;
    }
}
