package StorageStatus.Container.ContainerSuccessStatus;

import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;

public enum ContainerWriteSuccess implements ContainerStatus {
    FINISH_WRITE
    ;

    private byte[] data;

    public byte[] getByteData() {
        return this.data;
    }

    public void setByteData(byte[] data) {
        this.data = data;
    }

    @Override
    public SPStatus getSPStatus() {
       return SPStatus.SUCCESS;
    }
    
}
