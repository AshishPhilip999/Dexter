package PBStorageTests.Concurrency;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

/*
Many threads sharing one storage instance. Values start with their own key, so
a reader can always tell whether it got the right record.
*/
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class ThreadSafetyTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    private static final int THREADS = 8;

    /*
    [key (4 bytes)][tag (1 byte)][filler] -- tag lets writers mark their values.
    */
    private static byte[] value(int key, int tag, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(5, length));
        buffer.putInt(key).put((byte) tag);
        while (buffer.hasRemaining()) {
            buffer.put((byte) tag);
        }
        return buffer.array();
    }

    private static int keyOf(byte[] value) {
        return ByteBuffer.wrap(value).getInt();
    }

    /*
    Longest a group of threads may run. A thread still running after this is
    reported as stuck (a deadlock, or a corrupted structure looping forever).
    */
    private static final long DEADLINE_MILLIS = 60_000;

    /*
    Runs the tasks on their own threads, released together; returns any
    failures they reported or threw.
    */
    private static List<String> runTogether(List<ThrowingRunnable> tasks) throws Exception {
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (ThrowingRunnable task : tasks) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    task.run(failures);
                } catch (Throwable t) {
                    failures.add(Thread.currentThread().getName() + " threw " + t);
                }
            });
            thread.setDaemon(true);   // a stuck thread must not keep the JVM alive
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        for (Thread thread : threads) {
            thread.join(Math.max(1, deadline - System.currentTimeMillis()));
            if (thread.isAlive()) {
                failures.add(thread.getName() + " is stuck (deadlock or endless loop)");
            }
        }
        return new ArrayList<>(failures);
    }

    interface ThrowingRunnable {
        void run(Queue<String> failures) throws Exception;
    }

    private static void check(Queue<String> failures, boolean ok, String message) {
        if (!ok && failures.size() < 20) {
            failures.add(message);
        }
    }

    // ---- reads ---------------------------------------------------------------

    @Test
    public void concurrentReadersEachGetTheirOwnData() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        int keys = 60_000;   // ~1,800 data pages: more than the page cache holds
        for (int k = 0; k < keys; k++) {
            ksp.write(k, value(k, 1, 100));
        }

        List<ThrowingRunnable> readers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int seed = t;
            readers.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < 20_000; i++) {
                    int k = random.nextInt(keys);
                    SPResult status = ksp.read(k);
                    check(failures, status.getStatus() == SPSuccess.READ, "read(" + k + ") -> " + status);
                    if (status.getStatus() == SPSuccess.READ) {
                        byte[] data = status.getData();
                        check(failures, data.length == 100 && keyOf(data) == k,
                            "read(" + k + ") returned data of key " + keyOf(data));
                    }
                }
            });
        }

        assertEquals(List.of(), runTogether(readers));
    }

    @Test
    public void eachResultKeepsItsOwnData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(storagePath(dir, "ksp")), Integer.class));
        ksp.write(1, value(1, 1, 20));
        ksp.write(2, value(2, 2, 30));

        SPResult first = ksp.read(1);
        SPResult second = ksp.read(2);
        SPResult written = ksp.write(3, value(3, 3, 40));

        assertNotSame(first, second, "every call returns a new result");
        assertEquals(1, keyOf(first.getData()), "a later read doesn't change an earlier result");
        assertEquals(20, first.getData().length);
        assertEquals(2, keyOf(second.getData()));
        assertEquals(3, keyOf(written.getData()));
    }

    @Test
    public void resultsHandedToOtherThreadsKeepTheirData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(storagePath(dir, "ksp")), Integer.class));
        int keys = 2_000;
        for (int k = 0; k < keys; k++) {
            ksp.write(k, value(k, 1, 50));
        }

        // Producers read and pass the SPResult itself to consumers, which check it.
        java.util.concurrent.BlockingQueue<Object[]> queue = new java.util.concurrent.ArrayBlockingQueue<>(256);
        int producers = 4;
        int perProducer = 10_000;
        List<ThrowingRunnable> tasks = new ArrayList<>();
        for (int t = 0; t < producers; t++) {
            final int seed = t;
            tasks.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < perProducer; i++) {
                    int k = random.nextInt(keys);
                    queue.put(new Object[] { k, ksp.read(k) });
                }
            });
        }
        for (int t = 0; t < producers; t++) {
            tasks.add(failures -> {
                for (int i = 0; i < perProducer; i++) {
                    Object[] item = queue.take();
                    int k = (Integer) item[0];
                    SPResult result = (SPResult) item[1];
                    check(failures, result.getStatus() == SPSuccess.READ, "read(" + k + ") -> " + result);
                    check(failures, result.getData() != null && keyOf(result.getData()) == k,
                        "result for key " + k + " carried another key's data");
                }
            });
        }

        assertEquals(List.of(), runTogether(tasks));
    }

    // ---- writes --------------------------------------------------------------

    @Test
    public void concurrentWritersOnSeparateKeys() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        int perThread = 3_000;

        List<ThrowingRunnable> writers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int base = t * perThread;
            writers.add(failures -> {
                for (int i = 0; i < perThread; i++) {
                    check(failures, ksp.write(base + i, value(base + i, 1, 50)).getStatus() == SPSuccess.WRITE, "write " + (base + i));
                }
                for (int i = 0; i < perThread; i += 2) {   // overwrite half, with a different size
                    check(failures, ksp.write(base + i, value(base + i, 2, 80)).getStatus() == SPSuccess.WRITE, "overwrite " + (base + i));
                }
            });
        }
        assertEquals(List.of(), runTogether(writers));

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int k = 0; k < THREADS * perThread; k++) {
            int expectedTag = (k % perThread) % 2 == 0 ? 2 : 1;
            byte[] data = data(reopened.read(k));
            assertEquals(k, keyOf(data));
            assertEquals(expectedTag, data[4], "latest value of key " + k);
        }
        assertEquals(THREADS * perThread, dataArray(reopened.read()).length, "no duplicate or leaked records");
    }

    @Test
    public void readersNeverSeeAWrongOrBrokenRecordWhileKeysAreOverwritten() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        int keys = 500;
        for (int k = 0; k < keys; k++) {
            ksp.write(k, value(k, 0, 40));
        }

        List<ThrowingRunnable> tasks = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            final int writer = w + 1;
            tasks.add(failures -> {
                Random random = new Random(writer);
                for (int i = 0; i < 8_000; i++) {
                    int k = random.nextInt(keys);
                    check(failures, ksp.write(k, value(k, writer, 5 + random.nextInt(300))).getStatus() == SPSuccess.WRITE, "write " + k);
                }
            });
        }
        for (int r = 0; r < 4; r++) {
            final int seed = 100 + r;
            tasks.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < 20_000; i++) {
                    int k = random.nextInt(keys);
                    SPResult status = ksp.read(k);
                    check(failures, status.getStatus() == SPSuccess.READ, "read(" + k + ") -> " + status);
                    if (status.getStatus() == SPSuccess.READ) {
                        byte[] data = status.getData();
                        check(failures, keyOf(data) == k, "read(" + k + ") returned key " + keyOf(data));
                        byte tag = data[4];
                        for (int b = 5; b < data.length; b++) {
                            if (data[b] != tag) {
                                check(failures, false, "read(" + k + ") returned a half-written value");
                                break;
                            }
                        }
                    }
                }
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int k = 0; k < keys; k++) {
            assertEquals(k, keyOf(data(reopened.read(k))));
        }
        assertEquals(keys, dataArray(reopened.read()).length, "every overwrite freed its old slot");
    }

    // ---- mixed writes, deletes and reads -------------------------------------

    @Test
    public void mixedWritesDeletesAndReadsLeaveIndexAndDataInAgreement() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        int keys = 1_000;

        List<ThrowingRunnable> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int seed = t;
            tasks.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < 6_000; i++) {
                    int k = random.nextInt(keys);
                    int op = random.nextInt(10);
                    SPResult status;
                    if (op < 4) {
                        status = ksp.write(k, value(k, seed, 5 + random.nextInt(200)));
                        check(failures, status.getStatus() == SPSuccess.WRITE, "write " + k + " -> " + status);
                    } else if (op < 6) {
                        status = ksp.delete(k);
                        check(failures, status.getStatus() == SPSuccess.DELETE || status.getStatus() == SPFail.KEY_NOT_FOUND, "delete " + k + " -> " + status);
                    } else if (op < 9) {
                        status = ksp.read(k);
                        check(failures, status.getStatus() == SPSuccess.READ || status.getStatus() == SPFail.KEY_NOT_FOUND, "read " + k + " -> " + status);
                        if (status.getStatus() == SPSuccess.READ) {
                            check(failures, keyOf(status.getData()) == k, "read " + k + " wrong record");
                        }
                    } else {
                        ksp.containsKey(k);
                    }
                }
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        Set<Integer> present = new TreeSet<>();
        for (int k = 0; k < keys; k++) {
            SPResult status = ksp.read(k);
            assertTrue(status.getStatus() == SPSuccess.READ || status.getStatus() == SPFail.KEY_NOT_FOUND, "key " + k + " -> " + status);
            if (status.getStatus() == SPSuccess.READ) {
                present.add(k);
                assertTrue(ksp.containsKey(k));
            }
        }
        SPResult all = ksp.read();
        assertEquals(present.size(), all.getStatus() == SPFail.NULL_READ ? 0 : all.getDataArray().length,
            "read() and the index agree on how many keys are live");

        // Rebuilding the index from the data pages must find exactly the same keys.
        PBKeyStorageProvider<Integer> rebuilt = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        rebuilt.reconstructKeyIndex();
        Set<Integer> afterRebuild = new TreeSet<>();
        for (int k = 0; k < keys; k++) {
            if (rebuilt.containsKey(k)) {
                afterRebuild.add(k);
            }
        }
        assertEquals(present, afterRebuild);
    }

    @Test
    public void deleteAllWhileOthersReadAndWrite() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        int keys = 300;
        for (int k = 0; k < keys; k++) {
            ksp.write(k, value(k, 0, 30));
        }

        List<ThrowingRunnable> tasks = new ArrayList<>();
        tasks.add(failures -> {
            for (int i = 0; i < 50; i++) {
                check(failures, ksp.delete().getStatus() == SPSuccess.DELETE, "delete()");
                Thread.yield();
            }
        });
        for (int t = 0; t < 6; t++) {
            final int seed = t;
            tasks.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < 10_000; i++) {
                    int k = random.nextInt(keys);
                    if (random.nextBoolean()) {
                        check(failures, ksp.write(k, value(k, seed, 30)).getStatus() == SPSuccess.WRITE, "write");
                    } else {
                        SPResult status = ksp.read(k);
                        check(failures, status.getStatus() == SPSuccess.READ || status.getStatus() == SPFail.KEY_NOT_FOUND, "read -> " + status);
                        if (status.getStatus() == SPSuccess.READ) {
                            check(failures, keyOf(status.getData()) == k, "wrong record");
                        }
                    }
                }
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        int live = 0;
        for (int k = 0; k < keys; k++) {
            if (ksp.read(k).getStatus() == SPSuccess.READ) {
                live++;
            }
        }
        SPResult all = track(new PBKeyStorageProvider<>(released(path), Integer.class)).read();
        assertEquals(live, all.getStatus() == SPFail.NULL_READ ? 0 : dataArray(all).length);
    }

    // ---- keyless storage ------------------------------------------------------

    @Test
    public void keylessReadsNeverSeeAHalfWrittenValue() throws Exception {
        PBStorageProvider sp = track(new PBStorageProvider(released(storagePath(dir, "sp"))));
        sp.write(bytes(100, 1));

        List<ThrowingRunnable> tasks = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            final int seed = w;
            tasks.add(failures -> {
                Random random = new Random(seed);
                for (int i = 0; i < 3_000; i++) {
                    int v = 1 + random.nextInt(250);
                    check(failures, sp.write(bytes(1 + random.nextInt(2_000), v)).getStatus() == SPSuccess.WRITE, "write");
                }
            });
        }
        for (int r = 0; r < 5; r++) {
            tasks.add(failures -> {
                for (int i = 0; i < 10_000; i++) {
                    SPResult status = sp.read();
                    check(failures, status.getStatus() == SPSuccess.READ, "read -> " + status);
                    if (status.getStatus() == SPSuccess.READ) {
                        byte[] data = status.getData();
                        boolean uniform = true;
                        for (byte b : data) {
                            uniform &= b == data[0];
                        }
                        check(failures, uniform && data[0] != 0, "read returned a mix of two writes");
                    }
                }
            });
        }
        assertEquals(List.of(), runTogether(tasks));
    }
}
