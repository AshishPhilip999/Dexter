package PBStorageTests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;
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

    // ---- open instances ------------------------------------------------------------
    //
    // Only one instance may have a storage open. Tests that "reopen" a storage
    // (to check what was persisted) open it as
    //     track(new PBKeyStorageProvider<>(released(path), Integer.class))
    // released(path) closes the instance this test opened before for that path,
    // as if the earlier program had exited; track(...) remembers the new one.
    // closeAllStorages() runs after each test.

    private static final Map<Path, PBStorageProvider> OPEN = new LinkedHashMap<>();

    private static Path key(String path) {
        return DexBundle.folderOf(path).toAbsolutePath().normalize();
    }

    /*
    Closes the tracked instance for path, if any, and returns path.
    */
    public static String released(String path) throws Exception {
        PBStorageProvider previous;
        synchronized (OPEN) {
            previous = OPEN.remove(key(path));
        }
        if (previous != null) {
            previous.close();
        }
        return path;
    }

    public static <T extends PBStorageProvider> T track(T storage) {
        synchronized (OPEN) {
            OPEN.put(storage.getFolder().toAbsolutePath().normalize(), storage);
        }
        return storage;
    }

    public static void closeAllStorages() throws Exception {
        List<PBStorageProvider> all;
        synchronized (OPEN) {
            all = new ArrayList<>(OPEN.values());
            OPEN.clear();
        }
        for (PBStorageProvider storage : all) {
            storage.close();
        }
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

    /*
    Length of data.pb on disk. If this test has the storage open, its journal
    is checkpointed first, so the file holds every operation so far.
    */
    public static long fileLength(String path) throws Exception {
        checkpointIfOpen(path);
        return datFile(path).length();
    }

    /*
    Makes the files on disk complete for a storage this test has open (see
    track), e.g. before a test inspects or changes them directly.
    */
    public static void checkpointIfOpen(String path) throws Exception {
        PBStorageProvider open;
        synchronized (OPEN) {
            open = OPEN.get(key(path));
        }
        if (open != null && !open.isClosed()) {
            assertSuccess(open.checkpoint());
        }
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
