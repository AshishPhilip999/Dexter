package ContainerCore.Container;

import java.io.Serializable;

import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerObject;
import StorageCore.Storage;
import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;
import StorageStatus.Container.ContainerSuccessStatus.ContainerWriteSuccess;
import Utility.StorageProviders.FileType;

public class Container<T extends Serializable> {
    protected final FileType fileType;
    protected final ContainerLimitType containerLimitType;
    protected long containerLimit = 0;

    // containerCount tracks the number of data entries
    // in the container.
    protected int containerLength = 0;

    // containerSize tracks the total size(Bytes) of the data
    // in the container.
    protected long containerSize = 0;

    protected Storage containerStorage;

    protected String containerName;

    private ContainerValidator<T> containerValidator;

    public Container(String containerName, Class<T> valueObjectInstance) {
        this.containerName = containerName;
        this.containerLimitType = ContainerLimitType.NO_LIMIT;
        this.fileType = FileType.DAT;
        this.containerValidator = new ContainerValidator<>(containerName, valueObjectInstance, containerStorage,
                this.getContainerFullPath(), fileType, false);
    }

    public Container(String containerName, Class<T> valueObjectInstance, FileType fileType) {
        this.containerName = containerName;
        this.containerLimitType = ContainerLimitType.NO_LIMIT;
        this.fileType = fileType;
        this.containerValidator = new ContainerValidator<>(containerName, valueObjectInstance, containerStorage,
                this.getContainerFullPath(), fileType, false);
    }

    public Container(String containerName, Class<T> valueObjectInstance, ContainerLimitType containerLimitType,
            long containerLimit) {
        this.containerName = containerName;
        this.containerLimitType = containerLimitType;
        this.containerLimit = containerLimit;
        this.fileType = FileType.DAT;
        this.containerValidator = new ContainerValidator<>(containerName, valueObjectInstance, containerStorage,
                this.getContainerFullPath(), fileType, false);
    }

    public Container(String containerName, Class<T> valueObjectInstance, FileType fileType,
            ContainerLimitType containerLimitType,
            long containerLimit) {
        this.containerName = containerName;
        this.containerLimitType = containerLimitType;
        this.containerLimit = containerLimit;
        this.fileType = fileType;
        this.containerValidator = new ContainerValidator<>(containerName, valueObjectInstance, containerStorage,
                this.getContainerFullPath(), fileType, false);
    }

    public Container(Storage containerStorage, String containerName, Class<T> valueObjectInstance, FileType fileType,
            ContainerLimitType containerLimitType, long containerLimit) {
        this.containerStorage = containerStorage;
        this.containerName = containerName;
        this.containerLimitType = containerLimitType;
        this.containerLimit = containerLimit;
        this.fileType = fileType;
        this.containerValidator = new ContainerValidator<>(containerName, valueObjectInstance, containerStorage,
                this.getContainerFullPath(), fileType,false);
    }

    public long getContainerSize() {
        return containerSize;
    }

    public long getContainerLimit() {
        return containerLimit;
    }

    public int getContainerLength() {
        return this.containerLength;
    }

    public String getContainerFullPath() {
        if (this.containerStorage != null) {
            return containerStorage.getStorageDirectory() + "/" + containerName;
        }

        return this.containerName;
    }

    public String getContainerName() {
        return containerName;
    }

    public boolean isContainerFull(long fileSize) {
        return this.containerValidator.isContainerFull(fileSize, containerLimitType, fileSize, fileSize, containerLength);
    }

    public SPStatus close() {
        return this.containerValidator.close();
    }

    public synchronized ContainerObject<T> write(T data) {
        ContainerObject<T> contObj = this.containerValidator.write(data, containerLength, containerLimitType, containerLimit, containerSize, containerLength);
        ContainerStatus status = contObj.status;
        if (status.getSPStatus() == SPStatus.SUCCESS) {
            ContainerWriteSuccess success = (ContainerWriteSuccess) status;
            this.increamentContainerSize(success.getByteData().length);
        }
        return contObj;
    }

    public ContainerObject<T> read() {
        return this.containerValidator.read();
    }

    protected void increamentContainerSize(long fileSize) {
        this.containerLength += 1;
        this.containerSize += fileSize;
    }
}
