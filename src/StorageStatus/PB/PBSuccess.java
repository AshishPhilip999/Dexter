package StorageStatus.PB;

/*
Successful outcomes. The data of an operation is carried by its PBResult, not
by these constants.
*/
public enum PBSuccess implements PBStatus {
    READ,
    WRITE,
    DELETE,
    COMPACT,
    SYNC,
    CHECKPOINT
    ;
}
