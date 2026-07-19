// package ContainerTests;

// import Utility.Serializer;
// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;

// import ContainerCore.ContainerLimitType;
// import ContainerCore.Container.Container;
// import ContainerCore.ResponseHandlers.ContainerWriteStatus;
// import ContainerCore.ResponseHandlers.StorageWriteResponse;
// import StorageStatus.SPStatus;

// import static org.junit.jupiter.api.Assertions.*;

// public class ContainerLimitTest {

//     @BeforeEach
//     public void setup() {
//         TestUtils.clearDatFiles();
//     }

//     @AfterEach
//     public void teardown() {
//         TestUtils.clearDatFiles();
//     }

//     @Test
//     public void lengthLimitAllowsExactCountAndRejectsExtra() throws Exception {
//         Container<String, String> container = new Container<>("Tests/TestData/lengthLimitContainer", ContainerLimitType.LENGTH, 2);

//         StorageWriteResponse first = container.write("one");
//         StorageWriteResponse second = container.write("two");
//         StorageWriteResponse third = container.write("three");

//         assertEquals(SPStatus.SUCCESS, first.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, first.writeStatus);
//         assertEquals(SPStatus.SUCCESS, second.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, second.writeStatus);
//         assertEquals(SPStatus.FAIL, third.status);
//         assertEquals(ContainerWriteStatus.FAIL_LIMIT_EXCEEDED, third.writeStatus);
//     }

//     @Test
//     public void bytesLimitAllowsWithinByteSizeAndRejectsBeyond() throws Exception {
//         String payload = repeat('x', 500);
//         int payloadBytes = Serializer.serialize(payload).length;
//         Container<String, String> container = new Container<>("Tests/TestData/bytesLimitContainer", ContainerLimitType.BYTES, payloadBytes + 1);

//         StorageWriteResponse first = container.write(payload);
//         StorageWriteResponse second = container.write(payload);

//         assertEquals(SPStatus.SUCCESS, first.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, first.writeStatus);
//         assertEquals(SPStatus.FAIL, second.status);
//         assertEquals(ContainerWriteStatus.FAIL_LIMIT_EXCEEDED, second.writeStatus);
//     }

//     @Test
//     public void kiloBytesLimitCountsKilobytesAndRejectsWhenExceeded() throws Exception {
//         String payload = repeat('x', 1500);
//         int payloadKb = Serializer.serialize(payload).length / 1024;
//         assertEquals(1, payloadKb);

//         Container<String, String> container = new Container<>("Tests/TestData/kilobytesLimitContainer", ContainerLimitType.KILOBYTES, 1);

//         StorageWriteResponse first = container.write(payload);
//         StorageWriteResponse second = container.write(payload);

//         assertEquals(SPStatus.SUCCESS, first.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, first.writeStatus);
//         assertEquals(SPStatus.FAIL, second.status);
//         assertEquals(ContainerWriteStatus.FAIL_LIMIT_EXCEEDED, second.writeStatus);
//     }

//     @Test
//     public void megaBytesLimitCountsMegabytesAndRejectsWhenExceeded() throws Exception {
//         String payload = repeat('x', 1_200_000);
//         int payloadMb = Serializer.serialize(payload).length / (1024 * 1024);
//         assertTrue(payloadMb >= 1);

//         Container<String, String> container = new Container<>("Tests/TestData/megabytesLimitContainer", ContainerLimitType.MEGABYTES, 1);

//         StorageWriteResponse first = container.write(payload);
//         StorageWriteResponse second = container.write(payload);

//         assertEquals(SPStatus.SUCCESS, first.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, first.writeStatus);
//         assertEquals(SPStatus.FAIL, second.status);
//         assertEquals(ContainerWriteStatus.FAIL_LIMIT_EXCEEDED, second.writeStatus);
//     }

//     @Test
//     public void gigaBytesLimitAllowsSmallWritesUnderOneGigabyte() throws Exception {
//         String payload = repeat('x', 1024);
//         Container<String, String> container = new Container<>("Tests/TestData/gigabytesLimitContainer", ContainerLimitType.GIGABYTES, 1);

//         StorageWriteResponse response = container.write(payload);

//         assertEquals(SPStatus.SUCCESS, response.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, response.writeStatus);
//     }

//     @Test
//     public void noLimitAcceptsAllWrites() throws Exception {
//         Container<String, String> container = new Container<>("Tests/TestData/noLimitContainer", ContainerLimitType.NO_LIMIT, 0);

//         StorageWriteResponse first = container.write("a");
//         StorageWriteResponse second = container.write("b");
//         StorageWriteResponse third = container.write("c");

//         assertEquals(SPStatus.SUCCESS, first.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, first.writeStatus);
//         assertEquals(SPStatus.SUCCESS, second.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, second.writeStatus);
//         assertEquals(SPStatus.SUCCESS, third.status);
//         assertEquals(ContainerWriteStatus.SUCCESS_STORE, third.writeStatus);
//     }

//     private static String repeat(char c, int count) {
//         StringBuilder sb = new StringBuilder(count);
//         for (int i = 0; i < count; i++) {
//             sb.append(c);
//         }
//         return sb.toString();
//     }
// }
