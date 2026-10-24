package StorageStatus.SP;

public enum SPFail implements SPStatus {
    /*
    read() on a storage with nothing stored.
    */
    NULL_READ,

    /*
    write called with null or empty data.
    */
    NULL_WRITE,

    /*
    Keyed read / write called with a null key.
    */
    NULL_KEY,

    /*
    Key is not of this storage's key type (e.g. from a raw-typed caller).
    */
    KEY_TYPE_MISMATCH,

    /*
    read(key) for a key that isn't stored.
    */
    KEY_NOT_FOUND,

    /*
    Key could not be encoded (e.g. a custom key class that fails to serialize).
    */
    KEY_ENCODE_ERROR,

    /*
    Encoded key is longer than the index allows (BPlusTree.MAX_KEY_SIZE, 1024 bytes).
    */
    KEY_TOO_LARGE,

    /*
    Key bytes + data don't fit into one page.
    */
    DATA_TOO_LARGE,

    /*
    I/O error while reading the file.
    */
    READ_ERROR,

    /*
    I/O error while writing the file.
    */
    WRITE_ERROR,

    /*
    I/O error while deleting (marking a slot deleted or erasing the file).
    */
    DELETE_ERROR,

    /*
    I/O error while compacting. If it happened before the packed file was put
    in place, the storage is unchanged; after that, the data is complete and a
    keyed storage's index is rebuilt on the next open.
    */
    COMPACT_ERROR,

    /*
    The storage was closed with close(); open a new instance to use it again.
    */
    STORAGE_CLOSED,

    /*
    I/O error while flushing the journal in sync(). Operations committed before
    are still in the journal; they just may not be on the disk yet.
    */
    SYNC_ERROR,

    /*
    I/O error while copying the journal into the files in checkpoint(). Nothing
    is lost: the journal keeps every change and the next checkpoint (or the
    next open) applies it again.
    */
    CHECKPOINT_ERROR,

    /*
    Container: the write would take the container past its maxLength or
    maxSize. Nothing was written.
    */
    LIMIT_EXCEEDED,

    /*
    Container: the value could not be serialized (e.g. it has a field that
    isn't Serializable). getError() has the cause.
    */
    SERIALIZE_ERROR,

    /*
    Container: the value isn't of the container's value type (only possible
    from raw-typed or unchecked code).
    */
    VALUE_TYPE_MISMATCH,

    /*
    Container: stored bytes could not be turned back into the container's
    value type (not a serialized object, a class that changed, or a value of
    another type). getError() has the cause.
    */
    DESERIALIZE_ERROR
    ;
}
