package PBStorageTests.Recovery;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;

/*
Operations that fail part-way (an exception after some of their changes were
made) leave the storage exactly as before the call -- in this instance, and
after reopening. And compact() recovers its index in place.
*/
public class AtomicityTests {
    @TempDir
    Path dir;

    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    /*
    Throws from inside an operation, after it has already changed things:
    onSlotFreed runs after a slot was marked deleted (delete, overwrite);
    takeFreeSlot before a record is placed; onCleared after the data file was
    erased.
    */
    static final class Faulty extends PBKeyStorageProvider<Integer> {
        volatile boolean failFree;
        volatile boolean failTake;
        volatile boolean failClear;

        Faulty(String path) throws Exception {
            super(path, Integer.class);
        }

        @Override
        protected void onSlotFreed(RecordID recordID, Slot slot) throws Exception {
            super.onSlotFreed(recordID, slot);
            if (this.failFree) {
                throw new IOException("injected: slot freed");
            }
        }

        @Override
        protected RecordID takeFreeSlot(short recordLength) throws Exception {
            if (this.failTake) {
                throw new IOException("injected: take free slot");
            }
            return super.takeFreeSlot(recordLength);
        }

        @Override
        protected void onCleared() throws Exception {
            super.onCleared();
            if (this.failClear) {
                throw new IOException("injected: cleared");
            }
        }
    }

    private Faulty faulty(String path, int keys) throws Exception {
        Faulty storage = track(new Faulty(released(path)));
        for (int k = 0; k < keys; k++) {
            assertSuccess(storage.write(k, bytes(50, k)));
        }
        return storage;
    }

    private static void assertKeys(PBKeyStorageProvider<Integer> storage, int keys, int... missing) {
        java.util.Set<Integer> gone = new java.util.HashSet<>();
        for (int m : missing) {
            gone.add(m);
        }
        for (int k = 0; k < keys; k++) {
            if (gone.contains(k)) {
                assertFail(SPFail.KEY_NOT_FOUND, storage.read(k));
            } else {
                assertArrayEquals(bytes(50, k), data(storage.read(k)), "key " + k);
            }
        }
        assertEquals(keys - gone.size(), dataArray(storage.read()).length, "read() agrees with the index");
    }

    private PBKeyStorageProvider<Integer> reopen(String path) throws Exception {
        return track(new PBKeyStorageProvider<>(released(path), Integer.class));
    }

    // ---- part-way failures ------------------------------------------------------------

    @Test
    public void failedDeleteKeepsTheKeyAndItsRecord() throws Exception {
        String path = storagePath(dir, "ksp");
        Faulty storage = faulty(path, 40);
        long dead = storage.getDeadBytes();

        storage.failFree = true;
        SPResult result = storage.delete(5);
        assertFail(SPFail.DELETE_ERROR, result);
        assertTrue(result.getError().getMessage().contains("injected"));

        assertKeys(storage, 40);
        assertEquals(dead, storage.getDeadBytes());

        storage.failFree = false;
        assertSuccess(storage.delete(5));
        assertKeys(storage, 40, 5);
        assertKeys(reopen(path), 40, 5);
    }

    @Test
    public void failedOverwriteKeepsTheOldValue() throws Exception {
        String path = storagePath(dir, "ksp");
        Faulty storage = faulty(path, 40);
        long size = storage.getFileSize();

        storage.failFree = true;
        assertFail(SPFail.WRITE_ERROR, storage.write(7, bytes(80, 99)));

        assertKeys(storage, 40);
        assertEquals(size, storage.getFileSize(), "the new record's space is given back");
        assertKeys(reopen(path), 40);
    }

    @Test
    public void failedNewKeyLeavesNoTrace() throws Exception {
        String path = storagePath(dir, "ksp");
        Faulty storage = faulty(path, 40);
        long size = storage.getFileSize();

        storage.failTake = true;
        assertFail(SPFail.WRITE_ERROR, storage.write(100, bytes(50, 1)));
        storage.failTake = false;

        assertFail(SPFail.KEY_NOT_FOUND, storage.read(100));
        assertEquals(size, storage.getFileSize());
        assertKeys(reopen(path), 40);
    }

    @Test
    public void failedEraseKeepsEverything() throws Exception {
        String path = storagePath(dir, "ksp");
        Faulty storage = faulty(path, 40);

        storage.failClear = true;
        assertFail(SPFail.DELETE_ERROR, storage.delete());
        assertFail(SPFail.WRITE_ERROR, storage.write(bytes(20, 1)));
        storage.failClear = false;

        assertKeys(storage, 40);
        assertKeys(reopen(path), 40);
    }

