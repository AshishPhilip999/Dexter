package ContainerCore.Container;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;

import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerMeta;
import StorageCore.Storage;
import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.DexterFileService;
import Utility.StorageProviders.StorageProviderType;

/*
A container holds one value of type T on disk. It is what applications use;
the storage underneath is a DexterFileService chosen by StorageProviderType
(PAGE_BASED by default), so the container works the same with any kind of
storage.

    try (Container<Settings> settings = new Container<>("data/settings", Settings.class)) {
        settings.write(new Settings(...));
        Settings current = settings.read().getValue();
    }

Values are serialized with Java serialization. Every operation returns a
ContainerResult<T>: an SPResult (status, bytes, error) that also carries the
value as a T (getValue()).

length() and size() report how many values the container holds and their
total size in bytes; maxLength / maxSize limit them (0 = no limit). These are
kept in a metadata file next to the storage, so they survive restarts. If the
program stops without close(), they are counted again from the storage the
next time the container is opened.

Thread-safe: writes and deletes are done one at a time; reads go straight to
the storage, which is itself thread-safe. One instance per storage (opening a
second throws StorageInUseException, see PBStorageProvider). Close it when
done (try-with-resources).
*/
public class Container<T extends Serializable> implements AutoCloseable {
    /*
    Opens the storage for a container. Keyed containers open keyed storage.
    */
    protected interface Opener {
        DexterFileService open(String path) throws Exception;
    }

    protected final String containerName;
    protected final Storage containerStorage;
    protected final StorageProviderType storageProviderType;
    protected final Class<T> valueType;

    /*
    The storage underneath.
    */
    protected final DexterFileService fileService;

    private final Path metaFile;

    /*
    Writes and deletes hold this lock, so a write's limit check, the write and
    the count update happen together.
    */
    protected final Object writeLock = new Object();

    private volatile long length;
    private volatile long size;
    private volatile long maxLength;
    private volatile long maxSize;
    private volatile boolean closed;

    // ---- constructors ------------------------------------------------------------

    /*
    A container at path containerName, on page-based storage, with no limits
    (or the limits it had when last closed).
    */
    public Container(String containerName, Class<T> valueType) throws Exception {
        this(null, containerName, valueType, StorageProviderType.PAGE_BASED, null, 0);
    }

    public Container(String containerName, Class<T> valueType, StorageProviderType storageProviderType) throws Exception {
        this(null, containerName, valueType, storageProviderType, null, 0);
    }

    /*
    With a limit: LENGTH sets maxLength to containerLimit values; BYTES,
    KILOBYTES, MEGABYTES, GIGABYTES set maxSize to containerLimit of that unit;
    NO_LIMIT clears both. Limits given here replace the stored ones.
    */
    public Container(String containerName, Class<T> valueType, ContainerLimitType containerLimitType,
            long containerLimit) throws Exception {
        this(null, containerName, valueType, StorageProviderType.PAGE_BASED, containerLimitType, containerLimit);
    }

    public Container(String containerName, Class<T> valueType, StorageProviderType storageProviderType,
            ContainerLimitType containerLimitType, long containerLimit) throws Exception {
        this(null, containerName, valueType, storageProviderType, containerLimitType, containerLimit);
    }

    /*
    A container inside a Storage: its path is the storage's directory + "/" +
    containerName. containerLimitType null = keep the stored limits.
    */
    public Container(Storage containerStorage, String containerName, Class<T> valueType,
            StorageProviderType storageProviderType, ContainerLimitType containerLimitType, long containerLimit)
            throws Exception {
        this(containerStorage, containerName, valueType, storageProviderType, containerLimitType, containerLimit,
            storageProviderType == null ? null : storageProviderType::open);
    }

