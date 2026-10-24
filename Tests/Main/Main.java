package Main;

import java.io.Serializable;
import java.util.List;

import ContainerCore.Container.ContainerResult;
import ContainerCore.Container.KeyContainer;
import ContainerCore.Utility.ContainerLimitType;

public class Main {
    public static void main(String[] args) {
        String path = "/Users/ashish-21172/Desktop/";
        String containerName = path + "PBStorage2";

        try {
            KeyContainer<Integer, TestObject> container = new KeyContainer<>(containerName, Integer.class, TestObject.class, ContainerLimitType.BYTES, 156);
            TestObject[] objs = { new TestObject("Amidetho Gozaimas"), new TestObject("Hola") };

            container.size();

            // int key = 10;
            // for(TestObject obj : objs) {
            //     ContainerResult<TestObject> result = container.write(key++, obj);
            //     if(result.hasSucceeded()) {
            //         System.out.println(result.getStatus());
            //     } else {
            //         System.out.println(result.getStatus());
            //     }
            // }

            // ContainerResult<TestObject> result = container.delete();
            // if (result.hasSucceeded()) {
            //     System.out.println(result.getStatus());
            // } else {
            //     System.out.println(result.getStatus());
            // }

            ContainerResult<TestObject> results = container.read();
            if (results.hasSucceeded()) {
                List<TestObject> list = results.getValues();

                for(TestObject obj : list) {
                    System.out.println(obj.value);
                    // for(byte[] data : results.getDataArray()) {
                    //     System.out.println(data.length);
                    // }
                }
            } else {
                System.out.println(results.getStatus());
            }

            container.close();

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