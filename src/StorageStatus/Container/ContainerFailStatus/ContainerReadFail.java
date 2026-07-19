package StorageStatus.Container.ContainerFailStatus;

import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;

public enum ContainerReadFail implements ContainerStatus {
     KEY_NOT_FOUND,
     SERIALIZE_ERROR,
     READ_ERROR,

     FILE_NOT_FOUND
    ;

    @Override
    public SPStatus getSPStatus() {
        return SPStatus.FAIL;
    }
    
}
