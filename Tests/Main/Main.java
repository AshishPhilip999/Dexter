package Main;

import java.io.Serializable;

import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

public class Main {
    public static void main(String[] args) {
        String path = "/Users/ashish-21172/Desktop/";
        String containerName = path + "PBStorage";

        try {
            PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(containerName, Integer.class);
            TestObject tO1 = new TestObject("TO 1");
            byte[] data1 = Serializer.serialize(tO1);

            sp.write(data1);

        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

class TestObject implements Serializable {
    String value;

    public TestObject(String value) {
        this.value = value;
    }
}