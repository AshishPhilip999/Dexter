package StorageStatus.Writer.FailStatus;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;

public enum IOWriteFail implements IOStatus {
    DUPLICATE_KEY_FOUND,
    KEY_NOT_FOUND,
    FILE_NOT_FOUND,
    NULL_DATA_PROVIDED,
    CANNOT_WRITE,
    ;

    @Override
    public SPStatus getSPStatus() {
        return SPStatus.FAIL;
    }
}
