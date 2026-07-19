package StorageStatus.Container.ContainerSuccessStatus;

import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;

public enum ContainerReadSuccess implements ContainerStatus {
    FINISH_READ
    ;

    @Override
    public SPStatus getSPStatus() {
       return SPStatus.SUCCESS;
    }
    
}
