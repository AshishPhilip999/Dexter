// package ContainerTests;

// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;

// import ContainerCore.Container.Container;
// import ContainerCore.ResponseHandlers.ContainerWriteStatus;
// import ContainerCore.ResponseHandlers.StorageWriteResponse;
// import StorageStatus.SPStatus;

// import static org.junit.jupiter.api.Assertions.*;

// public class ReadWriteTests {
//     @BeforeEach
//     public void cleanupDatFiles() {
//         TestUtils.clearDatFiles();
//     }

//     @AfterEach
//     public void cleanupAfterTest() {
//         TestUtils.clearDatFiles();
//     }

//     @Test
//     public void singleContainerReadWriteTest() throws Exception {
//         Container<String, String> container = new Container<>("TestData/singleContainer");

//         String payload = "hello-storage-provider";
//         StorageWriteResponse response = container.write(payload);

//         assertNotNull(response, "Write response should not be null");
//         assertEquals(SPStatus.SUCCESS, response.status, "Container write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, response.writeStatus, "Write status should indicate success");

//         String actual = container.read();
//         assertEquals(payload, actual, "Read value should match the written payload");
//         container.close();
//     }

//     @Test
//     public void multipleContainerWriteTest() throws Exception {
//         Container<String, String> container = new Container<>("TestData/multiWriteContainer");

//         String firstPayload = "first-write";
//         String secondPayload = "second-write";
//         String thirdPayload = "third-write";

//         StorageWriteResponse firstResponse = container.write(firstPayload);
//         StorageWriteResponse secondResponse = container.write(secondPayload);
//         StorageWriteResponse thirdResponse = container.write(thirdPayload);

//         assertEquals(SPStatus.SUCCESS, firstResponse.status, "First write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, firstResponse.writeStatus, "First write should indicate success");

//         assertEquals(SPStatus.SUCCESS, secondResponse.status, "Second write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, secondResponse.writeStatus, "Second write should indicate success");

//         assertEquals(SPStatus.SUCCESS, thirdResponse.status, "Third write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, thirdResponse.writeStatus, "Third write should indicate success");

//         String actual = container.read();
//         assertEquals(thirdPayload, actual, "Read should return the latest written payload");
//         container.close();
//     }

//     @Test
//     public void singleKeyedWriteReadTest() throws Exception {
//         Container<String, String> container = new Container<>("TestData/keyedContainer");

//         String payload = "keyed-write";
//         String key = "key-1";

//         StorageWriteResponse response = container.write(payload, key);

//         assertNotNull(response, "Write response should not be null");
//         assertEquals(SPStatus.SUCCESS, response.status, "Keyed write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, response.writeStatus, "Keyed write should indicate success");

//         String actual = container.read(key);
//         assertEquals(payload, actual, "Read by key should return the written payload");
//         container.close();
//     }

//     @Test
//     public void multipleKeyedWritesReadByKeyTest() throws Exception {
//         Container<String, String> container = new Container<>("TestData/keyedContainerMultiple");

//         String firstPayload = "first-keyed-write";
//         String secondPayload = "second-keyed-write";
//         String thirdPayload = "third-keyed-write";

//         String firstKey = "key-1";
//         String secondKey = "key-2";
//         String thirdKey = "key-3";

//         StorageWriteResponse firstResponse = container.write(firstPayload, firstKey);
//         StorageWriteResponse secondResponse = container.write(secondPayload, secondKey);
//         StorageWriteResponse thirdResponse = container.write(thirdPayload, thirdKey);

//         assertEquals(SPStatus.SUCCESS, firstResponse.status, "First keyed write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, firstResponse.writeStatus, "First keyed write should indicate success");

//         assertEquals(SPStatus.SUCCESS, secondResponse.status, "Second keyed write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, secondResponse.writeStatus, "Second keyed write should indicate success");

//         assertEquals(SPStatus.SUCCESS, thirdResponse.status, "Third keyed write should succeed");
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, thirdResponse.writeStatus, "Third keyed write should indicate success");

