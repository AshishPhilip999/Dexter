package PBStorageTests.PBKSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.Serialization.Serializer;

public class PBKSPReadTests {
    @TempDir
    Path dir;

    private PBKeyStorageProvider<Integer> newStorage() throws Exception {
        return new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
    }

    // ---- read(key) ---------------------------------------------------------

    @Test
    public void readKeyReturnsReadWithData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));

        PBStatus status = ksp.read(1);

        assertEquals(PBSuccess.READ, status);
        assertSuccess(status);
        assertArrayEquals(bytes(10, 1), data(status));
        assertNull(dataArray(status));
    }

    @Test
    public void readKeyOnEmptyStorageReturnsKeyNotFound() throws Exception {
        assertFail(PBFail.KEY_NOT_FOUND, newStorage().read(1));
    }

    @Test
    public void readUnknownKeyReturnsKeyNotFound() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(2));
    }

    @Test
    public void readNullKeyFails() throws Exception {
        assertFail(PBFail.NULL_KEY, newStorage().read(null));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void readKeyOfWrongTypeFails() throws Exception {
        PBKeyStorageProvider raw = newStorage();
        raw.write(1, bytes(10, 1));
        assertFail(PBFail.KEY_TYPE_MISMATCH, raw.read("1"));
    }

    @Test
    public void readEachKeyReturnsItsOwnData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        for (int i = 0; i < 20; i++) {
            ksp.write(i, Serializer.serialize("value-" + i));
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("value-" + i, Serializer.deserialize(data(ksp.read(i))));
        }
    }

    @Test
    public void readKeyAfterOverwriteReturnsLatestValue() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        ksp.write(1, bytes(30, 2));
        assertArrayEquals(bytes(30, 2), data(ksp.read(1)));
    }

    @Test
    public void readKeyAfterReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Integer.class).write(5, bytes(10, 5));

        assertArrayEquals(bytes(10, 5), data(new PBKeyStorageProvider<>(path, Integer.class).read(5)));
    }

    @Test
    public void readKeyAfterKeylessWriteReturnsKeyNotFound() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        ksp.write(bytes(3, 9));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(1));
    }

    @Test
    public void readDistinguishesNegativeZeroAndExtremeKeys() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        int[] keys = { Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE };
        for (int key : keys) {
            ksp.write(key, Serializer.serialize("k" + key));
        }

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        for (int key : keys) {
            assertEquals("k" + key, Serializer.deserialize(data(reopened.read(key))));
        }
    }

    // ---- read() ------------------------------------------------------------

    @Test
    public void readAllOnEmptyStorageReturnsNullRead() throws Exception {
        assertFail(PBFail.NULL_READ, newStorage().read());
    }

    @Test
    public void readAllReturnsEveryRecord() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        for (int i = 0; i < 10; i++) {
            ksp.write(i, Serializer.serialize("value-" + i));
        }

        PBStatus status = ksp.read();

        assertEquals(PBSuccess.READ, status);
        byte[][] records = dataArray(status);
        assertEquals(10, records.length);

        Set<String> values = new HashSet<>();
        for (byte[] record : records) {
            values.add(Serializer.deserialize(record));
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(values.contains("value-" + i), "missing value-" + i);
        }
    }

    @Test
    public void readAllRecordsExcludeKeyBytes() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(123456, bytes(4, 1));

        byte[][] records = dataArray(ksp.read());
        assertEquals(1, records.length);
        assertArrayEquals(bytes(4, 1), records[0]);
    }

    @Test
    public void readAllDataIsFirstRecord() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(4, 1));
        ksp.write(2, bytes(4, 2));

        PBStatus status = ksp.read();
        byte[][] records = dataArray(status);
        assertArrayEquals(records[0], data(status));
    }

    @Test
    public void readAllShowsOverwrittenKeyOnceWithLatestValue() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(4, 1));
        ksp.write(2, bytes(4, 2));
        ksp.write(1, bytes(4, 3));

        byte[][] records = dataArray(ksp.read());
        assertEquals(2, records.length);
        for (byte[] record : records) {
            assertNotEquals(1, record[0], "old value of key 1 must not be returned");
        }
    }

    @Test
    public void readAllExcludesDeletedKeys() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(4, 1));
        ksp.write(2, bytes(4, 2));
        ksp.delete(1);

        byte[][] records = dataArray(ksp.read());
        assertEquals(1, records.length);
        assertArrayEquals(bytes(4, 2), records[0]);
    }

    @Test
    public void readAllAfterReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 100; i++) {
            ksp.write(i, bytes(50, i));
        }
        ksp.delete(10);
        ksp.write(20, bytes(50, 99));

        assertEquals(99, dataArray(new PBKeyStorageProvider<>(path, Integer.class).read()).length);
    }

    @Test
    public void readAllAfterKeylessWriteReturnsOnlyThatRecord() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(4, 1));
        ksp.write(bytes(3, 9));

        byte[][] records = dataArray(ksp.read());
        assertEquals(1, records.length);
        assertArrayEquals(bytes(3, 9), records[0]);
    }

    // ---- containsKey -------------------------------------------------------

    @Test
    public void containsKeyReflectsWritesAndDeletes() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        assertFalse(ksp.containsKey(1));

        ksp.write(1, bytes(4, 1));
        assertTrue(ksp.containsKey(1));

        ksp.delete(1);
        assertFalse(ksp.containsKey(1));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void containsKeyIsFalseForNullAndWrongType() throws Exception {
        PBKeyStorageProvider raw = newStorage();
        raw.write(1, bytes(4, 1));

        assertFalse(raw.containsKey(null));
        assertFalse(raw.containsKey("1"));
    }

    @Test
    public void containsKeyAfterReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Integer.class).write(1, bytes(4, 1));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertTrue(reopened.containsKey(1));
        assertFalse(reopened.containsKey(2));
    }
}
