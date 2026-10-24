package ContainerTests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ContainerCore.Container.Container;
import ContainerCore.Container.ContainerResult;
import ContainerCore.Container.KeyContainer;
import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerMeta;
import StorageCore.Storage;
import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.DexterFileService;
import Utility.StorageProviders.DexterKeyFileService;
import Utility.StorageProviders.StorageProviderType;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException;

/*
Container and KeyContainer on page-based storage: operations, ContainerResult,
length / size, limits, the metadata file, and wiring to the storage layer.
*/
public class ContainerApiTests {
    @TempDir
    Path dir;

    private String path(String name) {
        return this.dir.resolve(name).toString();
    }

    private static TestableObject object(int id) {
        return new TestableObject(id, "object-" + id, id % 2 == 0);
    }

    private static int bytesOf(Serializable value) throws Exception {
        return Serializer.serialize(value).length;
    }

    static final class NotSerializableInside implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object field = new Object();
    }

    // ---- wiring ---------------------------------------------------------------------------

    @Test
    public void storageProvidersImplementTheInterfaces() throws Exception {
        try (DexterFileService keyless = StorageProviderType.PAGE_BASED.open(path("a"));
             DexterKeyFileService<Integer> keyed = StorageProviderType.PAGE_BASED.openKeyed(path("b"), Integer.class)) {
            assertTrue(keyless instanceof PBStorageProvider);
            assertTrue(keyed instanceof PBKeyStorageProvider);
            assertEquals(SPSuccess.WRITE, keyed.write(1, new byte[] { 1 }).getStatus());
            assertArrayEquals(new byte[] { 1 }, keyed.read(1).getData());
        }
    }

    @Test
    public void pageBasedIsTheDefault() throws Exception {
        try (Container<TestableObject> a = new Container<>(path("a"), TestableObject.class);
             Container<TestableObject> b = new Container<>(path("b"), TestableObject.class, StorageProviderType.PAGE_BASED)) {
            assertEquals(StorageProviderType.PAGE_BASED, a.getStorageProviderType());
            assertEquals(StorageProviderType.PAGE_BASED, b.getStorageProviderType());
            assertTrue(Files.isDirectory(this.dir.resolve("a.dex")));
            assertTrue(Files.isRegularFile(this.dir.resolve("a.dex").resolve("container.meta")));
        }
    }

    @Test
    public void containerInAStorageUsesItsDirectory() throws Exception {
        Files.createDirectories(this.dir.resolve("store"));
        Storage storage = new Storage(this.dir.resolve("store").toString(), "store");
        try (KeyContainer<Integer, TestableObject> users = new KeyContainer<>(storage, "users", Integer.class,
                TestableObject.class, StorageProviderType.PAGE_BASED, null, 0)) {
            assertEquals(this.dir.resolve("store") + "/users", users.getContainerFullPath());
            assertTrue(users.write(1, object(1)).hasSucceeded());
        }
        assertTrue(Files.isDirectory(this.dir.resolve("store").resolve("users.dex")));
    }

    // ---- Container (one value) ---------------------------------------------------------------

    @Test
    public void writeAndReadReturnTheValue() throws Exception {
        try (Container<TestableObject> c = new Container<>(path("c"), TestableObject.class)) {
            ContainerResult<TestableObject> written = c.write(object(1));
            assertEquals(SPSuccess.WRITE, written.getStatus());
            assertEquals(object(1), written.getValue());

            ContainerResult<TestableObject> read = c.read();
            assertEquals(SPSuccess.READ, read.getStatus());
            assertEquals(object(1), read.getValue());
            assertEquals(1, c.length());
            assertEquals(bytesOf(object(1)), c.size());
        }
    }

    @Test
    public void writeReplacesTheValue() throws Exception {
        try (Container<String> c = new Container<>(path("c"), String.class)) {
            c.write("first");
            c.write("second, longer");
            assertEquals("second, longer", c.read().getValue());
            assertEquals(1, c.length());
            assertEquals(bytesOf("second, longer"), c.size());
        }
    }

    @Test
    public void deleteEmptiesTheContainer() throws Exception {
        try (Container<String> c = new Container<>(path("c"), String.class)) {
            c.write("value");
            assertEquals(SPSuccess.DELETE, c.delete().getStatus());

            assertEquals(SPFail.NULL_READ, c.read().getStatus());
            assertNull(c.read().getValue());
            assertEquals(0, c.length());
            assertEquals(0, c.size());
        }
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void badValuesAreRefused() throws Exception {
        try (Container<Serializable> c = new Container<>(path("c"), Serializable.class);
             Container<String> strings = new Container<>(path("s"), String.class)) {
            assertEquals(SPFail.NULL_WRITE, c.write(null).getStatus());

            ContainerResult<Serializable> notSerializable = c.write(new NotSerializableInside());
            assertEquals(SPFail.SERIALIZE_ERROR, notSerializable.getStatus());
            assertTrue(notSerializable.getError() instanceof NotSerializableException);

            ContainerResult wrongType = ((Container) strings).write(Integer.valueOf(5));
            assertEquals(SPFail.VALUE_TYPE_MISMATCH, wrongType.getStatus());
            assertEquals(0, strings.length());
        }
    }

    @Test
    public void bytesOfAnotherTypeAreADeserializeError() throws Exception {
        String path = path("k");
        try (PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(path, Integer.class)) {
            sp.write(1, Serializer.serialize(Integer.valueOf(3)));   // not a String
            sp.write(2, new byte[] { 1, 2, 3 });                       // not serialized at all
            sp.write(3, Serializer.serialize("fine"));
        }
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            ContainerResult<String> wrongType = c.read(1);
            assertEquals(SPFail.DESERIALIZE_ERROR, wrongType.getStatus());
            assertFalse(wrongType.hasSucceeded());
            assertTrue(wrongType.getError() instanceof IllegalStateException);
            assertNull(wrongType.getValue());

            assertEquals(SPFail.DESERIALIZE_ERROR, c.read(2).getStatus());
            assertEquals(SPFail.DESERIALIZE_ERROR, c.read().getStatus(), "one bad record fails the whole read()");
            assertEquals("fine", c.read(3).getValue());

            ContainerResult<String> deleted = c.delete(1);
            assertEquals(SPSuccess.DELETE, deleted.getStatus(), "a value that isn't a String can still be deleted");
            assertNull(deleted.getValue());
        }
    }

    @Test
    public void valueLengthSizeAndLimitsSurviveReopening() throws Exception {
        String path = path("c");
        try (Container<String> c = new Container<>(path, String.class)) {
            c.write("persisted");
            c.setMaxSize(5_000);
        }
        try (Container<String> c = new Container<>(path, String.class)) {
            assertEquals("persisted", c.read().getValue());
            assertEquals(1, c.length());
            assertEquals(bytesOf("persisted"), c.size());
            assertEquals(5_000, c.getMaxSize(), "limits are kept unless the constructor gives new ones");
        }
        try (Container<String> c = new Container<>(path, String.class, ContainerLimitType.NO_LIMIT, 0)) {
            assertEquals(0, c.getMaxSize());
        }
    }

    @Test
    public void byteLimitRefusesAValueTooBig() throws Exception {
        int small = bytesOf("small");
        try (Container<String> c = new Container<>(path("c"), String.class, ContainerLimitType.BYTES, small)) {
            assertTrue(c.write("small").hasSucceeded());
            assertTrue(c.isFull());

            assertEquals(SPFail.LIMIT_EXCEEDED, c.write("much larger than allowed").getStatus());
            assertEquals("small", c.read().getValue(), "nothing was written");
            assertEquals(small, c.size());
        }
    }

    @Test
    public void limitUnitsAndSetters() throws Exception {
        try (Container<String> c = new Container<>(path("c"), String.class, ContainerLimitType.KILOBYTES, 3)) {
            assertEquals(3 * 1024, c.getMaxSize());
            c.setLimit(ContainerLimitType.MEGABYTES, 2);
            assertEquals(2L * 1024 * 1024, c.getMaxSize());
            c.setLimit(ContainerLimitType.GIGABYTES, 5);
            assertEquals(5L * 1024 * 1024 * 1024, c.getMaxSize());
            c.setLimit(ContainerLimitType.LENGTH, 7);
            assertEquals(7, c.getMaxLength());
            c.setLimit(ContainerLimitType.NO_LIMIT, 0);
            assertEquals(0, c.getMaxSize());
            assertEquals(0, c.getMaxLength());
            assertThrows(IllegalArgumentException.class, () -> c.setMaxSize(-1));
            assertThrows(IllegalArgumentException.class, () -> c.setMaxLength(-1));
        }
    }

    @Test
    public void closedContainer() throws Exception {
        String path = path("c");
        Container<String> c = new Container<>(path, String.class);
        c.write("v");
        assertThrows(StorageInUseException.class, () -> new Container<>(path, String.class), "one instance per storage");
        c.close();
        c.close();

        assertTrue(c.isClosed());
        assertEquals(SPFail.STORAGE_CLOSED, c.read().getStatus());
        assertEquals(SPFail.STORAGE_CLOSED, c.write("w").getStatus());
        assertEquals(SPFail.STORAGE_CLOSED, c.delete().getStatus());
        try (Container<String> again = new Container<>(path, String.class)) {
            assertEquals("v", again.read().getValue());
        }
    }

    @Test
    public void constructorArgumentsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Container<>("", String.class));
        assertThrows(IllegalArgumentException.class, () -> new Container<String>(path("c"), null));
        assertThrows(IllegalArgumentException.class, () -> new Container<>(path("c"), String.class, (StorageProviderType) null));
        assertThrows(IllegalArgumentException.class, () -> new Container<>(path("c"), String.class, ContainerLimitType.BYTES, -1));
        assertThrows(IllegalArgumentException.class, () -> new KeyContainer<String, String>(path("k"), null, String.class));
    }

    // ---- ContainerResult ---------------------------------------------------------------------

    @Test
    public void everyOperationReturnsItsValue() throws Exception {
        try (KeyContainer<Integer, TestableObject> c = new KeyContainer<>(path("k"), Integer.class, TestableObject.class)) {
            ContainerResult<TestableObject> written = c.write(1, object(1));
            assertEquals(SPSuccess.WRITE, written.getStatus());
            assertEquals(object(1), written.getValue(), "write: the value written");
            assertArrayEquals(Serializer.serialize(object(1)), written.getData(), "and its bytes, as in SPResult");
            assertEquals(List.of(object(1)), written.getValues());

            c.write(2, object(2));
            assertEquals(object(2), c.read(2).getValue(), "read(key): the value");

            ContainerResult<TestableObject> all = c.read();
            assertEquals(2, all.getValues().size(), "read(): every value");
            assertEquals(all.getValues().get(0), all.getValue(), "getValue() is the first");
            assertEquals(2, all.getDataArray().length);
            assertThrows(UnsupportedOperationException.class, () -> all.getValues().add(object(9)));

            ContainerResult<TestableObject> deleted = c.delete(1);
            assertEquals(SPSuccess.DELETE, deleted.getStatus());
            assertEquals(object(1), deleted.getValue(), "delete(key): the value removed");

            ContainerResult<TestableObject> missing = c.read(1);
            assertEquals(SPFail.KEY_NOT_FOUND, missing.getStatus());
            assertNull(missing.getValue());
            assertEquals(List.of(), missing.getValues());

            assertNull(c.delete().getValue(), "delete(): no value");
            assertEquals(SPFail.NULL_READ, c.read().getStatus());
            assertEquals(List.of(), c.read().getValues());
        }
    }

    @Test
    public void containerResultIsAnSPResult() throws Exception {
        try (Container<String> c = new Container<>(path("c"), String.class)) {
            SPResult asStorageResult = c.write("hello");    // usable wherever an SPResult is
            assertTrue(asStorageResult.hasSucceeded());
            assertEquals(SPSuccess.WRITE, asStorageResult.getStatus());
            assertNull(asStorageResult.getError());

            ContainerResult<String> tooBig;
            c.setMaxSize(10);
            tooBig = c.write("much longer than ten bytes");
            assertEquals(SPFail.LIMIT_EXCEEDED, tooBig.getStatus());
            assertNull(tooBig.getValue());
            assertEquals("LIMIT_EXCEEDED", tooBig.toString());
            assertEquals("WRITE (hello)", ContainerResult.from(SPResult.of(SPSuccess.WRITE, new byte[1]), "hello").toString());
        }
    }

    // ---- KeyContainer --------------------------------------------------------------------------

    @Test
    public void keyedWriteReadAndGetObjects() throws Exception {
        try (KeyContainer<Integer, TestableObject> users = new KeyContainer<>(path("u"), Integer.class, TestableObject.class)) {
            for (int i = 0; i < 50; i++) {
                assertEquals(SPSuccess.WRITE, users.write(i, object(i)).getStatus());
            }
            assertEquals(object(7), users.read(7).getValue());
            assertEquals(SPFail.KEY_NOT_FOUND, users.read(99).getStatus());
            assertTrue(users.containsKey(7));
            assertFalse(users.containsKey(99));

            List<TestableObject> all = users.read().getValues();
            assertEquals(50, all.size());
            assertTrue(all.contains(object(42)));
            assertEquals(List.of(object(3)), users.read(3).getValues());
            assertEquals(List.of(), users.read(99).getValues());
        }
    }

    @Test
    public void lengthAndSizeFollowWritesOverwritesAndDeletes() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class)) {
            c.write(1, "a");
            c.write(2, "bb");
            assertEquals(2, c.length());
            assertEquals(bytesOf("a") + bytesOf("bb"), c.size());

            c.write(1, "a much longer value");
            assertEquals(2, c.length(), "an overwrite doesn't add a value");
            assertEquals(bytesOf("a much longer value") + bytesOf("bb"), c.size());

            assertEquals(SPSuccess.DELETE, c.delete(2).getStatus());
            assertEquals(1, c.length());
            assertEquals(bytesOf("a much longer value"), c.size());

            assertEquals(SPFail.KEY_NOT_FOUND, c.delete(2).getStatus());
            assertEquals(1, c.length(), "a failed delete changes nothing");

            c.write("keyless");   // replaces everything, as on the storage
            assertEquals(1, c.length());
            assertEquals(bytesOf("keyless"), c.size());

            c.delete();
            assertEquals(0, c.length());
            assertEquals(0, c.size());
        }
    }

    @Test
    public void maxLengthRefusesNewKeysButAllowsOverwrites() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class,
                ContainerLimitType.LENGTH, 3)) {
            assertTrue(c.write(1, "one").hasSucceeded());
            assertTrue(c.write(2, "two").hasSucceeded());
            assertTrue(c.write(3, "three").hasSucceeded());
            assertTrue(c.isFull());

            assertEquals(SPFail.LIMIT_EXCEEDED, c.write(4, "four").getStatus());
            assertEquals(SPFail.KEY_NOT_FOUND, c.read(4).getStatus());
            assertTrue(c.write(2, "two again").hasSucceeded(), "overwriting doesn't add a value");

            c.delete(1);
            assertTrue(c.write(4, "four").hasSucceeded());
            assertEquals(3, c.length());
        }
    }

    @Test
    public void maxSizeRefusesGrowthButAllowsShrinking() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class)) {
            c.write(1, "x".repeat(200));
            c.write(2, "y".repeat(200));
            long full = c.size();

            c.setMaxSize(full);
            assertEquals(SPFail.LIMIT_EXCEEDED, c.write(1, "x".repeat(201)).getStatus());
            assertEquals(SPFail.LIMIT_EXCEEDED, c.write(3, "z").getStatus());
            assertTrue(c.write(1, "x".repeat(100)).hasSucceeded(), "shrinking is fine");
            assertTrue(c.write(3, "z").hasSucceeded(), "the space freed can be used");

            c.setMaxSize(10);   // now below what is stored
            assertTrue(c.write(1, "x").hasSucceeded(), "still shrinking: allowed");
            assertEquals(SPFail.LIMIT_EXCEEDED, c.write(4, "w").getStatus());
            assertEquals("x", c.read(1).getValue());
        }
    }

    @Test
    public void keyedBadInputs() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class)) {
            assertEquals(SPFail.NULL_KEY, c.write(null, "v").getStatus());
            assertEquals(SPFail.NULL_WRITE, c.write(1, null).getStatus());
            assertEquals(SPFail.NULL_KEY, c.read(null).getStatus());
            assertEquals(SPFail.NULL_KEY, c.delete(null).getStatus());
            assertEquals(SPFail.DATA_TOO_LARGE, c.write(1, "x".repeat(5_000)).getStatus());
            assertEquals(0, c.length());
            assertEquals(0, c.size());
        }
    }

    @Test
    public void keyedCountsSurviveReopening() throws Exception {
        String path = path("k");
        long size;
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            for (int i = 0; i < 300; i++) {
                c.write(i, "value-" + i);
            }
            c.delete(5);
            c.setMaxLength(1_000);
            size = c.size();
        }
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(299, c.length());
            assertEquals(size, c.size());
            assertEquals(1_000, c.getMaxLength());
            assertEquals("value-7", c.read(7).getValue());
        }
    }

    // ---- counting again when the metadata can't be trusted ------------------------------------

    private static void copyFolder(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.getFileName().toString().equals("dex.lock")) {
                    Files.copy(file, to.resolve(file.getFileName()));
                }
            }
        }
    }

    @Test
    public void afterACrashTheValuesAreCountedAgain() throws Exception {
        String path = path("live");
        KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class);
        Map<Integer, String> expected = new HashMap<>();
        Random random = new Random(3);
        for (int i = 0; i < 2_000; i++) {
            int k = random.nextInt(500);
            if (random.nextInt(4) == 0) {
                c.delete(k);
                expected.remove(k);
            } else {
                String v = "v".repeat(1 + random.nextInt(50)) + i;
                c.write(k, v);
                expected.put(k, v);
            }
        }
        long expectedSize = 0;
        for (String v : expected.values()) {
            expectedSize += bytesOf(v);
        }
        assertEquals(expected.size(), c.length());
        assertEquals(expectedSize, c.size());

        // A crash now: the files as they are, metadata still marked "open".
        copyFolder(this.dir.resolve("live.dex"), this.dir.resolve("image.dex"));
        c.close();

        try (KeyContainer<Integer, String> recovered = new KeyContainer<>(path("image"), Integer.class, String.class)) {
            assertEquals(expected.size(), recovered.length());
            assertEquals(expectedSize, recovered.size());
        }
    }

    @Test
    public void missingOrDamagedMetadataIsRebuilt() throws Exception {
        String path = path("k");
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            for (int i = 0; i < 40; i++) {
                c.write(i, "value-" + i);
            }
        }
        Path meta = this.dir.resolve("k.dex").resolve("container.meta");

        Files.write(meta, new byte[] { 1, 2, 3 });
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(40, c.length());
        }

        Files.delete(meta);
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(40, c.length());
        }
    }

    /*
    While open the metadata says "open"; close() marks it closed with the final
    counts, and the next open trusts them instead of counting again (shown here
    by changing the storage behind the container's back, which it then doesn't
    notice -- the reason to use the storage only through its container).
    */
    @Test
    public void cleanCloseIsTrustedOnTheNextOpen() throws Exception {
        String path = path("k");
        Path meta = this.dir.resolve("k.dex").resolve("container.meta");
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            c.write(1, "one");
            c.write(2, "two");
            assertFalse(ContainerMeta.load(meta).closedCleanly, "marked open while in use");
        }
        ContainerMeta closed = ContainerMeta.load(meta);
        assertTrue(closed.closedCleanly);
        assertEquals(2, closed.length);

        try (PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(path, Integer.class)) {
            sp.write(3, Serializer.serialize("three"));    // behind the container's back
        }
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(2, c.length(), "the saved count is used, nothing is counted again");
            c.recount();
            assertEquals(3, c.length(), "recount() sees the extra value");
        }
    }

    @Test
    public void metadataWithAFlippedByteIsRebuilt() throws Exception {
        String path = path("k");
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            for (int i = 0; i < 30; i++) {
                c.write(i, "value-" + i);
            }
        }
        Path meta = this.dir.resolve("k.dex").resolve("container.meta");
        byte[] bytes = Files.readAllBytes(meta);
        bytes[16] ^= 0x7F;                                   // inside the stored length
        Files.write(meta, bytes);
        assertNull(ContainerMeta.load(meta), "the checksum catches it");

        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(30, c.length());
        }
    }

    @Test
    public void storageWrittenWithoutAContainerIsCounted() throws Exception {
        String path = path("plain");
        try (PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(path, Integer.class)) {
            for (int i = 0; i < 25; i++) {
                sp.write(i, Serializer.serialize("value-" + i));
            }
        }
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path, Integer.class, String.class)) {
            assertEquals(25, c.length());
            assertEquals("value-3", c.read(3).getValue());
        }
    }

    @Test
    public void recountMatchesTheTrackedCounts() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class)) {
            Random random = new Random(9);
            for (int i = 0; i < 1_000; i++) {
                int k = random.nextInt(200);
                if (random.nextInt(3) == 0) {
                    c.delete(k);
                } else {
                    c.write(k, "w".repeat(random.nextInt(40)) + i);
                }
            }
            long length = c.length();
            long size = c.size();
            assertEquals(SPSuccess.READ, c.recount().getStatus());
            assertEquals(length, c.length());
            assertEquals(size, c.size());
        }
    }

    // ---- threads ---------------------------------------------------------------------------------

    @Test
    public void concurrentWritersNeverPassTheLimit() throws Exception {
        try (KeyContainer<Integer, String> c = new KeyContainer<>(path("k"), Integer.class, String.class,
                ContainerLimitType.LENGTH, 150)) {
            Queue<String> failures = new ConcurrentLinkedQueue<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                final int seed = t;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        Random random = new Random(seed);
                        for (int i = 0; i < 2_000; i++) {
                            int k = random.nextInt(400);
                            ContainerResult<String> r = random.nextInt(4) == 0 ? c.delete(k) : c.write(k, "t" + seed + "-" + i);
                            SPFail s = r.hasSucceeded() ? null : (SPFail) r.getStatus();
                            if (s != null && s != SPFail.LIMIT_EXCEEDED && s != SPFail.KEY_NOT_FOUND) {
                                failures.add(k + " -> " + r);
                            }
                            if (c.length() > 150) {
                                failures.add("length " + c.length() + " passed the limit");
                            }
                        }
                    } catch (Throwable e) {
                        failures.add(e.toString());
                    }
                });
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(60_000);
                assertFalse(thread.isAlive());
            }
            assertEquals(List.of(), new ArrayList<>(failures));

            long length = c.length();
            long size = c.size();
            c.recount();
            assertEquals(length, c.length(), "tracked length matches the storage");
            assertEquals(size, c.size(), "tracked size matches the storage");
            assertTrue(length <= 150);
        }
    }
}
