package PBStorageTests.PBKSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

/*
The disk-based index (.pbi): lazy loading, bounded memory, rebuilding, and
staying consistent with the data file.
*/
public class PBKSPIndexTests {
    @TempDir
    Path dir;

    /*
    Exposes how many pages are held in memory.
    */
    static final class Probe<K extends java.io.Serializable> extends PBKeyStorageProvider<K> {
        Probe(String path, Class<K> keyType) throws Exception {
            super(path, keyType);
        }

        int cachedDataPages() {
            return this.getCachedPageCount();
        }

        int cachedIndexPages() {
            return this.getCachedIndexPageCount();
        }

        int pages() {
            return this.getPageCount();
        }
    }

    private static final int VALUE_SIZE = 100;
    private static final int RECORDS_PER_PAGE =
        (PAGE - PAGE_HEADER) / (SLOT_ENTRY + INT_KEY_BYTES + VALUE_SIZE);

    private PBKeyStorageProvider<Integer> fillOnePage(String path) throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < RECORDS_PER_PAGE; i++) {
            assertSuccess(ksp.write(i, bytes(VALUE_SIZE, i)));
        }
        assertEquals(lengthForPages(1), fileLength(path), "precondition: page 0 is full");
        return ksp;
    }

    // ---- files and opening ---------------------------------------------------

    @Test
    public void indexFileIsCreatedNextToDataFile() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Integer.class).write(1, bytes(5, 1));

        assertTrue(datFile(path).isFile());
        assertTrue(indexFile(path).isFile());
    }

    @Test
    public void openingLoadsNoPagesAndMemoryStaysBounded() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> writer = new PBKeyStorageProvider<>(path, Integer.class);
        int count = 100_000;
        for (int i = 0; i < count; i++) {
            writer.write(i, bytes(VALUE_SIZE, i));
        }

        Probe<Integer> reopened = new Probe<>(path, Integer.class);
        assertTrue(reopened.pages() > PBStorageProvider.DEFAULT_CACHE_PAGES, "precondition: more pages than the cache holds");
        assertEquals(0, reopened.cachedDataPages(), "opening reads no data pages");
        assertEquals(0, reopened.cachedIndexPages(), "opening reads no index pages");

        assertArrayEquals(bytes(VALUE_SIZE, 77_777), data(reopened.read(77_777)));
        assertEquals(1, reopened.cachedDataPages(), "one read loads one data page");
        assertTrue(reopened.cachedIndexPages() <= 4, "one read loads one root-to-leaf path");

        for (int i = 0; i < count; i += 3) {
            assertArrayEquals(bytes(VALUE_SIZE, i), data(reopened.read(i)));
        }
        assertTrue(reopened.cachedDataPages() <= PBStorageProvider.DEFAULT_CACHE_PAGES, "data cache is bounded");
    }

    // ---- rebuilding the index ------------------------------------------------

    @Test
    public void missingIndexFileIsRebuiltOnOpen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 2_000; i++) {
            ksp.write(i, bytes(20, i));
        }
        ksp.write(5, bytes(30, 99));
        ksp.delete(6);

        Files.delete(indexFile(path).toPath());

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertTrue(indexFile(path).isFile());
        assertArrayEquals(bytes(30, 99), data(reopened.read(5)));
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(6));
        assertArrayEquals(bytes(20, 1_999), data(reopened.read(1_999)));
        assertEquals(1_999, dataArray(reopened.read()).length);
    }

    @Test
    public void rebuiltIndexStillReusesDeletedSlots() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);
        ksp.delete(0);
        Files.delete(indexFile(path).toPath());

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertSuccess(reopened.write(1_000, bytes(VALUE_SIZE, 5)));

        assertEquals(lengthForPages(1), fileLength(path), "the deleted slot was reused, no new page");
    }

    @Test
    public void indexBelongingToAnotherDataFileIsRebuilt() throws Exception {
        String a = storagePath(dir, "a");
        String b = storagePath(dir, "b");
        PBKeyStorageProvider<Integer> storeA = new PBKeyStorageProvider<>(a, Integer.class);
        PBKeyStorageProvider<Integer> storeB = new PBKeyStorageProvider<>(b, Integer.class);
        for (int i = 0; i < 500; i++) {
            storeA.write(i, bytes(10, 1));
            storeB.write(i * 2, bytes(10, 2));
        }

        Files.copy(indexFile(a).toPath(), indexFile(b).toPath(), StandardCopyOption.REPLACE_EXISTING);

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(b, Integer.class);
        assertArrayEquals(bytes(10, 2), data(reopened.read(4)));
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(1));
    }

    @Test
    public void olderFileWithoutFileIdOrIndexOpens() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 300; i++) {
            ksp.write(i, bytes(10, i));
        }

        // Make it look like a file from before file ids and index files existed.
        try (RandomAccessFile raf = new RandomAccessFile(datFile(path), "rw")) {
            raf.seek(FILE_HEADER - Long.BYTES);
            raf.writeLong(0L);
        }
        Files.delete(indexFile(path).toPath());

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 300; i++) {
            assertArrayEquals(bytes(10, i), data(reopened.read(i)));
        }
        try (RandomAccessFile raf = new RandomAccessFile(datFile(path), "r")) {
            raf.seek(FILE_HEADER - Long.BYTES);
            assertNotEquals(0L, raf.readLong(), "the file was given an id");
        }
    }

    @Test
    public void reconstructKeyIndexKeepsEverythingReadable() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 1_000; i++) {
            ksp.write(i, bytes(10, i));
        }
        ksp.delete(500);

        ksp.reconstructKeyIndex();

        assertArrayEquals(bytes(10, 499), data(ksp.read(499)));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(500));
        assertSuccess(ksp.write(2_000, bytes(10, 7)));
        assertArrayEquals(bytes(10, 7), data(new PBKeyStorageProvider<>(path, Integer.class).read(2_000)));
    }

    @Test
    public void rebuildKeepsOneRecordWhenAKeyWasLeftLiveTwice() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        ksp.write(1, bytes(10, 1));   // page 0, slot 0
        ksp.write(1, bytes(10, 2));   // page 0, slot 1; slot 0 marked deleted

        // Simulate a crash before the old slot was marked deleted: un-delete slot 0.
        long slot0DeletedFlag = FILE_HEADER + PAGE_HEADER + 16;
        try (RandomAccessFile raf = new RandomAccessFile(datFile(path), "rw")) {
            raf.seek(slot0DeletedFlag);
            raf.writeByte(0);
        }
        Files.delete(indexFile(path).toPath());

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertArrayEquals(bytes(10, 2), data(reopened.read(1)), "the later record wins");
        assertEquals(1, dataArray(reopened.read()).length, "the duplicate was marked deleted");
    }

    // ---- staying consistent with the data file -------------------------------

    @Test
    public void keylessWriteThroughPlainProviderInvalidatesIndex() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 100; i++) {
            ksp.write(i, bytes(10, i));
        }

        new PBStorageProvider(path).write(bytes(3, 9));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(0));
        assertSuccess(reopened.write(0, bytes(10, 5)));
        assertArrayEquals(bytes(10, 5), data(reopened.read(0)));
        assertEquals(2, dataArray(reopened.read()).length);
    }

    @Test
    public void deleteAllThroughPlainProviderInvalidatesIndex() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 100; i++) {
            ksp.write(i, bytes(10, i));
        }

        new PBStorageProvider(path).delete();

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(50));
        assertFail(PBFail.NULL_READ, reopened.read());
    }

    @Test
    public void deleteAllEmptiesTheIndexFile() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 10_000; i++) {
            ksp.write(i, bytes(10, i));
        }
        long before = indexFile(path).length();

        ksp.delete();

        assertTrue(indexFile(path).length() < before);
        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(1));
        assertSuccess(reopened.write(1, bytes(10, 1)));
    }

    // ---- key size limits -----------------------------------------------------

    @Test
    public void keyAtTheIndexLimitWorksAndOneByteMoreIsRejected() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<String> ksp = new PBKeyStorageProvider<>(path, String.class);
        String atLimit = "k".repeat(PBKeyStorageProvider.MAX_KEY_BYTES - 1);   // + 1 tag byte
        String overLimit = atLimit + "k";

        assertSuccess(ksp.write(atLimit, bytes(10, 1)));
        assertArrayEquals(bytes(10, 1), data(new PBKeyStorageProvider<>(path, String.class).read(atLimit)));

        assertFail(PBFail.KEY_TOO_LARGE, ksp.write(overLimit, bytes(10, 1)));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(overLimit));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.delete(overLimit));
        assertFalse(ksp.containsKey(overLimit));
    }

    @Test
    public void manyLongStringKeys() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<String> ksp = new PBKeyStorageProvider<>(path, String.class);
        Random random = new Random(3);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            char[] chars = new char[800 + random.nextInt(200)];
            for (int c = 0; c < chars.length; c++) {
                chars[c] = (char) ('a' + random.nextInt(26));
            }
            String key = new String(chars) + i;
            keys.add(key);
            assertSuccess(ksp.write(key, bytes(10, i)));
        }

        PBKeyStorageProvider<String> reopened = new PBKeyStorageProvider<>(path, String.class);
        for (int i = 0; i < keys.size(); i++) {
            assertArrayEquals(bytes(10, i), data(reopened.read(keys.get(i))));
        }
    }

    // ---- randomized check against a HashMap ----------------------------------

    @Test
    public void randomOperationsMatchHashMapAcrossReopens() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        Map<Integer, byte[]> model = new HashMap<>();
        Random random = new Random(2026);

        for (int op = 0; op < 30_000; op++) {
            int key = random.nextInt(2_000) - 1_000;
            int choice = random.nextInt(10);

            if (choice < 5) {
                byte[] value = bytes(1 + random.nextInt(300), random.nextInt(256));
                assertSuccess(ksp.write(key, value));
                model.put(key, value);
            } else if (choice < 7) {
                boolean present = model.remove(key) != null;
                assertEquals(present ? PBSuccess.DELETE : PBFail.KEY_NOT_FOUND, ksp.delete(key).getStatus(), "delete, op " + op);
            } else {
                byte[] expected = model.get(key);
                if (expected == null) {
                    assertFail(PBFail.KEY_NOT_FOUND, ksp.read(key));
                } else {
                    assertArrayEquals(expected, data(ksp.read(key)), "read, op " + op);
                }
            }

            if (op % 7_500 == 7_499) {
                ksp = new PBKeyStorageProvider<>(path, Integer.class);
            }
        }

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        for (int key = -1_000; key < 1_000; key++) {
            byte[] expected = model.get(key);
            assertEquals(expected != null, reopened.containsKey(key), "containsKey " + key);
            if (expected != null) {
                assertArrayEquals(expected, data(reopened.read(key)), "final read " + key);
            }
        }

        byte[][] records = dataArray(reopened.read());
        assertEquals(model.size(), records.length);
        List<String> actual = new ArrayList<>();
        for (byte[] r : records) {
            actual.add(Arrays.toString(r));
        }
        List<String> expected = new ArrayList<>();
        for (byte[] r : model.values()) {
            expected.add(Arrays.toString(r));
        }
        actual.sort(null);
        expected.sort(null);
        assertEquals(expected, actual, "read() returns exactly the live values");
    }

}
