package StorageStatus.Writer.FailStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IOReadFail implements IOStatus {
    KEY_NOT_FOUND,
    FILE_NOT_FOUND,
    CANNOT_READ
    ;
    @Override
    public SPStatus getSPStatus() {
        return SPStatus.FAIL;
    }
    
}
