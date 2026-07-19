package StorageStatus.Writer.SuccessStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IOWriteSuccess implements IOStatus {
    FILE_FOUND,
    FINISH_WRITE
    ;

    @Override
    public SPStatus getSPStatus() {
        return SPStatus.SUCCESS;
    }
    
}
