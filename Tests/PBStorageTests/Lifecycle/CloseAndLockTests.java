package PBStorageTests.Lifecycle;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException;

/*
close(), and the lock that keeps a storage to one open instance at a time --
in this program and across programs. These tests manage their instances
themselves (no track / released), since opening twice is what they test.
*/
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class CloseAndLockTests {
    @TempDir
    Path dir;

    private PBKeyStorageProvider<Integer> open(String path) throws Exception {
        return new PBKeyStorageProvider<>(path, Integer.class);
    }

    // ---- one instance at a time ------------------------------------------------------

    @Test
    public void secondInstanceInThisProgramIsRefused() throws Exception {
        String path = storagePath(dir, "ksp");
        try (PBKeyStorageProvider<Integer> first = open(path)) {
            StorageInUseException e = assertThrows(StorageInUseException.class, () -> open(path));
            assertTrue(e.getMessage().contains("in this program"), e.getMessage());
            assertEquals(first.getFolder(), e.getFolder());

            assertThrows(StorageInUseException.class, () -> new PBStorageProvider(path), "keyless too");
            assertThrows(StorageInUseException.class, () -> open(path + ".dex"), "same storage, other spelling");
            assertSuccess(first.write(1, bytes(5, 1)));
        }
    }

    @Test
    public void closedStorageCanBeOpenedAgainWithItsData() throws Exception {
        String path = storagePath(dir, "ksp");
        try (PBKeyStorageProvider<Integer> first = open(path)) {
            first.write(1, bytes(10, 1));
        }
        try (PBKeyStorageProvider<Integer> second = open(path)) {
            assertArrayEquals(bytes(10, 1), data(second.read(1)));
        }
    }

    @Test
    public void separateStoragesDontBlockEachOther() throws Exception {
        try (PBKeyStorageProvider<Integer> a = open(storagePath(dir, "a"));
             PBKeyStorageProvider<Integer> b = open(storagePath(dir, "b"))) {
            assertSuccess(a.write(1, bytes(5, 1)));
            assertSuccess(b.write(1, bytes(5, 2)));
        }
    }

    @Test
    public void lockSurvivesCompact() throws Exception {
        String path = storagePath(dir, "ksp");
        try (PBKeyStorageProvider<Integer> ksp = open(path)) {
            for (int k = 0; k < 100; k++) {
                ksp.write(k, bytes(50, k));
            }
            assertSuccess(ksp.compact());   // data.pb is replaced; dex.lock isn't
            assertThrows(StorageInUseException.class, () -> open(path));
        }
    }

    @Test
    public void refusedOpenDoesNotTouchTheFolder() throws Exception {
        String path = storagePath(dir, "ksp");
        try (PBKeyStorageProvider<Integer> ksp = open(path)) {
            // Looks like an interrupted compaction, but it is the open instance's.
            File temp = new File(dexFolder(path), "data-compact.pb");
            Files.write(temp.toPath(), bytes(10, 1));

            assertThrows(StorageInUseException.class, () -> open(path));
            assertTrue(temp.exists(), "only the lock holder may clean up the folder");
        }
    }

    // ---- failed opens release the lock -------------------------------------------------

    @Test
    public void wrongKeyTypeReleasesTheLock() throws Exception {
        String path = storagePath(dir, "ksp");
        open(path).close();

        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, String.class));
        assertThrows(IllegalArgumentException.class, () -> new PBKeyStorageProvider<>(path, null));

        open(path).close();
    }

    @Test
    public void corruptFileReleasesTheLock() throws Exception {
        String path = storagePath(dir, "ksp");
        open(path).close();
        Files.write(datFile(path).toPath(), bytes(100, 7));   // shorter than a file header

        assertThrows(IllegalStateException.class, () -> open(path));
        assertThrows(IllegalStateException.class, () -> open(path), "not StorageInUseException");
    }

    // ---- close() ---------------------------------------------------------------------

    @Test
    public void everyOperationAfterCloseSaysClosed() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        ksp.write(1, bytes(10, 1));
        ksp.write(2, bytes(10, 2));
        ksp.delete(2);
        long dead = ksp.getDeadBytes();

        assertFalse(ksp.isClosed());
        ksp.close();
        assertTrue(ksp.isClosed());

        List<PBResult> results = List.of(
            ksp.write(3, bytes(10, 3)), ksp.read(1), ksp.read(), ksp.delete(1),
            ksp.write(bytes(10, 4)), ksp.delete(), ksp.compact()
        );
        for (PBResult result : results) {
            assertFail(PBFail.STORAGE_CLOSED, result);
            assertNull(result.getError());
        }
        assertFalse(ksp.containsKey(1));
        assertThrows(IllegalStateException.class, ksp::getFileSize);
        assertThrows(IllegalStateException.class, ksp::reconstructKeyIndex);
        assertEquals(dead, ksp.getDeadBytes(), "last value kept");
        assertEquals(dexFolder(path).toPath(), ksp.getFolder());

        try (PBKeyStorageProvider<Integer> again = open(path)) {
            assertArrayEquals(bytes(10, 1), data(again.read(1)), "nothing changed after close");
            assertFail(PBFail.KEY_NOT_FOUND, again.read(3));
        }
    }

    @Test
    public void keylessOperationsAfterCloseSayClosed() throws Exception {
        PBStorageProvider sp = new PBStorageProvider(storagePath(dir, "sp"));
        sp.write(bytes(10, 1));
        sp.close();

        assertFail(PBFail.STORAGE_CLOSED, sp.read());
        assertFail(PBFail.STORAGE_CLOSED, sp.write(bytes(10, 2)));
        assertFail(PBFail.STORAGE_CLOSED, sp.delete());
        assertFail(PBFail.STORAGE_CLOSED, sp.compact());
    }

    @Test
    public void closeTwiceIsFine() throws Exception {
        PBKeyStorageProvider<Integer> ksp = open(storagePath(dir, "ksp"));
        ksp.close();
        ksp.close();
        assertTrue(ksp.isClosed());
    }

    @Test
    public void argumentsAreStillCheckedAfterClose() throws Exception {
        PBKeyStorageProvider<Integer> ksp = open(storagePath(dir, "ksp"));
        ksp.close();
        assertFail(PBFail.NULL_KEY, ksp.read(null));
        assertFail(PBFail.NULL_WRITE, ksp.write(1, null));
    }

    @Test
    public void closeWhileOtherThreadsWorkIsClean() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = open(path);
        for (int k = 0; k < 200; k++) {
            ksp.write(k, bytes(20, k));
        }

        Queue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch started = new CountDownLatch(4);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int seed = t;
            Thread thread = new Thread(() -> {
                started.countDown();
                for (int i = 0; i < 50_000; i++) {
                    int k = (i * 7 + seed) % 200;
                    PBResult r = (i % 3 == 0) ? ksp.write(k, bytes(20, k)) : ksp.read(k);
                    if (r.getStatus() == PBFail.STORAGE_CLOSED) {
                        return;   // expected once close() has run
                    }
                    if (!r.hasSucceeded()) {
                        failures.add(k + " -> " + r);
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        started.await();
        Thread.sleep(20);
        ksp.close();
        for (Thread thread : threads) {
            thread.join(30_000);
            assertFalse(thread.isAlive(), "thread stuck after close");
        }
        assertEquals(List.of(), new ArrayList<>(failures));

        try (PBKeyStorageProvider<Integer> again = open(path)) {
            for (int k = 0; k < 200; k++) {
                assertArrayEquals(bytes(20, k), data(again.read(k)), "key " + k);
            }
        }
    }

    // ---- another program -----------------------------------------------------------

    /*
    Starts LockHolder in a new JVM on path and waits until it has the storage open.
    */
    private static Process startHolder(String path) throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = location(LockHolder.class) + File.pathSeparator + location(PBKeyStorageProvider.class);
        Process process = new ProcessBuilder(javaBin, "-cp", classPath, LockHolder.class.getName(), path)
            .redirectErrorStream(true)
            .start();
        return process;
    }

    private static String location(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String readLine(Process process) throws Exception {
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith("READY") || line.startsWith("CLOSED")) {
                return line;
            }
            if (line.contains("Exception")) {
                fail("holder failed: " + line);
            }
        }
        fail("holder exited without answering");
        return null;
    }

    @Test
    public void anotherProgramIsRefusedUntilItCloses() throws Exception {
        String path = storagePath(dir, "shared");
        Process holder = startHolder(path);
        try {
            String ready = readLine(holder);
            String pid = ready.substring("READY ".length());

            StorageInUseException e = assertThrows(StorageInUseException.class, () -> open(path));
            assertTrue(e.getMessage().contains("another program"), e.getMessage());
            assertTrue(e.getMessage().contains("pid " + pid), "names the holder: " + e.getMessage());

            Writer in = new OutputStreamWriter(holder.getOutputStream(), StandardCharsets.UTF_8);
            in.write("close\n");
            in.flush();
            assertEquals("CLOSED", readLine(holder));
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));

            try (PBKeyStorageProvider<Integer> mine = open(path)) {
                assertArrayEquals(new byte[] { 42 }, data(mine.read(1)), "sees the other program's write");
            }
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    public void crashedProgramLeavesNoStaleLock() throws Exception {
        String path = storagePath(dir, "shared");
        Process holder = startHolder(path);
        try {
            readLine(holder);
            assertThrows(StorageInUseException.class, () -> open(path));

            holder.destroyForcibly();   // killed without close()
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));

            try (PBKeyStorageProvider<Integer> mine = open(path)) {
                assertArrayEquals(new byte[] { 42 }, data(mine.read(1)));
                assertEquals(PBSuccess.WRITE, mine.write(2, bytes(5, 2)).getStatus());
            }
        } finally {
            holder.destroyForcibly();
        }
    }
}
