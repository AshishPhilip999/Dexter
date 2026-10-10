package StorageStatus.PB;

public enum PBFail implements PBStatus {
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
    COMPACT_ERROR
    ;
}
