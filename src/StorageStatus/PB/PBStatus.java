package StorageStatus.PB;

import StorageStatus.StorageStatus;

/*
Result of every PBStorageProvider / PBKeyStorageProvider read, write and delete.
getStatus() is SUCCESS (PBSuccess) or FAIL (PBFail).
*/
public interface PBStatus {
    OperationStatus getStatus();
 }
