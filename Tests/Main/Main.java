package Main;

import java.io.Serializable;

import StorageStatus.PB.PBResult;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

public class Main {
    public static void main(String[] args) {
        String path = "/Users/ashish-21172/Desktop/";
        String containerName = path + "PBStorage";

        try {
            PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(containerName, Integer.class);
            // TestObject tO1 = new TestObject("TO 1");
            // byte[] data1 = Serializer.serialize(tO1);

            // sp.write(1, data1);
            PBResult result = sp.read(1);

            if (result.hasSucceeded()) {
                byte[] data = result.getData();
                TestObject obj = Serializer.deserialize(data);

                System.out.println(obj.value);
            } else {
                System.out.println(result.getError());
            }

            sp.close();

            PBKeyStorageProvider<Integer> sp2 = new PBKeyStorageProvider<>(containerName, Integer.class);

            PBResult result2 = sp2.read(1);

            if (result2.hasSucceeded()) {
                byte[] data = result2.getData();
                TestObject obj = Serializer.deserialize(data);

                System.out.println(obj.value);
            } else {
                System.out.println(result2.getError());
            }

            sp2.close();

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