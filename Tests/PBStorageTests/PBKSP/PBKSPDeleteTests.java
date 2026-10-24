package PBStorageTests.PBKSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

public class PBKSPDeleteTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    private PBKeyStorageProvider<Integer> newStorage() throws Exception {
        return track(new PBKeyStorageProvider<>(released(storagePath(dir, "ksp")), Integer.class));
    }

    // ---- delete(key) -------------------------------------------------------

    @Test
    public void deleteKeyReturnsDelete() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));

        SPResult status = ksp.delete(1);

        assertEquals(SPSuccess.DELETE, status.getStatus());
        assertSuccess(status);
    }

    @Test
    public void deleteKeyRemovesOnlyThatKey() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        ksp.write(2, bytes(10, 2));

        ksp.delete(1);

        assertFail(SPFail.KEY_NOT_FOUND, ksp.read(1));
        assertFalse(ksp.containsKey(1));
        assertArrayEquals(bytes(10, 2), data(ksp.read(2)));
    }

    @Test
    public void deleteUnknownKeyReturnsKeyNotFound() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        assertFail(SPFail.KEY_NOT_FOUND, ksp.delete(2));
    }

    @Test
    public void deleteKeyOnEmptyStorageReturnsKeyNotFound() throws Exception {
        assertFail(SPFail.KEY_NOT_FOUND, newStorage().delete(1));
    }

    @Test
    public void deleteSameKeyTwiceReturnsKeyNotFoundSecondTime() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));

        assertEquals(SPSuccess.DELETE, ksp.delete(1).getStatus());
        assertFail(SPFail.KEY_NOT_FOUND, ksp.delete(1));
    }

    @Test
    public void deleteNullKeyFails() throws Exception {
        assertFail(SPFail.NULL_KEY, newStorage().delete(null));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void deleteKeyOfWrongTypeFails() throws Exception {
        PBKeyStorageProvider raw = newStorage();
        raw.write(1, bytes(10, 1));

        assertFail(SPFail.KEY_TYPE_MISMATCH, raw.delete("1"));
        assertTrue(raw.containsKey(1));
    }

    @Test
    public void deleteKeyPersistsAcrossReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        ksp.write(1, bytes(10, 1));
        ksp.write(2, bytes(10, 2));
        ksp.delete(1);

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertFail(SPFail.KEY_NOT_FOUND, reopened.read(1));
        assertArrayEquals(bytes(10, 2), data(reopened.read(2)));
        assertEquals(1, dataArray(reopened.read()).length);
    }

    @Test
    public void deletedKeyCanBeWrittenAgain() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        ksp.write(1, bytes(10, 1));
        ksp.delete(1);

        assertSuccess(ksp.write(1, bytes(10, 2)));
        assertArrayEquals(bytes(10, 2), data(ksp.read(1)));
        assertArrayEquals(bytes(10, 2), data(track(new PBKeyStorageProvider<>(released(path), Integer.class)).read(1)));
    }

    @Test
    public void deletingLastKeyLeavesNothingToRead() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        ksp.delete(1);

        assertFail(SPFail.NULL_READ, ksp.read());
    }

    @Test
    public void deleteOverwrittenKeyRemovesLatestValue() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        ksp.write(1, bytes(10, 1));
        ksp.write(1, bytes(10, 2));
        ksp.delete(1);

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertFail(SPFail.KEY_NOT_FOUND, reopened.read(1));
        assertFail(SPFail.NULL_READ, reopened.read());
    }

    @Test
    public void deleteManyKeysAcrossPages() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 200; i++) {
            ksp.write(i, bytes(50, i));
        }
        for (int i = 0; i < 200; i += 2) {
            assertEquals(SPSuccess.DELETE, ksp.delete(i).getStatus());
        }

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 200; i++) {
            if (i % 2 == 0) {
                assertFail(SPFail.KEY_NOT_FOUND, reopened.read(i));
            } else {
                assertArrayEquals(bytes(50, i), data(reopened.read(i)));
            }
        }
        assertEquals(100, dataArray(reopened.read()).length);
    }

    // ---- delete() ----------------------------------------------------------

    @Test
    public void deleteAllReturnsDelete() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        ksp.write(1, bytes(10, 1));
        assertEquals(SPSuccess.DELETE, ksp.delete().getStatus());
    }

    @Test
    public void deleteAllOnEmptyStorageSucceeds() throws Exception {
        assertEquals(SPSuccess.DELETE, newStorage().delete().getStatus());
    }

    @Test
    public void deleteAllRemovesEveryKeyAndRecord() throws Exception {
        PBKeyStorageProvider<Integer> ksp = newStorage();
        for (int i = 0; i < 10; i++) {
            ksp.write(i, bytes(10, i));
        }

        ksp.delete();

        assertFail(SPFail.NULL_READ, ksp.read());
        for (int i = 0; i < 10; i++) {
            assertFail(SPFail.KEY_NOT_FOUND, ksp.read(i));
            assertFalse(ksp.containsKey(i));
        }
    }

    @Test
    public void deleteAllTruncatesFileToHeader() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 300; i++) {
            ksp.write(i, bytes(50, 1));
        }

        ksp.delete();

        assertEquals(FILE_HEADER, fileLength(path));
    }

    @Test
    public void deleteAllKeepsKeyType() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        ksp.write(1, bytes(10, 1));
        ksp.delete();

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertEquals(Integer.class, reopened.getKeyType());
        assertFail(SPFail.NULL_READ, reopened.read());
        assertThrows(IllegalStateException.class, () -> track(new PBKeyStorageProvider<>(released(path), String.class)));
    }

    @Test
    public void writeAfterDeleteAll() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        ksp.write(1, bytes(10, 1));
        ksp.delete();

        assertSuccess(ksp.write(2, bytes(10, 2)));

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertFail(SPFail.KEY_NOT_FOUND, reopened.read(1));
        assertArrayEquals(bytes(10, 2), data(reopened.read(2)));
        assertEquals(lengthForPages(1), fileLength(path));
    }

    @Test
    public void deleteAllDiscardsDeletedSlots() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 10; i++) {
            ksp.write(i, bytes(10, i));
        }
        ksp.delete(3);
        ksp.delete();

        // Slots freed before delete() pointed into the old pages; writing must not reuse them.
        assertSuccess(ksp.write(100, bytes(10, 7)));
        assertEquals(1, dataArray(ksp.read()).length);
        assertArrayEquals(bytes(10, 7), data(track(new PBKeyStorageProvider<>(released(path), Integer.class)).read(100)));
    }
}
