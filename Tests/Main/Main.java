package Main;
import java.io.Serializable;

import ContainerCore.Container.Container;
import ContainerCore.Container.KeyContainer;
import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerObject;
import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;
import StorageStatus.Container.ContainerFailStatus.ContainerReadFail;
import StorageStatus.Container.ContainerSuccessStatus.ContainerReadSuccess;
import Utility.IO.FileType;
import junit.framework.Test;

public class Main {
    public static void main(String[] args) {
        String path = "/Users/ashish-21172/Desktop/";
        String containerName = path + "container1";
        Container<Integer> container1 = new Container<>(containerName, Integer.class, FileType.DAT);

        // ContainerObject<TestObject> writeObj = container1.write(new TestObject("Hello World"));

        // System.out.println(writeObj.status); 

        ContainerObject<Integer> contObj = container1.read();
        if(contObj.status.getSPStatus() == SPStatus.SUCCESS) {
            ContainerReadSuccess success = (ContainerReadSuccess)contObj.status;
            System.out.println(success);
        } else if (contObj.status.getSPStatus() == SPStatus.FAIL) {
            ContainerReadFail fail = (ContainerReadFail) contObj.status;
            System.out.println(fail);
        }

        // KeyContainer<Integer, TestObject> keyContainer = new KeyContainer<>(containerName, TestObject.class, FileType.DAT);
        // Container<TestObject> keyContainer2 = keyContainer;
        // keyContainer.write(123, new TestObject("Hello world with key"));
        // keyContainer.write(124, new TestObject("Hello 2"));
    }
}

class TestObject implements Serializable {
    String value;

    public TestObject(String value) {
        this.value = value;
    }
}