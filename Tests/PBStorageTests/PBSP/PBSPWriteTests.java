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
import Utility.Serialization.Serializer;

public class PBSPWriteTests {
    @TempDir
    Path dir;

    @Test
    public void writeReturnsWriteWithWrittenData() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        byte[] payload = { 1, 2, 3 };

        PBStatus status = sp.write(payload);

        assertEquals(PBSuccess.WRITE, status);
        assertSuccess(status);
        assertArrayEquals(payload, data(status));
        assertNull(dataArray(status));
    }

    @Test
    public void writeNullDataFails() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        assertFail(PBFail.NULL_WRITE, sp.write(null));
    }

    @Test
    public void writeEmptyDataFails() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        assertFail(PBFail.NULL_WRITE, sp.write(new byte[0]));
    }

    @Test
    public void writeMaxRecordSizeSucceeds() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = new PBStorageProvider(path);
        byte[] payload = bytes(MAX_RECORD, 7);

        assertSuccess(sp.write(payload));
        assertArrayEquals(payload, data(sp.read()));
        assertEquals(lengthForPages(1), fileLength(path));
    }

    @Test
    public void writeOneByteOverMaxRecordSizeFails() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        assertFail(PBFail.DATA_TOO_LARGE, sp.write(bytes(MAX_RECORD + 1, 7)));
    }

    @Test
    public void writeSingleByte() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        assertSuccess(sp.write(new byte[] { 42 }));
        assertArrayEquals(new byte[] { 42 }, data(sp.read()));
    }

    @Test
    public void secondWriteReplacesFirst() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(100, 1));
        sp.write(bytes(10, 2));

        assertArrayEquals(bytes(10, 2), data(sp.read()));
    }

    @Test
    public void failedWriteKeepsExistingData() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));

        sp.write(null);
        sp.write(new byte[0]);
        sp.write(bytes(MAX_RECORD + 1, 2));

        assertArrayEquals(bytes(10, 1), data(sp.read()));
    }

    @Test
    public void writePersistsAcrossReopen() throws Exception {
        String path = storagePath(dir, "sp");
        new PBStorageProvider(path).write(Serializer.serialize("persisted"));

        PBStorageProvider reopened = new PBStorageProvider(path);
        assertEquals("persisted", Serializer.deserialize(data(reopened.read())));
    }

    @Test
    public void writeReplacingLargerDataShrinksBackToOnePage() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = new PBStorageProvider(path);
        sp.write(bytes(MAX_RECORD, 1));
        sp.write(bytes(5, 2));

        assertEquals(lengthForPages(1), fileLength(path));
        assertArrayEquals(bytes(5, 2), data(new PBStorageProvider(path).read()));
    }

    @Test
    public void writeAfterDelete() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));
        sp.delete();

        assertSuccess(sp.write(bytes(10, 2)));
        assertArrayEquals(bytes(10, 2), data(sp.read()));
    }

    @Test
    public void writeOnKeyedFileReplacesAllRecordsAndKeepsKeyType() throws Exception {
        String path = storagePath(dir, "keyed");
        PBKeyStorageProvider<Integer> keyed = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 5; i++) {
            keyed.write(i, bytes(10, i));
        }

        assertSuccess(new PBStorageProvider(path).write(bytes(3, 9)));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(0));
        byte[][] records = dataArray(reopened.read());
        assertEquals(1, records.length);
        assertArrayEquals(bytes(3, 9), records[0]);
        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, String.class));
    }

    @Test
    public void writeKeepsBinaryContentIntact() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        sp.write(payload);
        assertArrayEquals(payload, data(sp.read()));
    }
}
