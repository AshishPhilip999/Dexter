package ContainerCore.Container;

import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerObject;
import StorageCore.Storage;
import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;
import StorageStatus.Container.ContainerSuccessStatus.ContainerWriteSuccess;

import java.io.Serializable;

import Utility.IO.FileType;

public class KeyContainer<K extends Serializable, T extends Serializable> extends Container<T> {
    private final KeyContainerValidater<K, T> containerValidater;

    public KeyContainer(String containerName, Class<T> valueObjectInstance) {
        super(containerName, valueObjectInstance);
        this.containerValidater = new KeyContainerValidater<>(containerName, valueObjectInstance, containerStorage, this.getContainerFullPath(), fileType);
    }

    public KeyContainer(String containerName, Class<T> valueObjectInstance, FileType fileType) {
        super(containerName, valueObjectInstance, fileType);
        this.containerValidater = new KeyContainerValidater<>(containerName, valueObjectInstance, containerStorage, this.getContainerFullPath(), fileType);
    }

    public KeyContainer(String containerName, Class<T> valueObjectInstance, ContainerLimitType containerLimitType, long containerLimit) {
        super(containerName, valueObjectInstance, containerLimitType, containerLimit);
        this.containerValidater = new KeyContainerValidater<>(containerName, valueObjectInstance, containerStorage, this.getContainerFullPath(), fileType);
    }

    public KeyContainer(String containerName, Class<T> valueObjectInstance, FileType fileType, ContainerLimitType containerLimitType,
            long containerLimit) {
        super(containerName, valueObjectInstance, fileType, containerLimitType, containerLimit);
        this.containerValidater = new KeyContainerValidater<>(containerName, valueObjectInstance, containerStorage, this.getContainerFullPath(), fileType);
    }

    public KeyContainer(Storage containerStorage, String containerName, Class<T> valueObjectInstance, FileType fileType,
            ContainerLimitType containerLimitType, long containerLimit) {
        super(containerStorage, containerName, valueObjectInstance, fileType, containerLimitType, containerLimit);
        this.containerValidater = new KeyContainerValidater<>(containerName, valueObjectInstance, containerStorage, this.getContainerFullPath(), fileType);
    }

    public synchronized ContainerObject<T> write(K key,T data) {
        ContainerObject<T> contObj = this.containerValidater.write(key, data, containerLength, containerLimitType, containerLimit, containerSize, containerLength);
        ContainerStatus status = contObj.status;
        if (status.getSPStatus() == SPStatus.SUCCESS) {
            ContainerWriteSuccess success = (ContainerWriteSuccess) status;
            this.increamentContainerSize(success.getByteData().length);
        }
        return contObj;
    }

    public ContainerObject<T> read(K key) {
        return this.containerValidater.read(key);
    }
}