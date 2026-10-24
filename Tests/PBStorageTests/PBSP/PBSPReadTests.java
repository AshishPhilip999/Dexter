package PBStorageTests.PBSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.Serialization.Serializer;

public class PBSPReadTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    static final class Person implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final int age;

        Person(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    @Test
    public void readOnNewStorageReturnsNullRead() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        assertFail(SPFail.NULL_READ, sp.read());
    }

    @Test
    public void readOnReopenedEmptyStorageReturnsNullRead() throws Exception {
        String path = storagePath(dir, "sp");
        track(new PBStorageProvider(released(path)));
        assertFail(SPFail.NULL_READ, track(new PBStorageProvider(released(path))).read());
    }

    @Test
    public void readReturnsReadWithWrittenData() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        byte[] payload = bytes(50, 3);
        sp.write(payload);

        SPResult status = sp.read();

        assertEquals(SPSuccess.READ, status.getStatus());
        assertSuccess(status);
        assertArrayEquals(payload, data(status));
        assertNull(dataArray(status));
    }

    @Test
    public void readDeserializesBackToObject() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(Serializer.serialize(new Person("Ashish", 30)));

        Person person = Serializer.deserialize(data(sp.read()));
        assertEquals("Ashish", person.name);
        assertEquals(30, person.age);
    }

    @Test
    public void readAfterReopen() throws Exception {
        String path = storagePath(dir, "sp");
        track(new PBStorageProvider(released(path))).write(bytes(20, 4));

        assertArrayEquals(bytes(20, 4), data(track(new PBStorageProvider(released(path))).read()));
    }

    @Test
    public void repeatedReadsReturnSameData() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(20, 4));

        assertArrayEquals(bytes(20, 4), data(sp.read()));
        assertArrayEquals(bytes(20, 4), data(sp.read()));
    }

    @Test
    public void readReturnsLatestOfSeveralWrites() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(10, 1));
        sp.write(bytes(20, 2));
        sp.write(bytes(30, 3));

        assertArrayEquals(bytes(30, 3), data(sp.read()));
    }

    @Test
    public void readMaxRecordSize() throws Exception {
        String path = storagePath(dir, "sp");
        track(new PBStorageProvider(released(path))).write(bytes(MAX_RECORD, 8));
        assertArrayEquals(bytes(MAX_RECORD, 8), data(track(new PBStorageProvider(released(path))).read()));
    }

    @Test
    public void readAfterDeleteReturnsNullRead() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(10, 1));
        sp.delete();
        assertFail(SPFail.NULL_READ, sp.read());
    }

    @Test
    public void readOnKeyedFileReturnsFirstLiveRecordWithoutKeyBytes() throws Exception {
        String path = storagePath(dir, "keyed");
        PBKeyStorageProvider<Integer> keyed = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        keyed.write(1, bytes(10, 1));
        keyed.write(2, bytes(10, 2));
        keyed.delete(1);

        assertArrayEquals(bytes(10, 2), data(track(new PBStorageProvider(released(path))).read()));
    }
}
