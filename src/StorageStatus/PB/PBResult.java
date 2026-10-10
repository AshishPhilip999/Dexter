package StorageStatus.PB;

/*
Result of one read, write or delete. Every call returns a new PBResult, so a
result keeps its own data: it can be stored, compared with later results, or
handed to another thread.

    PBResult result = storage.read(42);
    if (result.hasSucceeded()) {
        byte[] data = result.getData();
    } else if (result.getStatus() == PBFail.KEY_NOT_FOUND) {
        ...
    }

hasSucceeded() is the overall pass/fail check. getStatus() is the detailed
outcome: a PBSuccess (READ, WRITE, DELETE) or a PBFail value.
*/
public final class PBResult {
    /*
    True if the operation passed (getStatus() is a PBSuccess), false if it
    failed (getStatus() is a PBFail).
    */
    private final boolean hasSucceeded;

    private final PBStatus status;

    /*
    READ:  the record read by read() / read(key); for the keyed read(), the
           first record.
    WRITE: the data that was written.
    null otherwise.
    */
    private final byte[] data;

    /*
    READ by the keyed read(): every record, one entry per record.
    null otherwise.
    */
    private final byte[][] dataArray;

    private PBResult(PBStatus status, byte[] data, byte[][] dataArray) {
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        this.hasSucceeded = status instanceof PBSuccess;
        this.status = status;
        this.data = data;
        this.dataArray = dataArray;
    }

    /*
    A result with no data (failures, DELETE).
    */
    public static PBResult of(PBStatus status) {
        return new PBResult(status, null, null);
    }

    /*
    A result carrying one record (READ) or the written data (WRITE).
    */
    public static PBResult of(PBStatus status, byte[] data) {
        return new PBResult(status, data, null);
    }

    /*
    A result carrying several records. getData() returns the first one.
    */
    public static PBResult ofAll(PBStatus status, byte[][] dataArray) {
        byte[] first = (dataArray != null && dataArray.length > 0) ? dataArray[0] : null;
        return new PBResult(status, first, dataArray);
    }

    /*
    The detailed outcome: a PBSuccess or a PBFail value.
    */
    public PBStatus getStatus() {
        return this.status;
    }

    /*
    Overall check: true if the operation passed, false if it failed.
    */
    public boolean hasSucceeded() {
        return this.hasSucceeded;
    }

    public byte[] getData() {
        return this.data;
    }

    public byte[][] getDataArray() {
        return this.dataArray;
    }

    @Override
    public String toString() {
        if (this.dataArray != null) {
            return this.status + " (" + this.dataArray.length + " records)";
        }
        if (this.data != null) {
            return this.status + " (" + this.data.length + " bytes)";
        }
        return String.valueOf(this.status);
    }
}
