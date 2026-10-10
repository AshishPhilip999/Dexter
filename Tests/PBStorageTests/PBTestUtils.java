package PBStorageTests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PBOffSets;

/*
Shared helpers for the PBStorageProvider / PBKeyStorageProvider tests.

Every storage call returns its own PBResult; data(...) / dataArray(...) check
it succeeded and return its data.
*/
public final class PBTestUtils {
    public static final int FILE_HEADER = PBOffSets.FILE_HEADER.getValue();
    public static final int PAGE = PBOffSets.PAGE.getValue();
    public static final int PAGE_HEADER = PBOffSets.HEADER.getValue();
    public static final int SLOT_ENTRY = PBOffSets.SLOT.getValue();

    /*
    Largest record (key bytes + data bytes) that fits one page.
    */
    public static final int MAX_RECORD = PAGE - PAGE_HEADER - SLOT_ENTRY;

    /*
    An Integer key is encoded as 1 tag byte + 4 value bytes.
    */
    public static final int INT_KEY_BYTES = 5;

    private PBTestUtils() {
    }

    public static String storagePath(Path dir, String name) {
        return dir.resolve(name).toString();
    }

    public static File datFile(String path) {
        return new File(dexFolder(path), "data.pb");
    }

    /*
    The storage's .dex folder.
    */
    public static File dexFolder(String path) {
        return new File(path + ".dex");
    }

    public static File indexFile(String path) {
        return new File(dexFolder(path), "index.pbi");
    }

    public static long fileLength(String path) {
        return datFile(path).length();
    }

    /*
    File length of a storage holding exactly this many pages.
    */
    public static long lengthForPages(int pages) {
        return FILE_HEADER + ((long) pages * PAGE);
    }

    public static byte[] bytes(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return data;
    }

    public static void assertSuccess(PBResult result) {
        assertTrue(result.hasSucceeded(), "Expected success but was " + result);
    }

    public static void assertFail(PBFail expected, PBResult actual) {
        assertEquals(expected, actual.getStatus());
        assertFalse(actual.hasSucceeded());
    }

    /*
    Data of a successful result.
    */
    public static byte[] data(PBResult result) {
        assertTrue(result.hasSucceeded(), "Expected success but was " + result);
        return result.getData();
    }

    public static byte[][] dataArray(PBResult result) {
        assertTrue(result.hasSucceeded(), "Expected success but was " + result);
        return result.getDataArray();
    }

}