//         assertEquals(firstPayload, container.read(firstKey), "Read by first key should return first payload");
//         assertEquals(secondPayload, container.read(secondKey), "Read by second key should return second payload");
//         assertEquals(thirdPayload, container.read(thirdKey), "Read by third key should return third payload");
//         container.close();
//     }

//     @Test
//     public void integerKeyedSingleAndMultipleTest() throws Exception {
//         Container<Integer, TestableObject> container = new Container<>("TestData/intKeyContainer");

//         TestableObject o1 = new TestableObject(1, "one", true);
//         Integer k1 = 1;

//         StorageWriteResponse r1 = container.write(o1, k1);
//         assertNotNull(r1);
//         assertEquals(SPStatus.SUCCESS, r1.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, r1.writeStatus);

//         TestableObject read1 = container.read(k1);
//         assertEquals(o1, read1);

//         // multiple keyed writes
//         TestableObject o2 = new TestableObject(2, "two", false);
//         TestableObject o3 = new TestableObject(3, "three", true);
//         Integer k2 = 2;
//         Integer k3 = 3;

//         StorageWriteResponse r2 = container.write(o2, k2);
//         StorageWriteResponse r3 = container.write(o3, k3);

//         assertEquals(o2, container.read(k2));
//         assertEquals(o3, container.read(k3));
//         container.close();
//     }

//     @Test
//     public void booleanKeyedSingleAndMultipleTest() throws Exception {
//         Container<Boolean, TestableObject> container = new Container<>("TestData/boolKeyContainer");

//         TestableObject o1 = new TestableObject(10, "t", true);
//         Boolean k1 = Boolean.TRUE;

//         StorageWriteResponse r1 = container.write(o1, k1);
//         assertNotNull(r1);
//         assertEquals(SPStatus.SUCCESS, r1.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, r1.writeStatus);

//         TestableObject read1 = container.read(k1);
//         assertEquals(o1, read1);

//         // multiple keyed writes (only two distinct boolean keys)
//         TestableObject o2 = new TestableObject(11, "f", false);
//         Boolean k2 = Boolean.FALSE;

//         StorageWriteResponse r2 = container.write(o2, k2);
//         assertEquals(o1, container.read(k1));
//         assertEquals(o2, container.read(k2));
//         container.close();
//     }

//     @Test
//     public void floatKeyedSingleAndMultipleTest() throws Exception {
//         Container<Float, TestableObject> container = new Container<>("TestData/floatKeyContainer");

//         TestableObject o1 = new TestableObject(21, "f1", false);
//         Float k1 = 1.1f;

//         StorageWriteResponse r1 = container.write(o1, k1);
//         assertNotNull(r1);
//         assertEquals(SPStatus.SUCCESS, r1.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, r1.writeStatus);

//         TestableObject read1 = container.read(k1);
//         assertEquals(o1, read1);

//         TestableObject o2 = new TestableObject(22, "f2", true);
//         TestableObject o3 = new TestableObject(23, "f3", false);
//         Float k2 = 2.2f;
//         Float k3 = 3.3f;

//         container.write(o2, k2);
//         container.write(o3, k3);

//         assertEquals(o2, container.read(k2));
//         assertEquals(o3, container.read(k3));
//         container.close();
//     }

//     @Test
//     public void doubleKeyedSingleAndMultipleTest() throws Exception {
//         Container<Double, TestableObject> container = new Container<>("TestData/doubleKeyContainer");

//         TestableObject o1 = new TestableObject(31, "d1", true);
//         Double k1 = 1.11;

//         StorageWriteResponse r1 = container.write(o1, k1);
//         assertNotNull(r1);
//         assertEquals(SPStatus.SUCCESS, r1.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, r1.writeStatus);

//         TestableObject read1 = container.read(k1);
//         assertEquals(o1, read1);

//         TestableObject o2 = new TestableObject(32, "d2", false);
//         TestableObject o3 = new TestableObject(33, "d3", true);
//         Double k2 = 2.22;
//         Double k3 = 3.33;

//         container.write(o2, k2);
//         container.write(o3, k3);

//         assertEquals(o2, container.read(k2));
//         assertEquals(o3, container.read(k3));
//         container.close();
//     }
// }
