package PBStorageTests.PBSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

public class PBSPDeleteTests {
    @TempDir
    Path dir;

    @Test
    public void deleteReturnsDelete() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));

        PBStatus status = sp.delete();

        assertEquals(PBSuccess.DELETE, status);
        assertSuccess(status);
    }

    @Test
    public void deleteOnEmptyStorageSucceeds() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        assertEquals(PBSuccess.DELETE, sp.delete());
    }

    @Test
    public void deleteTwiceSucceeds() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));

        assertEquals(PBSuccess.DELETE, sp.delete());
        assertEquals(PBSuccess.DELETE, sp.delete());
        assertFail(PBFail.NULL_READ, sp.read());
    }

    @Test
    public void deleteRemovesData() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));
        sp.delete();

        assertFail(PBFail.NULL_READ, sp.read());
    }

    @Test
    public void deleteTruncatesFileToHeader() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = new PBStorageProvider(path);
        sp.write(bytes(MAX_RECORD, 1));
        sp.delete();

        assertEquals(FILE_HEADER, fileLength(path));
    }

    @Test
    public void deletePersistsAcrossReopen() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = new PBStorageProvider(path);
        sp.write(bytes(10, 1));
        sp.delete();

        assertFail(PBFail.NULL_READ, new PBStorageProvider(path).read());
    }

    @Test
    public void writeAfterDeleteAndReopen() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = new PBStorageProvider(path);
        sp.write(bytes(10, 1));
        sp.delete();

        PBStorageProvider reopened = new PBStorageProvider(path);
        assertSuccess(reopened.write(bytes(10, 2)));
        assertArrayEquals(bytes(10, 2), data(new PBStorageProvider(path).read()));
    }

    @Test
    public void deleteOnKeyedFileRemovesRecordsButKeepsKeyType() throws Exception {
        String path = storagePath(dir, "keyed");
        PBKeyStorageProvider<Integer> keyed = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 5; i++) {
            keyed.write(i, bytes(10, i));
        }

        assertEquals(PBSuccess.DELETE, new PBStorageProvider(path).delete());

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.NULL_READ, reopened.read());
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(0));
        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, String.class));
    }
}