    /*
    For subclasses that need a different kind of storage (KeyContainer).
    */
    protected Container(Storage containerStorage, String containerName, Class<T> valueType,
            StorageProviderType storageProviderType, ContainerLimitType containerLimitType, long containerLimit,
            Opener opener) throws Exception {
        if (containerName == null || containerName.isEmpty()) {
            throw new IllegalArgumentException("Container name cannot be empty");
        }
        if (valueType == null) {
            throw new IllegalArgumentException("Value type cannot be null");
        }
        if (storageProviderType == null) {
            throw new IllegalArgumentException("Storage provider type cannot be null");
        }
        if (containerLimit < 0) {
            throw new IllegalArgumentException("Container limit cannot be negative");
        }

        this.containerStorage = containerStorage;
        this.containerName = containerName;
        this.valueType = valueType;
        this.storageProviderType = storageProviderType;

        String path = this.getContainerFullPath();
        this.fileService = opener.open(path);
        this.metaFile = storageProviderType.metaFile(path);

        try {
            ContainerMeta meta = ContainerMeta.load(this.metaFile);
            if (meta != null && meta.closedCleanly) {
                this.length = meta.length;
                this.size = meta.size;
            } else {
                SPResult counted = this.countFromStorage();
                if (!counted.hasSucceeded()) {
                    throw new IOException("Could not count the container's values: " + counted, counted.getError());
                }
            }

            if (containerLimitType != null) {
                this.applyLimit(containerLimitType, containerLimit);
            } else if (meta != null) {
                this.maxLength = meta.maxLength;
                this.maxSize = meta.maxSize;
            }

            this.saveMeta(false);   // open: if the program stops now, the next open counts again
        } catch (Exception e) {
            try {
                this.fileService.close();
            } catch (Exception closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    // ---- operations ------------------------------------------------------------------

    /*
    Stores value, replacing whatever the container held (length becomes 1).
    SUCCESS: SPSuccess.WRITE, getValue() is the value written
    FAIL:    NULL_WRITE, VALUE_TYPE_MISMATCH, SERIALIZE_ERROR, LIMIT_EXCEEDED,
             STORAGE_CLOSED, the storage's WRITE_ERROR / DATA_TOO_LARGE
    */
    public ContainerResult<T> write(T value) {
        SPResult serialized = this.serialize(value);
        if (!serialized.hasSucceeded()) {
            return ContainerResult.from(serialized);
        }
        byte[] bytes = serialized.getData();

        synchronized (this.writeLock) {
            if (this.exceedsLimits(1, bytes.length)) {
                return ContainerResult.failed(SPFail.LIMIT_EXCEEDED);
            }
            SPResult result = this.fileService.write(bytes);
            if (result.hasSucceeded()) {
                this.length = 1;
                this.size = bytes.length;
                return ContainerResult.from(result, value);
            }
            return ContainerResult.from(result);
        }
    }

    /*
    The stored value (getValue()). On a KeyContainer: every value (getValues()).
    SUCCESS: SPSuccess.READ
    FAIL:    NULL_READ (nothing stored), READ_ERROR, DESERIALIZE_ERROR, STORAGE_CLOSED
    */
    public ContainerResult<T> read() {
        return this.withValues(this.fileService.read());
    }

    /*
    Erases everything (length and size become 0).
    SUCCESS: SPSuccess.DELETE
    FAIL:    DELETE_ERROR, STORAGE_CLOSED
    */
    public ContainerResult<T> delete() {
        synchronized (this.writeLock) {
            SPResult result = this.fileService.delete();
            if (result.hasSucceeded()) {
                this.length = 0;
                this.size = 0;
            }
            return ContainerResult.from(result);
        }
    }

    /*
    Counts the values again from the storage (normally done on its own when
    needed). Reads every value, so it takes time and memory in proportion to
    the container's contents.
    SUCCESS: SPSuccess.READ
    FAIL:    READ_ERROR, STORAGE_CLOSED
    */
    public ContainerResult<T> recount() {
        synchronized (this.writeLock) {
            return ContainerResult.from(this.countFromStorage());
        }
    }

    /*
    Closes the storage and saves length, size and limits. Safe to call more
    than once. After close(), operations return SPFail.STORAGE_CLOSED.
    */
    @Override
    public void close() throws IOException {
        synchronized (this.writeLock) {
            if (this.closed) {
                return;
            }
            this.closed = true;

            IOException failure = null;
            try {
                this.fileService.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                this.saveMeta(true);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    // ---- size and limits ---------------------------------------------------------------

    /*
    Number of values stored.
    */
    public long length() {
        return this.length;
    }

    /*
    Total size of the stored values in bytes (serialized, without keys).
    */
    public long size() {
        return this.size;
    }

    /*
    Most values the container may hold; 0 = no limit.
    */
    public long getMaxLength() {
        return this.maxLength;
    }

    /*
    Sets the most values the container may hold (0 = no limit). Lowering it
    below length() keeps what is stored; only new values are refused.
    Saved with the container.
    */
    public void setMaxLength(long maxLength) throws IOException {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength cannot be negative");
        }
        synchronized (this.writeLock) {
            this.maxLength = maxLength;
            this.saveMetaIfOpen();
        }
    }

    /*
    Most bytes the stored values may take; 0 = no limit.
    */
    public long getMaxSize() {
        return this.maxSize;
    }

    /*
    Sets the most bytes the stored values may take (0 = no limit). Lowering it
    below size() keeps what is stored; only writes that would grow the
    container past it are refused. Saved with the container.
    */
    public void setMaxSize(long maxSize) throws IOException {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize cannot be negative");
        }
        synchronized (this.writeLock) {
            this.maxSize = maxSize;
            this.saveMetaIfOpen();
        }
    }

    /*
    Sets the most bytes in a unit, e.g. setMaxSize(10, ContainerLimitType.MEGABYTES).
    LENGTH sets maxLength instead; NO_LIMIT clears both limits.
    */
    public void setLimit(ContainerLimitType limitType, long limit) throws IOException {
        if (limitType == null) {
            throw new IllegalArgumentException("Limit type cannot be null");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("Limit cannot be negative");
        }
        synchronized (this.writeLock) {
            this.applyLimit(limitType, limit);
            this.saveMetaIfOpen();
        }
    }

    /*
    True if the container is at its maxLength or maxSize.
    */
    public boolean isFull() {
        return (this.maxLength > 0 && this.length >= this.maxLength)
            || (this.maxSize > 0 && this.size >= this.maxSize);
    }

    // ---- names -----------------------------------------------------------------------

    public String getContainerName() {
        return this.containerName;
    }

    /*
    Path of the container's storage: the Storage's directory + "/" + name, or
    just the name.
    */
    public String getContainerFullPath() {
        if (this.containerStorage != null) {
            return this.containerStorage.getStorageDirectory() + "/" + this.containerName;
        }
        return this.containerName;
    }

    public StorageProviderType getStorageProviderType() {
        return this.storageProviderType;
    }

    public Class<T> getValueType() {
        return this.valueType;
    }

    // ---- for subclasses --------------------------------------------------------------------

    /*
    Serializes a value: SPSuccess.WRITE with the bytes, or NULL_WRITE /
    VALUE_TYPE_MISMATCH / SERIALIZE_ERROR.
    */
    protected SPResult serialize(T value) {
        if (value == null) {
            return SPResult.of(SPFail.NULL_WRITE);
        }
        if (!this.valueType.isInstance(value)) {
            return SPResult.of(SPFail.VALUE_TYPE_MISMATCH);
        }
        try {
            return SPResult.of(SPSuccess.WRITE, Serializer.serialize(value));
        } catch (Exception e) {
            return SPResult.ofError(SPFail.SERIALIZE_ERROR, e);
        }
    }

    /*
    A read's storage result with its bytes turned into T values; a value that
    can't be turned back makes it a DESERIALIZE_ERROR.
    */
    protected ContainerResult<T> withValues(SPResult result) {
        if (!result.hasSucceeded()) {
            return ContainerResult.from(result);
        }
        try {
            if (result.getDataArray() != null) {
                java.util.List<T> values = new java.util.ArrayList<>(result.getDataArray().length);
                for (byte[] record : result.getDataArray()) {
                    values.add(this.deserialize(record));
                }
                return ContainerResult.fromAll(result, values);
            }
            return ContainerResult.from(result, result.getData() == null ? null : this.deserialize(result.getData()));
        } catch (IllegalStateException e) {
            return ContainerResult.failed(SPFail.DESERIALIZE_ERROR, e);
        }
    }

    /*
    Turns bytes back into a T. Throws IllegalStateException if they are not a
    serialized T.
    */
    protected T deserialize(byte[] bytes) {
        Object value;
        try {
            value = Serializer.deserialize(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Stored bytes are not a serialized " + this.valueType.getName(), e);
        }
        if (!this.valueType.isInstance(value)) {
            throw new IllegalStateException("Stored value is a " + (value == null ? "null" : value.getClass().getName())
                + ", not a " + this.valueType.getName());
        }
        return this.valueType.cast(value);
    }

    /*
    True if storing a value would take the container past a limit: newLength
    values and newSize bytes afterwards. Changes that don't grow the container
    are always allowed, even when it is already over a lowered limit.
    */
    protected boolean exceedsLimits(long newLength, long newSize) {
        boolean tooMany = this.maxLength > 0 && newLength > this.maxLength && newLength > this.length;
        boolean tooBig = this.maxSize > 0 && newSize > this.maxSize && newSize > this.size;
        return tooMany || tooBig;
    }

    /*
    Records the result of a successful write or delete. Call under writeLock.
    */
    protected void setCounts(long length, long size) {
        this.length = length;
        this.size = size;
    }

    /*
    Counts from storage. Keyless storage holds at most one value; KeyContainer
    reads every record.
    */
    protected SPResult countFromStorage() {
        SPResult all = this.fileService.read();
        if (all.getStatus() == SPFail.NULL_READ) {
            this.setCounts(0, 0);
            return SPResult.of(SPSuccess.READ);
        }
        if (!all.hasSucceeded()) {
            return all;
        }
        byte[][] records = all.getDataArray();
        if (records == null) {
            this.setCounts(1, all.getData().length);
        } else {
            long bytes = 0;
            for (byte[] record : records) {
                bytes += record.length;
            }
            this.setCounts(records.length, bytes);
        }
        return SPResult.of(SPSuccess.READ);
    }

    // ---- internals ----------------------------------------------------------------------

    private void applyLimit(ContainerLimitType limitType, long limit) {
        switch (limitType) {
            case NO_LIMIT:
                this.maxLength = 0;
                this.maxSize = 0;
                break;
            case LENGTH:
                this.maxLength = limit;
                break;
            default:
                this.maxSize = Math.multiplyExact(limit, (long) limitType.getValue());
                break;
        }
    }

    private void saveMetaIfOpen() throws IOException {
        if (!this.closed) {
            this.saveMeta(false);
        }
    }

    private void saveMeta(boolean closedCleanly) throws IOException {
        ContainerMeta meta = new ContainerMeta();
        meta.length = this.length;
        meta.size = this.size;
        meta.maxLength = this.maxLength;
        meta.maxSize = this.maxSize;
        meta.closedCleanly = closedCleanly;
        meta.save(this.metaFile);
    }
}
