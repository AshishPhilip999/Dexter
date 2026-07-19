package ContainerCore.Utility;

import java.io.Serializable;

import StorageStatus.Container.ContainerStatus;

public class ContainerObject<T extends Serializable> {
    public ContainerStatus status;
    public T object;
}
