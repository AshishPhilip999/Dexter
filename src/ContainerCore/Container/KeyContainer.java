package ContainerCore.Container;

import java.io.Serializable;

import ContainerCore.Utility.ContainerLimitType;
import StorageCore.Storage;
import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import Utility.StorageProviders.DexterKeyFileService;
import Utility.StorageProviders.StorageProviderType;

/*
A container of many values of type T, each under a key of type K, like a
persistent Map<K, T>. Works on any keyed storage (DexterKeyFileService),
page-based by default.

    try (KeyContainer<Integer, User> users = new KeyContainer<>("data/users", Integer.class, User.class)) {
        users.write(1, new User("Ashish"));
        User one = users.read(1).getValue();
        List<User> everyone = users.read().getValues();
    }

length() is the number of keys, size() the total bytes of their values. The
keyless write(value) / delete() inherited from Container replace / erase
everything, as on the storage.
*/
public class KeyContainer<K extends Serializable, T extends Serializable> extends Container<T> {
    private final DexterKeyFileService<K> keyFileService;
    private final Class<K> keyType;

    public KeyContainer(String containerName, Class<K> keyType, Class<T> valueType) throws Exception {
        this(null, containerName, keyType, valueType, StorageProviderType.PAGE_BASED, null, 0);
    }

    public KeyContainer(String containerName, Class<K> keyType, Class<T> valueType,
            StorageProviderType storageProviderType) throws Exception {
        this(null, containerName, keyType, valueType, storageProviderType, null, 0);
    }

    /*
    With a limit; see Container for how ContainerLimitType maps to maxLength /
    maxSize.
    */
    public KeyContainer(String containerName, Class<K> keyType, Class<T> valueType,
            ContainerLimitType containerLimitType, long containerLimit) throws Exception {
        this(null, containerName, keyType, valueType, StorageProviderType.PAGE_BASED, containerLimitType, containerLimit);
    }

    public KeyContainer(String containerName, Class<K> keyType, Class<T> valueType,
            StorageProviderType storageProviderType, ContainerLimitType containerLimitType, long containerLimit)
            throws Exception {
        this(null, containerName, keyType, valueType, storageProviderType, containerLimitType, containerLimit);
    }

    public KeyContainer(Storage containerStorage, String containerName, Class<K> keyType, Class<T> valueType,
            StorageProviderType storageProviderType, ContainerLimitType containerLimitType, long containerLimit)
            throws Exception {
        super(containerStorage, containerName, valueType, storageProviderType, containerLimitType, containerLimit,
            path -> openKeyed(storageProviderType, path, keyType));
        this.keyType = keyType;
        @SuppressWarnings("unchecked")
        DexterKeyFileService<K> keyed = (DexterKeyFileService<K>) this.fileService;
        this.keyFileService = keyed;
    }

    private static <K extends Serializable> DexterKeyFileService<K> openKeyed(StorageProviderType type, String path,
            Class<K> keyType) throws Exception {
        if (keyType == null) {
            throw new IllegalArgumentException("Key type cannot be null");
        }
        return type.openKeyed(path, keyType);
    }

    // ---- operations ------------------------------------------------------------------

    /*
    Stores value under key, replacing any value it had.
    SUCCESS: SPSuccess.WRITE, getValue() is the value written
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, NULL_WRITE, VALUE_TYPE_MISMATCH,
             SERIALIZE_ERROR, LIMIT_EXCEEDED, STORAGE_CLOSED, and the
             storage's KEY_TOO_LARGE / DATA_TOO_LARGE / WRITE_ERROR
    */
    public ContainerResult<T> write(K key, T value) {
        SPResult serialized = this.serialize(value);
        if (!serialized.hasSucceeded()) {
            return ContainerResult.from(serialized);
        }
        byte[] bytes = serialized.getData();

        synchronized (this.writeLock) {
            SPResult current = this.keyFileService.read(key);
            long oldBytes;
            if (current.hasSucceeded()) {
                oldBytes = current.getData().length;
            } else if (current.getStatus() == SPFail.KEY_NOT_FOUND) {
                oldBytes = -1;
            } else {
                return ContainerResult.from(asWriteFailure(current));
            }

            long newLength = this.length() + (oldBytes < 0 ? 1 : 0);
            long newSize = this.size() - Math.max(oldBytes, 0) + bytes.length;
            if (this.exceedsLimits(newLength, newSize)) {
                return ContainerResult.failed(SPFail.LIMIT_EXCEEDED);
            }

            SPResult result = this.keyFileService.write(key, bytes);
            if (result.hasSucceeded()) {
                this.setCounts(newLength, newSize);
                return ContainerResult.from(result, value);
            }
            return ContainerResult.from(result);
        }
    }

    /*
    The value stored under key (getValue()).
    SUCCESS: SPSuccess.READ
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, READ_ERROR,
             DESERIALIZE_ERROR, STORAGE_CLOSED
    */
    public ContainerResult<T> read(K key) {
        return this.withValues(this.keyFileService.read(key));
    }

    /*
    Removes key and its value; getValue() is the value that was removed.
    SUCCESS: SPSuccess.DELETE
    FAIL:    NULL_KEY, KEY_TYPE_MISMATCH, KEY_NOT_FOUND, DELETE_ERROR, STORAGE_CLOSED
    */
    public ContainerResult<T> delete(K key) {
        synchronized (this.writeLock) {
            SPResult current = this.keyFileService.read(key);
            if (!current.hasSucceeded()) {
                return ContainerResult.from(current.getStatus() == SPFail.KEY_NOT_FOUND || current.getError() == null
                    ? current
                    : SPResult.ofError(SPFail.DELETE_ERROR, current.getError()));
            }

            SPResult result = this.keyFileService.delete(key);
            if (!result.hasSucceeded()) {
                return ContainerResult.from(result);
            }
            this.setCounts(this.length() - 1, this.size() - current.getData().length);
            T removed;
            try {
                removed = this.deserialize(current.getData());
            } catch (IllegalStateException e) {
                removed = null;   // deleted all the same; it just isn't a T any more
            }
            return ContainerResult.from(result, removed);
        }
    }

    /*
    True if a value is stored under key (false for null, the wrong key type,
    a read error, or after close()).
    */
    public boolean containsKey(K key) {
        return this.keyFileService.read(key).hasSucceeded();
    }

    public Class<K> getKeyType() {
        return this.keyType;
    }

    // ---- internals ----------------------------------------------------------------------

    /*
    A read that failed while preparing a write: errors become WRITE_ERROR (with
    the cause); NULL_KEY, KEY_TYPE_MISMATCH, STORAGE_CLOSED are reported as they are.
    */
    private static SPResult asWriteFailure(SPResult readResult) {
        if (readResult.getError() != null) {
            return SPResult.ofError(SPFail.WRITE_ERROR, readResult.getError());
        }
        return readResult;
    }
}
