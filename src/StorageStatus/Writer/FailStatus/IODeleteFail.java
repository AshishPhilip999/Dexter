package StorageStatus.Writer.FailStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IODeleteFail implements IOStatus {
    FILE_NOT_FOUND,
    CANNOT_DELETE
    ;

    @Override
    public SPStatus getSPStatus() {
       return SPStatus.FAIL;
    }
}
