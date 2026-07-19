package StorageCore;

import java.util.ArrayList;
import java.util.List;

import ContainerCore.Container.KeyContainer;
import ContainerCore.Utility.ContainerLimitType;
import Utility.IO.FileType;

public class Storage {
    private final String storageDirectory;
    private final String storageName;

    private List<KeyContainer> containers;

    private List<Storage> storages;

    public Storage(String storageDirectory, String storageName) {
        this.storageDirectory = storageDirectory;
        this.storageName = storageName;

        this.containers = new ArrayList<>();
        this.storages = new ArrayList<>();
    }

    public String getStorageDirectory() {
        return storageDirectory;
    }

    public String getStorageName() {
        return storageName;
    }

    public void addContainer(KeyContainer container) {
        this.containers.add(container);
    }

    // public void createNewContainer(String containerName, ContainerLimitType containerLimitType, FileType fileType) {
    //     Container newContainer = new Container(this, containerName, containerLimitType, fileType);
    //     this.addContainer(newContainer);
    // }

    public void addStorage(Storage storage) {
        this.storages.add(storage);
    }

    public void createNewStorage(String storageDirectory, String storageName) {
        Storage newStorage = new Storage(storageDirectory, storageName);
        this.addStorage(newStorage);
    }
}
