package PBStorageTests.PBSP;

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
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

public class PBSPDeleteTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    @Test
    public void deleteReturnsDelete() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(10, 1));

        SPResult status = sp.delete();

        assertEquals(SPSuccess.DELETE, status.getStatus());
        assertSuccess(status);
    }

    @Test
    public void deleteOnEmptyStorageSucceeds() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        assertEquals(SPSuccess.DELETE, sp.delete().getStatus());
    }

    @Test
    public void deleteTwiceSucceeds() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(10, 1));

        assertEquals(SPSuccess.DELETE, sp.delete().getStatus());
        assertEquals(SPSuccess.DELETE, sp.delete().getStatus());
        assertFail(SPFail.NULL_READ, sp.read());
    }

    @Test
    public void deleteRemovesData() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(10, 1));
        sp.delete();

        assertFail(SPFail.NULL_READ, sp.read());
    }

    @Test
    public void deleteTruncatesFileToHeader() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = track(new PBStorageProvider(released(path)));
        sp.write(bytes(MAX_RECORD, 1));
        sp.delete();

        assertEquals(FILE_HEADER, fileLength(path));
    }

    @Test
    public void deletePersistsAcrossReopen() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = track(new PBStorageProvider(released(path)));
        sp.write(bytes(10, 1));
        sp.delete();

        assertFail(SPFail.NULL_READ, track(new PBStorageProvider(released(path))).read());
    }

    @Test
    public void writeAfterDeleteAndReopen() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = track(new PBStorageProvider(released(path)));
        sp.write(bytes(10, 1));
        sp.delete();

        PBStorageProvider reopened = track(new PBStorageProvider(released(path)));
        assertSuccess(reopened.write(bytes(10, 2)));
        assertArrayEquals(bytes(10, 2), data(track(new PBStorageProvider(released(path))).read()));
    }

    @Test
    public void deleteOnKeyedFileRemovesRecordsButKeepsKeyType() throws Exception {
        String path = storagePath(dir, "keyed");
        PBKeyStorageProvider<Integer> keyed = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 5; i++) {
            keyed.write(i, bytes(10, i));
        }

        assertEquals(SPSuccess.DELETE, track(new PBStorageProvider(released(path))).delete().getStatus());

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertFail(SPFail.NULL_READ, reopened.read());
        assertFail(SPFail.KEY_NOT_FOUND, reopened.read(0));
        assertThrows(IllegalStateException.class, () -> track(new PBKeyStorageProvider<>(released(path), String.class)));
    }
}