    @Test
    public void manyFailuresMixedWithSuccessesStayConsistent() throws Exception {
        String path = storagePath(dir, "ksp");
        Faulty storage = faulty(path, 0);
        java.util.Map<Integer, byte[]> expected = new java.util.HashMap<>();
        java.util.Random random = new java.util.Random(5);

        for (int i = 0; i < 3_000; i++) {
            int k = random.nextInt(200);
            boolean fail = random.nextInt(5) == 0;
            storage.failFree = fail;
            storage.failTake = fail && random.nextBoolean();
            if (random.nextInt(3) == 0) {
                SPResult r = storage.delete(k);
                if (r.hasSucceeded()) {
                    expected.remove(k);
                }
            } else {
                byte[] v = bytes(10 + random.nextInt(200), i);
                if (storage.write(k, v).hasSucceeded()) {
                    expected.put(k, v);
                }
            }
        }
        storage.failFree = false;
        storage.failTake = false;

        java.util.function.Consumer<PBKeyStorageProvider<Integer>> matches = s -> {
            for (int k = 0; k < 200; k++) {
                if (expected.containsKey(k)) {
                    assertArrayEquals(expected.get(k), data(s.read(k)), "key " + k);
                } else {
                    assertFail(SPFail.KEY_NOT_FOUND, s.read(k));
                }
            }
        };
        matches.accept(storage);
        matches.accept(reopen(path));
    }

    // ---- compact() recovers its index in place -------------------------------------

    /*
    A key whose decoding fails while failDecodes > 0. Decoding only happens
    when the index is rebuilt (on open, after compact(), reconstructKeyIndex).
    */
    static final class FlakyKey implements Serializable {
        private static final long serialVersionUID = 1L;
        static volatile int failDecodes;

        final int id;

        FlakyKey(int id) {
            this.id = id;
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            if (failDecodes > 0) {
                failDecodes--;
                throw new IOException("injected: key decode");
            }
        }
    }

    private PBKeyStorageProvider<FlakyKey> flakyStorage(String path) throws Exception {
        FlakyKey.failDecodes = 0;
        PBKeyStorageProvider<FlakyKey> storage = track(new PBKeyStorageProvider<>(released(path), FlakyKey.class));
        for (int k = 0; k < 300; k++) {
            assertSuccess(storage.write(new FlakyKey(k), bytes(40, k)));
        }
        for (int k = 0; k < 300; k += 3) {
            assertSuccess(storage.delete(new FlakyKey(k)));
        }
        return storage;
    }

    private static void assertFlakyKeys(PBKeyStorageProvider<FlakyKey> storage) {
        for (int k = 0; k < 300; k++) {
            SPResult r = storage.read(new FlakyKey(k));
            if (k % 3 == 0) {
                assertFail(SPFail.KEY_NOT_FOUND, r);
            } else {
                assertArrayEquals(bytes(40, k), data(r), "key " + k);
            }
        }
    }

    @Test
    public void compactRetriesAFailedIndexRebuild() throws Exception {
        PBKeyStorageProvider<FlakyKey> storage = flakyStorage(storagePath(dir, "flaky"));

        FlakyKey.failDecodes = 1;     // the first rebuild fails, the retry works
        SPResult result = storage.compact();

        assertEquals(SPSuccess.COMPACT, result.getStatus(), "recovered in place: " + result);
        assertEquals(0, storage.getDeadBytes());
        assertFlakyKeys(storage);
    }

    @Test
    public void indexThatCannotBeRebuiltIsRebuiltByTheNextCall() throws Exception {
        String path = storagePath(dir, "flaky");
        PBKeyStorageProvider<FlakyKey> storage = flakyStorage(path);

        FlakyKey.failDecodes = 1_000_000;   // every rebuild fails for now
        SPResult result = storage.compact();
        assertFail(SPFail.COMPACT_ERROR, result);
        assertTrue(result.getError().getMessage().contains("injected"));
        assertEquals(1, result.getError().getSuppressed().length, "the retry's failure is attached");

        // Never a wrong answer from a stale index: an error that says why.
        SPResult read = storage.read(new FlakyKey(1));
        assertFail(SPFail.READ_ERROR, read);
        assertTrue(read.getError().getMessage().contains("injected"));
        assertFalse(storage.containsKey(new FlakyKey(1)));
        assertFail(SPFail.WRITE_ERROR, storage.write(new FlakyKey(1), bytes(5, 5)));

        FlakyKey.failDecodes = 0;           // the problem goes away
        assertFlakyKeys(storage);            // the first call rebuilds, then everything is there
        assertSuccess(storage.write(new FlakyKey(1000), bytes(5, 5)));
        assertFlakyKeys(track(new PBKeyStorageProvider<>(released(path), FlakyKey.class)));
    }

    @Test
    public void interruptedReconstructKeyIndexIsRedoneOnOpen() throws Exception {
        String path = storagePath(dir, "flaky");
        PBKeyStorageProvider<FlakyKey> storage = flakyStorage(path);

        FlakyKey.failDecodes = 50;   // fails part-way through the rebuild
        assertThrows(IOException.class, storage::reconstructKeyIndex);
        // A crash now: same data file (same id), index half-built.
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());

        FlakyKey.failDecodes = 0;
        assertFlakyKeys(track(new PBKeyStorageProvider<>(released(image), FlakyKey.class)));
    }

    @Test
    public void rebuildInterruptedByACrashIsRedoneOnOpen() throws Exception {
        String path = storagePath(dir, "flaky");
        PBKeyStorageProvider<FlakyKey> storage = flakyStorage(path);

        FlakyKey.failDecodes = 1_000_000;
        assertFail(SPFail.COMPACT_ERROR, storage.compact());
        // A crash now: the index on disk is half-built and marked as matching no data file.
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());

        FlakyKey.failDecodes = 0;
        assertFlakyKeys(track(new PBKeyStorageProvider<>(released(image), FlakyKey.class)));
    }
}
