package StorageStatus.Container.ContainerFailStatus;

import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;

public enum ContainerWriteFail implements ContainerStatus {
    CANNOT_SERIALIZE,
    DUPLICATE_KEY_FOUND,
    KEY_NOT_FOUND,
    LIMIT_EXCEEDED,
    NULL_DATA_PROVIDED,
    NULL_KEY_PROVIDED,
    WRITE_ERROR,

    FILE_NOT_FOUND
    ;

    @Override
    public SPStatus getSPStatus() {
        return SPStatus.FAIL;
    }
    
}
