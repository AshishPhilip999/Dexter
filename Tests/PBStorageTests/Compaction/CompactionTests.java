package PBStorageTests.Compaction;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.Constants.Extension;
import Utility.StorageProviders.PageBasedStorageProvider.Index.IndexFile;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

/*
Dead byte tracking, cutting empty pages off the end of the file, and compact().
*/
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class CompactionTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    private static final int VALUE_SIZE = 100;
    private static final int RECORD = INT_KEY_BYTES + VALUE_SIZE;
    private static final int RECORDS_PER_PAGE = (PAGE - PAGE_HEADER) / (SLOT_ENTRY + RECORD);

    /*
    Header layout: ... marker(4) at 236, deadBytes(8) at 240, fileId(8) at 248.
    */
    private static final int STATS_MARKER_OFFSET = FILE_HEADER - 20;
    private static final int FILE_ID_OFFSET = FILE_HEADER - 8;

    private PBKeyStorageProvider<Integer> open(String path) throws Exception {
        return track(new PBKeyStorageProvider<>(released(path), Integer.class));
    }

    private static byte[] value(int key, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(4, length));
        buffer.putInt(key);
        while (buffer.hasRemaining()) {
            buffer.put((byte) key);
        }
        return buffer.array();
    }

    private PBKeyStorageProvider<Integer> withKeys(String path, int count) throws Exception {
        PBKeyStorageProvider<Integer> ksp = open(path);
        for (int k = 0; k < count; k++) {
            assertSuccess(ksp.write(k, value(k, VALUE_SIZE)));
        }
        return ksp;
    }

    private static int pagesFor(int records) {
        return (records + RECORDS_PER_PAGE - 1) / RECORDS_PER_PAGE;
    }

    /*
    Makes the data file look like one written before dead bytes were tracked.
    */
    private static void eraseStatsFromHeader(String path) throws Exception {
        released(path);   // closed first, so its journal can't write the header back
        try (RandomAccessFile file = new RandomAccessFile(datFile(path), "rw")) {
            file.seek(STATS_MARKER_OFFSET);
            file.write(new byte[12]);
        }
    }

    private static long fileIdOf(String path) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(datFile(path), "r")) {
            file.seek(FILE_ID_OFFSET);
            return file.readLong();
        }
    }

    // ---- dead bytes ------------------------------------------------------------

    @Test
    public void newStorageHasNoDeadBytes() throws Exception {
        PBKeyStorageProvider<Integer> ksp = withKeys(storagePath(dir, "ksp"), 10);
        assertEquals(0, ksp.getDeadBytes());
        assertEquals(0.0, ksp.getFragmentation());
    }

    @Test
    public void deleteCountsTheWholeRecord() throws Exception {
        PBKeyStorageProvider<Integer> ksp = withKeys(storagePath(dir, "ksp"), 3);
        assertSuccess(ksp.delete(1));
        assertEquals(RECORD, ksp.getDeadBytes());
    }

    @Test
    public void overwriteCountsTheOldVersion() throws Exception {
        PBKeyStorageProvider<Integer> ksp = withKeys(storagePath(dir, "ksp"), 3);
        assertSuccess(ksp.write(1, value(1, 50)));
        assertEquals(RECORD, ksp.getDeadBytes());
    }

    @Test
    public void reusingASlotLeavesOnlyItsUnusedTail() throws Exception {
        PBKeyStorageProvider<Integer> ksp = withKeys(storagePath(dir, "ksp"), 3);
        assertSuccess(ksp.delete(1));
        assertSuccess(ksp.write(7, value(7, 60)));

        assertEquals(RECORD - (INT_KEY_BYTES + 60), ksp.getDeadBytes());

        assertSuccess(ksp.delete(7));
        assertEquals(RECORD, ksp.getDeadBytes(), "deleted again: the whole slot is dead");
    }

    @Test
    public void deadBytesSurviveReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 10);
        ksp.delete(2);
        ksp.delete(5);

        assertEquals(2L * RECORD, open(path).getDeadBytes());
    }

    @Test
    public void fragmentationIsDeadShareOfThePages() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, RECORDS_PER_PAGE * 2);
        for (int k = 0; k < RECORDS_PER_PAGE; k++) {
            ksp.delete(k);
        }

        double expected = (double) (RECORDS_PER_PAGE * RECORD) / (2.0 * PAGE);
        assertEquals(expected, ksp.getFragmentation(), 1e-9);
    }

    @Test
    public void olderFilesGetTheirDeadBytesCountedOnOpen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 10);
        ksp.delete(3);
        ksp.delete(4);
        ksp.write(20, value(20, 30));            // reuses a slot: tail of 70 bytes dead
        long expected = ksp.getDeadBytes();
        assertEquals(RECORD + (VALUE_SIZE - 30), expected);

        eraseStatsFromHeader(path);
        assertEquals(expected, open(path).getDeadBytes(), "worked out from the pages");
        assertEquals(expected, open(path).getDeadBytes(), "and stored back in the header");
    }

    @Test
    public void runningCountMatchesARecountAfterRandomWork() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        Random random = new Random(7);
        for (int i = 0; i < 5_000; i++) {
            int k = random.nextInt(400);
            if (random.nextInt(3) == 0) {
                ksp.delete(k);
            } else {
                ksp.write(k, value(k, 4 + random.nextInt(300)));
            }
        }
        long counted = ksp.getDeadBytes();

        eraseStatsFromHeader(path);
        assertEquals(counted, open(path).getDeadBytes());
    }

    // ---- cutting empty pages off the end -------------------------------------------

    @Test
    public void deletingTheLastPagesRecordsShrinksTheFile() throws Exception {
        String path = storagePath(dir, "ksp");
        int total = RECORDS_PER_PAGE * 2 + 3;
        PBKeyStorageProvider<Integer> ksp = withKeys(path, total);
        assertEquals(lengthForPages(3), fileLength(path));

        ksp.delete(total - 1);
        ksp.delete(total - 2);
        assertEquals(lengthForPages(3), fileLength(path), "page still has a live record");

        ksp.delete(total - 3);
        assertEquals(lengthForPages(2), fileLength(path));
        assertEquals(0, ksp.getDeadBytes(), "the cut-off slots are no longer counted");
    }

    @Test
    public void cuttingContinuesThroughEarlierEmptyPages() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, RECORDS_PER_PAGE * 3);

        for (int k = RECORDS_PER_PAGE; k < RECORDS_PER_PAGE * 2; k++) {
            ksp.delete(k);                        // empties page 1 (not the last)
        }
        assertEquals(lengthForPages(3), fileLength(path));

        for (int k = RECORDS_PER_PAGE * 2; k < RECORDS_PER_PAGE * 3; k++) {
            ksp.delete(k);
        }
        assertEquals(lengthForPages(1), fileLength(path), "pages 2 and 1 are both cut off");
        assertEquals(0, ksp.getDeadBytes());
        for (int k = 0; k < RECORDS_PER_PAGE; k++) {
            assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)));
        }
    }

    @Test
    public void deletingEverythingLeavesJustTheHeader() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 100);
        for (int k = 0; k < 100; k++) {
            assertSuccess(ksp.delete(k));
        }

        assertEquals(FILE_HEADER, fileLength(path));
        assertEquals(0, ksp.getDeadBytes());
        assertFail(PBFail.NULL_READ, ksp.read());

        assertSuccess(ksp.write(5, value(5, VALUE_SIZE)));
        assertArrayEquals(value(5, VALUE_SIZE), data(open(path).read(5)));
    }

    @Test
    public void slotsOfCutPagesAreNeverReused() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, RECORDS_PER_PAGE + 5);
        for (int k = RECORDS_PER_PAGE; k < RECORDS_PER_PAGE + 5; k++) {
            ksp.delete(k);
        }
        assertEquals(lengthForPages(1), fileLength(path));

        // Same-sized records would fit the forgotten slots; they must go to new space.
        for (int k = 1000; k < 1000 + RECORDS_PER_PAGE * 2; k++) {
            assertSuccess(ksp.write(k, value(k, VALUE_SIZE)));
        }
        for (int k = 1000; k < 1000 + RECORDS_PER_PAGE * 2; k++) {
            assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)), "key " + k);
        }
        for (int k = 0; k < RECORDS_PER_PAGE; k++) {
            assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)), "key " + k);
        }
        assertEquals(RECORDS_PER_PAGE * 3, dataArray(open(path).read()).length);
    }

    static final class Probe extends PBKeyStorageProvider<Integer> {
        Probe(String path) throws Exception {
            super(path, Integer.class);
        }

        int cachedDataPages() {
            return this.getCachedPageCount();
        }
    }

    @Test
    public void cutPagesLeaveTheCache() throws Exception {
        String path = storagePath(dir, "ksp");
        Probe ksp = track(new Probe(released(path)));
        for (int k = 0; k < RECORDS_PER_PAGE * 4; k++) {
            ksp.write(k, value(k, VALUE_SIZE));
        }
        assertEquals(4, ksp.cachedDataPages());

        for (int k = RECORDS_PER_PAGE; k < RECORDS_PER_PAGE * 4; k++) {
            ksp.delete(k);
        }
        assertEquals(1, ksp.cachedDataPages());
    }

    // ---- compact() -------------------------------------------------------------

    @Test
    public void compactReturnsCompact() throws Exception {
        PBResult result = withKeys(storagePath(dir, "ksp"), 5).compact();
        assertEquals(PBSuccess.COMPACT, result.getStatus());
        assertTrue(result.hasSucceeded());
    }

    @Test
    public void compactShrinksTheFileAndKeepsTheData() throws Exception {
        String path = storagePath(dir, "ksp");
        int total = 2_000;
        PBKeyStorageProvider<Integer> ksp = withKeys(path, total);
        for (int k = 0; k < total; k += 2) {
            ksp.delete(k);
        }
        assertTrue(ksp.getFragmentation() > 0.4);
        assertEquals(lengthForPages(pagesFor(total)), fileLength(path));

        assertSuccess(ksp.compact());

        assertEquals(lengthForPages(pagesFor(total / 2)), fileLength(path));
        assertEquals(fileLength(path), ksp.getFileSize());
        assertEquals(0, ksp.getDeadBytes());
        assertEquals(0.0, ksp.getFragmentation());
        for (int k = 0; k < total; k++) {
            if (k % 2 == 0) {
                assertFail(PBFail.KEY_NOT_FOUND, ksp.read(k));
                assertFalse(ksp.containsKey(k));
            } else {
                assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)), "key " + k);
            }
        }
    }

    @Test
    public void compactKeepsTheRecordOrder() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        Random random = new Random(3);
        for (int i = 0; i < 3_000; i++) {
            int k = random.nextInt(500);
            if (random.nextInt(4) == 0) {
                ksp.delete(k);
            } else {
                ksp.write(k, value(k, 4 + random.nextInt(200)));
            }
        }
        byte[][] before = dataArray(ksp.read());

        assertSuccess(ksp.compact());

        assertArrayEquals(before, dataArray(ksp.read()));
        assertArrayEquals(before, dataArray(open(path).read()), "and after reopening");
    }

    @Test
    public void compactRemovesTheUnusedTailOfReusedSlots() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        ksp.write(1, value(1, 3000));
        ksp.write(2, value(2, 10));
        ksp.delete(1);
        ksp.write(3, value(3, 10));               // reuses the 3005-byte slot
        assertEquals(3005 - (INT_KEY_BYTES + 10), ksp.getDeadBytes());

        assertSuccess(ksp.compact());

        assertEquals(0, ksp.getDeadBytes());
        assertArrayEquals(value(2, 10), data(ksp.read(2)));
        assertArrayEquals(value(3, 10), data(ksp.read(3)));
        assertEquals(0, open(path).getDeadBytes());
    }

    @Test
    public void indexBelongsToTheNewDataFile() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 300);
        long oldId = fileIdOf(path);

        assertSuccess(ksp.compact());

        long newId = fileIdOf(path);
        assertNotEquals(oldId, newId, "the packed file is a new file");
        IndexFile index = new IndexFile(indexFile(path).getPath().replace(".pbi", ""), Extension.PBI);
        assertEquals(newId, index.getDataFileId());
    }

    @Test
    public void compactShrinksTheIndexFile() throws Exception {
        String path = storagePath(dir, "ksp");
        int total = 6_000;
        PBKeyStorageProvider<Integer> ksp = withKeys(path, total);
        for (int k = 0; k < total - 50; k++) {
            ksp.delete(k);
        }
        checkpointIfOpen(path);
        long indexBefore = indexFile(path).length();

        assertSuccess(ksp.compact());

        assertTrue(indexFile(path).length() < indexBefore / 10,
            "index " + indexBefore + " -> " + indexFile(path).length());
        for (int k = total - 50; k < total; k++) {
            assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)));
        }
    }

    @Test
    public void compactKeepsTheKeyType() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 5).compact();
        assertThrows(IllegalStateException.class, () -> track(new PBKeyStorageProvider<>(released(path), String.class)));
    }

    @Test
    public void compactOnAnEmptyStorage() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);

        assertSuccess(ksp.compact());
        assertEquals(FILE_HEADER, fileLength(path));
        assertFail(PBFail.NULL_READ, ksp.read());

        assertSuccess(ksp.write(1, value(1, 10)));
        assertArrayEquals(value(1, 10), data(ksp.read(1)));
    }

    @Test
    public void compactKeylessStorage() throws Exception {
        String path = storagePath(dir, "sp");
        PBStorageProvider sp = track(new PBStorageProvider(released(path)));
        sp.write(bytes(500, 9));

        assertSuccess(sp.compact());

        assertArrayEquals(bytes(500, 9), data(sp.read()));
        assertArrayEquals(bytes(500, 9), data(track(new PBStorageProvider(released(path))).read()));
        assertEquals(lengthForPages(1), fileLength(path));
    }

    @Test
    public void storageWorksNormallyAfterCompact() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 200);
        for (int k = 0; k < 200; k += 3) {
            ksp.delete(k);
        }
        assertSuccess(ksp.compact());

        Map<Integer, byte[]> expected = new HashMap<>();
        for (int k = 0; k < 200; k++) {
            if (k % 3 != 0) {
                expected.put(k, value(k, VALUE_SIZE));
            }
        }
        Random random = new Random(11);
        for (int i = 0; i < 2_000; i++) {
            int k = random.nextInt(300);
            if (random.nextInt(3) == 0) {
                ksp.delete(k);
                expected.remove(k);
            } else {
                byte[] v = value(k, 4 + random.nextInt(250));
                assertSuccess(ksp.write(k, v));
                expected.put(k, v);
            }
        }

        java.util.function.Consumer<PBKeyStorageProvider<Integer>> matchesExpected = storage -> {
            for (int k = 0; k < 300; k++) {
                if (expected.containsKey(k)) {
                    assertArrayEquals(expected.get(k), data(storage.read(k)), "key " + k);
                } else {
                    assertFail(PBFail.KEY_NOT_FOUND, storage.read(k));
                }
            }
        };
        matchesExpected.accept(ksp);
        matchesExpected.accept(open(path));   // and after reopening
    }

    @Test
    public void noTemporaryFileIsLeftBehind() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 50).compact();

        File[] files = dexFolder(path).listFiles();
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        names.sort(null);
        assertEquals(List.of("data.pb", "dex.lock", "index.pbi", "journal.wal"), names);
    }

    // ---- interrupted compaction ----------------------------------------------------

    @Test
    public void leftoverTemporaryFileIsDeletedOnOpen() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 50);
        File leftover = new File(dexFolder(path), "data-compact.pb");
        Files.write(leftover.toPath(), bytes(5000, 1));   // crash while writing the copy

        PBKeyStorageProvider<Integer> ksp = open(path);

        assertFalse(leftover.exists());
        for (int k = 0; k < 50; k++) {
            assertArrayEquals(value(k, VALUE_SIZE), data(ksp.read(k)));
        }
    }

    @Test
    public void crashAfterTheSwapBeforeTheIndexRebuildIsRepairedOnOpen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 500);
        for (int k = 0; k < 500; k += 2) {
            ksp.delete(k);
        }
        Path oldIndex = dir.resolve("old-index.pbi");
        checkpointIfOpen(path);
        Files.copy(indexFile(path).toPath(), oldIndex);

        assertSuccess(ksp.compact());
        released(path);
        // Simulate dying right after the rename: the index still describes the old file.
        Files.copy(oldIndex, indexFile(path).toPath(), StandardCopyOption.REPLACE_EXISTING);

        PBKeyStorageProvider<Integer> reopened = open(path);
        for (int k = 0; k < 500; k++) {
            if (k % 2 == 0) {
                assertFail(PBFail.KEY_NOT_FOUND, reopened.read(k));
            } else {
                assertArrayEquals(value(k, VALUE_SIZE), data(reopened.read(k)), "key " + k);
            }
        }
    }

    // ---- threads -------------------------------------------------------------------

    @Test
    public void compactWhileOthersReadWriteAndDelete() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        int threads = 6;
        int keysPerThread = 200;

        Queue<String> failures = new ConcurrentLinkedQueue<>();
        List<Map<Integer, byte[]>> models = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            Map<Integer, byte[]> model = new HashMap<>();
            models.add(model);
            final int base = t * keysPerThread;
            final int seed = t;
            workers.add(new Thread(() -> {
                try {
                    start.await();
                    Random random = new Random(seed);
                    for (int i = 0; i < 4_000; i++) {
                        int k = base + random.nextInt(keysPerThread);
                        int op = random.nextInt(10);
                        if (op < 4) {
                            byte[] v = value(k, 4 + random.nextInt(200));
                            if (ksp.write(k, v).hasSucceeded()) {
                                model.put(k, v);
                            } else {
                                failures.add("write " + k);
                            }
                        } else if (op < 6) {
                            PBResult r = ksp.delete(k);
                            if (r.hasSucceeded() != model.containsKey(k)) {
                                failures.add("delete " + k + " -> " + r);
                            }
                            model.remove(k);
                        } else {
                            PBResult r = ksp.read(k);
                            byte[] want = model.get(k);
                            boolean ok = want == null
                                ? r.getStatus() == PBFail.KEY_NOT_FOUND
                                : r.hasSucceeded() && java.util.Arrays.equals(want, r.getData());
                            if (!ok && failures.size() < 20) {
                                failures.add("read " + k + " -> " + r);
                            }
                        }
                    }
                } catch (Throwable e) {
                    failures.add("worker threw " + e);
                }
            }));
        }
        Thread compactor = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 25; i++) {
                    PBResult r = ksp.compact();
                    if (!r.hasSucceeded()) {
                        failures.add("compact -> " + r);
                    }
                    Thread.sleep(5);
                }
            } catch (Throwable e) {
                failures.add("compactor threw " + e);
            }
        });
        workers.add(compactor);

        for (Thread thread : workers) {
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        long deadline = System.currentTimeMillis() + 120_000;
        for (Thread thread : workers) {
            thread.join(Math.max(1, deadline - System.currentTimeMillis()));
            assertFalse(thread.isAlive(), thread.getName() + " is stuck");
        }
        assertEquals(List.of(), new ArrayList<>(failures));

        assertSuccess(ksp.compact());
        PBKeyStorageProvider<Integer> reopened = open(path);
        for (int t = 0; t < threads; t++) {
            for (int k = t * keysPerThread; k < (t + 1) * keysPerThread; k++) {
                byte[] want = models.get(t).get(k);
                if (want == null) {
                    assertFail(PBFail.KEY_NOT_FOUND, reopened.read(k));
                } else {
                    assertArrayEquals(want, data(reopened.read(k)), "key " + k);
                }
            }
        }
        assertEquals(0, reopened.getDeadBytes());
    }
}
