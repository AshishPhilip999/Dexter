package PBStorageTests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import StorageStatus.PB.OperationStatus;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PBOffSets;

/*
Shared helpers for the PBStorageProvider / PBKeyStorageProvider tests.

PBSuccess values are shared enum constants, so their data is overwritten by the
next call: always take data out of a status straight away (data(...) /
dataArray(...)) before calling the storage again.
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
        return new File(path + ".pb");
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

    public static void assertSuccess(PBStatus status) {
        assertEquals(OperationStatus.SUCCESS, status.getStatus(), "Expected success but was " + status);
    }

    public static void assertFail(PBStatus expected, PBStatus actual) {
        assertEquals(expected, actual);
        assertEquals(OperationStatus.FAIL, actual.getStatus());
    }

    /*
    Data of a successful status, copied out before the shared enum is reused.
    */
    public static byte[] data(PBStatus status) {
        assertTrue(status instanceof PBSuccess, "Expected PBSuccess but was " + status);
        byte[] data = ((PBSuccess) status).getData();
        return data == null ? null : data.clone();
    }

    public static byte[][] dataArray(PBStatus status) {
        assertTrue(status instanceof PBSuccess, "Expected PBSuccess but was " + status);
        byte[][] records = ((PBSuccess) status).getDataArray();
        return records == null ? null : records.clone();
    }
}
