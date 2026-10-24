package StorageStatus.SP;

/*
Successful outcomes. The data of an operation is carried by its SPResult, not
by these constants.
*/
public enum SPSuccess implements SPStatus {
    READ,
    WRITE,
    DELETE,
    COMPACT,
    SYNC,
    CHECKPOINT
    ;
}
