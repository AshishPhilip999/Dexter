package StorageStatus.SP;

/*
Result of one storage operation (read, write, delete, ...). Every call returns
a new result, so a result keeps its own data: it can be stored, compared with
later results, or handed to another thread.

    SPResult result = storage.read(42);
    if (result.hasSucceeded()) {
        byte[] data = result.getData();
    } else if (result.getStatus() == SPFail.KEY_NOT_FOUND) {
        ...
    } else if (result.getError() != null) {
        log(result.getError());     // why a READ_ERROR / WRITE_ERROR / ... happened
    }

hasSucceeded() is the overall pass/fail check. getStatus() is the detailed
outcome: a SPSuccess (READ, WRITE, DELETE, ...) or a SPFail value.

Results can't be changed after they are made: the fields are final and so are
the getters. Subclasses may only add to it -- ContainerCore.Container.ContainerResult
adds the stored value as an object.
*/
public class SPResult {
    /*
    True if the operation passed (getStatus() is a SPSuccess), false if it
    failed (getStatus() is a SPFail).
    */
    private final boolean hasSucceeded;

    private final SPStatus status;

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

    /*
    Failures only: the exception that caused it, e.g. the IOException behind a
    WRITE_ERROR. null for successes and for failures found by checking the
    input (NULL_KEY, KEY_NOT_FOUND, DATA_TOO_LARGE, ...).
    */
    private final Exception error;

    protected SPResult(SPStatus status, byte[] data, byte[][] dataArray, Exception error) {
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        this.hasSucceeded = status instanceof SPSuccess;
        this.status = status;
        this.data = data;
        this.dataArray = dataArray;
        this.error = error;
    }

    /*
    A copy of another result (for subclasses that wrap one).
    */
    protected SPResult(SPResult result) {
        this(result.status, result.data, result.dataArray, result.error);
    }

    /*
    A result with no data (failures, DELETE).
    */
    public static SPResult of(SPStatus status) {
        return new SPResult(status, null, null, null);
    }

    /*
    A result carrying one record (READ) or the written data (WRITE).
    */
    public static SPResult of(SPStatus status, byte[] data) {
        return new SPResult(status, data, null, null);
    }

    /*
    A result carrying several records. getData() returns the first one.
    */
    public static SPResult ofAll(SPStatus status, byte[][] dataArray) {
        byte[] first = (dataArray != null && dataArray.length > 0) ? dataArray[0] : null;
        return new SPResult(status, first, dataArray, null);
    }

    /*
    A failure caused by an exception: READ_ERROR, WRITE_ERROR, DELETE_ERROR,
    COMPACT_ERROR, KEY_ENCODE_ERROR, ... Takes a SPFail, so a success can never
    carry an error.
    */
    public static SPResult ofError(SPFail status, Exception error) {
        return new SPResult(status, null, null, error);
    }

    /*
    The detailed outcome: a SPSuccess or a SPFail value.
    */
    public final SPStatus getStatus() {
        return this.status;
    }

    /*
    Overall check: true if the operation passed, false if it failed.
    */
    public final boolean hasSucceeded() {
        return this.hasSucceeded;
    }

    public final byte[] getData() {
        return this.data;
    }

    public final byte[][] getDataArray() {
        return this.dataArray;
    }

    /*
    Why the operation failed, when an exception caused it; null otherwise
    (including every success). Check getStatus() for what failed.
    */
    public final Exception getError() {
        return this.error;
    }

    @Override
    public String toString() {
        if (this.error != null) {
            return this.status + " (" + this.error + ")";
        }
        if (this.dataArray != null) {
            return this.status + " (" + this.dataArray.length + " records)";
        }
        if (this.data != null) {
            return this.status + " (" + this.data.length + " bytes)";
        }
        return String.valueOf(this.status);
    }
}
