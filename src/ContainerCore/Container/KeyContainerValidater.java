package ContainerCore.Container;

import java.io.Serializable;

import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerObject;
import StorageCore.Storage;
import StorageStatus.Container.ContainerFailStatus.ContainerWriteFail;
import StorageStatus.Writer.IOStatus;
import Utility.StorageProviders.FileType;

class KeyContainerValidater<K extends Serializable, T extends Serializable> extends ContainerValidator<T> {
    private K key;

    public KeyContainerValidater(String containerName, Class<T> valueObjectInstance, Storage containerStorage, String fullPath,
            FileType fileType) {
        super(containerName, valueObjectInstance, containerStorage, fullPath, fileType, true);
        this.configureFileWriter(containerName, containerStorage, fullPath, fileType);
    }

    public synchronized ContainerObject<T> write(K key, T data, long fileSize, ContainerLimitType containerLimitType,
            long containerLimit,
            long containerSize, int containerLength) {
        if (key == null) {
            ContainerObject<T> obj = new ContainerObject<>();
            obj.status = ContainerWriteFail.NULL_KEY_PROVIDED;
            return obj;
        }

        ContainerObject<T> containerObject = new ContainerObject<>();
        this.key = key;
        containerObject.status = this.writeInContainer(data, fileSize, containerLimitType, containerLimit,
                containerSize,
                containerLength);
        containerObject.object = data;

        return containerObject;
    }

    public ContainerObject<T> read(K key) {
        if (key == null) {
            ContainerObject<T> obj = new ContainerObject<>();
            obj.status = ContainerWriteFail.NULL_KEY_PROVIDED;
            return obj;
        }

        this.key = key;
        return this.readInContainer();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected IOStatus writeInFile(byte[] currentData) {
        return null; //this.fileService.write(currentData, this.key);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected IOStatus readInFile() {
        return null; //this.fileService.read(this.key);
    }

    @Override
    protected void configureFileWriter(String containerName, Storage containerStorage, String fullPath,
            FileType fileType) {
        String filePath;
        if (containerStorage != null) {
            filePath = fullPath;
        } else {
            filePath = containerName;
        }

        switch (fileType) {
            case BINARY:
                // fileWriter = new BinaryWriter(filePath);
                break;
            case DAT:
                try {
                    // this.fileService = new DatIO<K>(filePath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case JSON:
                // fileWriter = new JSONWriter(filePath);
                break;
            case ANY:
                // fileWriter = new AnyWriter(filePath);
                break;
        }
    }
}
