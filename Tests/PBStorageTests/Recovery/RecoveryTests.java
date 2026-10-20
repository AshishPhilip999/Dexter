package PBStorageTests.Recovery;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.Durability;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.Journal.Journal;

/*
Crash recovery: whatever moment a program stops at, the storage reopens as it
was after some complete operation, and nothing that had returned success is
missing.

Crashes are simulated three ways:
- killing a separate program (CrashWorker) in the middle of its work;
- a "crash image": a copy of the files taken while a storage is open, which is
  exactly what a crash at that moment would leave on disk;
- cutting the journal short or damaging it, as a power cut can (the files
  themselves are only written at checkpoints, after the journal is flushed).
*/
@Timeout(value = 300, unit = TimeUnit.SECONDS)
public class RecoveryTests {
    @TempDir
    Path dir;

    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    private PBKeyStorageProvider<Integer> open(String path) throws Exception {
        return track(new PBKeyStorageProvider<>(released(path), Integer.class));
    }

    private static File journalOf(String path) {
        return new File(dexFolder(path), "journal.wal");
    }

    /*
    Runs operations 0..count-1 of the script in this program.
    */
    private static void run(PBKeyStorageProvider<Integer> storage, long seed, int from, int count) {
        for (int i = from; i < from + count; i++) {
            PBResult0.check(CrashScript.apply(storage, CrashScript.op(seed, i)), i);
        }
    }

    // ---- a killed program --------------------------------------------------------------

    private static Process startWorker(String path, long seed, Durability durability, int compactEvery) throws Exception {
        return startWorker(path, seed, durability, compactEvery, CrashScript.KEYS);
    }

    private static Process startWorker(String path, long seed, Durability durability, int compactEvery, int keys) throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = location(CrashWorker.class) + File.pathSeparator + location(PBKeyStorageProvider.class);
        return new ProcessBuilder(javaBin, "-cp", classPath, CrashWorker.class.getName(),
                path, Long.toString(seed), durability.name(), Integer.toString(compactEvery), Integer.toString(keys))
            .redirectErrorStream(true)
            .start();
    }

    private static String location(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /*
    Lets the worker run until it reports at least `until` operations, kills it
    (no close(), no shutdown hooks), and returns the last operation it reported.
    */
    private static int runAndKill(Process worker, int until) throws Exception {
        BufferedReader out = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
        int last = -1;
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith("FAILED") || line.contains("Exception")) {
                worker.destroyForcibly();
                fail("worker: " + line);
            }
            if (line.startsWith("OP ")) {
                last = Integer.parseInt(line.substring(3));
                if (last >= until) {
                    break;
                }
            }
        }
        worker.destroyForcibly();
        assertTrue(worker.waitFor(30, TimeUnit.SECONDS));
        assertTrue(last >= 0, "the worker reported no operations");
        return last;
    }

    private void killedWorkerLosesNothing(long seed, Durability durability, int compactEvery, int until) throws Exception {
        String path = storagePath(dir, "crash-" + seed);
        Process worker = startWorker(path, seed, durability, compactEvery);
        int reported;
        try {
            reported = runAndKill(worker, until);
        } finally {
            worker.destroyForcibly();
        }

        PBKeyStorageProvider<Integer> reopened = open(path);
        Map<Integer, byte[]> actual = CrashScript.contents(reopened);
        int completed = CrashScript.completedOperations(actual, seed, reported + 1, reported + 100_000);

        assertTrue(completed >= reported + 1,
            "after a kill the storage must hold every reported operation, and only whole operations (got " + completed
            + ", reported " + (reported + 1) + ")");
        assertEquals(DexFiles.HEADER_ONLY, journalOf(path).length(), "recovery empties the journal");
        assertSuccess(reopened.write(1, bytes(10, 1)));   // and the storage works on
    }

    @Test
    public void killedProgramLosesNoCompletedOperation() throws Exception {
        killedWorkerLosesNothing(11, Durability.NORMAL, 0, 3_000 + new Random().nextInt(3_000));
    }

    /*
    Over a large key space the journal applies its changes to the files many
    times before the kill, so recovery starts from files that already hold part
    of the journal's work.
    */
    @Test
    public void killedProgramOverManyPagesLosesNothing() throws Exception {
        long seed = 17;
        int keys = 120_000;
        String path = storagePath(dir, "wide");
        Process worker = startWorker(path, seed, Durability.NORMAL, 0, keys);
        int reported;
        try {
            reported = runAndKill(worker, 90_000 + new Random().nextInt(30_000));
        } finally {
            worker.destroyForcibly();
        }
        assertTrue(datFile(path).length() > 2_000_000, "precondition: changes were applied to data.pb before the kill");

        PBKeyStorageProvider<Integer> reopened = open(path);
        int completed = CrashScript.completedOperations(CrashScript.contents(reopened, keys), seed, reported + 1, reported + 100_000, keys);
        assertTrue(completed >= reported + 1, "got " + completed + ", reported " + (reported + 1));
    }

    @Test
    public void crashImageAfterAppliesReopensWithEveryOperation() throws Exception {
        long seed = 18;
        int keys = 120_000;
        String path = storagePath(dir, "wide");
        PBKeyStorageProvider<Integer> storage = open(path);
        for (int i = 0; i < 90_000; i++) {
            PBResult0.check(CrashScript.apply(storage, CrashScript.op(seed, i, keys)), i);
        }
        assertTrue(datFile(path).length() > 2_000_000, "precondition: changes were applied to data.pb");
        assertTrue(journalOf(path).length() > DexFiles.HEADER_ONLY, "precondition: the journal still holds them");

        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());
        PBKeyStorageProvider<Integer> recovered = open(image);
        assertEquals(90_000, CrashScript.completedOperations(CrashScript.contents(recovered, keys), seed, 90_000, 90_000, keys));
    }

    /*
    Many pages but small journal records (keys written in order), so changes
    are applied to the files several times while the journal is never emptied
    by a checkpoint. A crash image then has files that hold part of the
    journal's work; recovery must still end with every operation.
    */
    @Test
    public void crashImageAfterAppliesWithoutACheckpoint() throws Exception {
        String path = storagePath(dir, "applied");
        PBKeyStorageProvider<Integer> storage = open(path);
        long previousJournal = 0;
        for (int k = 0; k < 200_000; k++) {
            assertSuccess(storage.write(k, bytes(100, k)));
            long journal = journalOf(path).length();
            assertTrue(journal >= previousJournal, "precondition: no checkpoint emptied the journal");
            previousJournal = journal;
        }
        assertTrue(datFile(path).length() > 10_000_000, "precondition: changes were applied to data.pb, was "
            + datFile(path).length());

        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());
        PBKeyStorageProvider<Integer> recovered = open(image);
        assertTrue(recovered.getRecoveredOperations() >= 200_000);
        for (int k = 0; k < 200_000; k++) {
            assertArrayEquals(bytes(100, k), data(recovered.read(k)), "key " + k);
        }
    }

    @Test
    public void killedProgramWithSyncDurabilityLosesNothing() throws Exception {
        killedWorkerLosesNothing(12, Durability.SYNC, 0, 1_500 + new Random().nextInt(1_000));
    }

    @Test
    public void killedWhileCompactingLosesNothing() throws Exception {
        for (long seed = 13; seed < 16; seed++) {
            killedWorkerLosesNothing(seed, Durability.NORMAL, 400, 1_200 + new Random().nextInt(2_000));
        }
    }

    // ---- crash images ------------------------------------------------------------------

    @Test
    public void crashImageReopensWithEveryOperation() throws Exception {
        long seed = 21;
        String path = storagePath(dir, "live");
        PBKeyStorageProvider<Integer> storage = open(path);
        run(storage, seed, 0, 2_500);    // several checkpoints, plus a journal tail

        assertTrue(journalOf(path).length() > DexFiles.HEADER_ONLY, "precondition: operations still in the journal");
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());

        PBKeyStorageProvider<Integer> recovered = open(image);
        assertTrue(recovered.getRecoveredOperations() > 0);
        assertEquals(2_500, CrashScript.completedOperations(CrashScript.contents(recovered), seed, 0, 2_500));
    }

    @Test
    public void cleanCloseLeavesNothingToRecover() throws Exception {
        String path = storagePath(dir, "ksp");
        run(open(path), 22, 0, 500);
        released(path);

        assertEquals(DexFiles.HEADER_ONLY, journalOf(path).length(), "close() checkpoints");
        assertEquals(0, open(path).getRecoveredOperations());
    }

    /*
    A power cut can leave the journal cut short at any byte (the files are only
    written at checkpoints, after the journal is flushed). Every cut must reopen
    as a whole number of operations, more of them the longer the journal.
    */
    @Test
    public void journalCutAnywhereReopensAsWholeOperations() throws Exception {
        long seed = 23;
        String path = storagePath(dir, "live");
        run(open(path), seed, 0, 1_700);
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());
        long journalLength = journalOf(image).length();
        assertTrue(journalLength > 50_000, "precondition: a long journal tail, was " + journalLength);

        List<Long> cuts = new ArrayList<>();
        Random random = new Random(7);
        cuts.add((long) DexFiles.HEADER_ONLY);
        for (int i = 0; i < 25; i++) {
            cuts.add(DexFiles.HEADER_ONLY + (long) (random.nextDouble() * (journalLength - DexFiles.HEADER_ONLY)));
        }
        cuts.add(journalLength);
        cuts.sort(null);

        int previous = -1;
        for (int c = 0; c < cuts.size(); c++) {
            String copy = storagePath(dir, "cut" + c);
            CrashScript.crashImage(dexFolder(image).toPath(), dexFolder(copy).toPath());
            try (RandomAccessFile journal = new RandomAccessFile(journalOf(copy), "rw")) {
                journal.setLength(cuts.get(c));
            }

            int completed = CrashScript.completedOperations(CrashScript.contents(open(copy)), seed, 0, 1_700);
            assertTrue(completed >= 0, "cut at " + cuts.get(c) + " left a half-applied operation");
            assertTrue(completed >= previous, "a longer journal can't recover less");
            previous = completed;
            released(copy);
        }
        assertEquals(1_700, previous, "the whole journal recovers everything");
    }

    @Test
    public void damagedJournalRecordStopsRecoveryThere() throws Exception {
        long seed = 24;
        String path = storagePath(dir, "live");
        run(open(path), seed, 0, 1_200);
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());
        long journalLength = journalOf(image).length();

        Random random = new Random(8);
        for (int c = 0; c < 10; c++) {
            String copy = storagePath(dir, "flip" + c);
            CrashScript.crashImage(dexFolder(image).toPath(), dexFolder(copy).toPath());
            long at = DexFiles.HEADER_ONLY + (long) (random.nextDouble() * (journalLength - DexFiles.HEADER_ONLY));
            try (RandomAccessFile journal = new RandomAccessFile(journalOf(copy), "rw")) {
                journal.seek(at);
                int b = journal.read();
                journal.seek(at);
                journal.write(b ^ 0x5A);
            }

            int completed = CrashScript.completedOperations(CrashScript.contents(open(copy)), seed, 0, 1_200);
            assertTrue(completed >= 0 && completed < 1_200, "a damaged record and everything after it are dropped");
            released(copy);
        }
    }

    @Test
    public void garbageJournalIsIgnored() throws Exception {
        long seed = 25;
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> storage = open(path);
        run(storage, seed, 0, 300);
        assertSuccess(storage.checkpoint());
        released(path);
        Files.write(journalOf(path).toPath(), bytes(5_000, 0x33));

        PBKeyStorageProvider<Integer> reopened = open(path);
        assertEquals(0, reopened.getRecoveredOperations());
        assertEquals(300, CrashScript.completedOperations(CrashScript.contents(reopened), seed, 0, 300));
    }

    /*
    Records from before a checkpoint carry the old salt. If emptying the journal
    only half-reached the disk (new header, old records still there), they must
    not be replayed on top of the files that already contain them.
    */
    @Test
    public void recordsFromAnEarlierJournalAreIgnored() throws Exception {
        long seed = 29;
        String path = storagePath(dir, "live");
        PBKeyStorageProvider<Integer> storage = open(path);
        run(storage, seed, 0, 600);
        assertSuccess(storage.checkpoint());
        run(storage, seed, 600, 300);
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());

        // A different salt in the header: as if the journal had been emptied since.
        try (RandomAccessFile journal = new RandomAccessFile(journalOf(image), "rw")) {
            journal.seek(8);
            long salt = journal.readLong();
            journal.seek(8);
            journal.writeLong(salt + 1);
        }

        PBKeyStorageProvider<Integer> reopened = open(image);
        assertEquals(0, reopened.getRecoveredOperations());
        assertEquals(600, CrashScript.completedOperations(CrashScript.contents(reopened), seed, 0, 900),
            "only what the files had at the checkpoint");
    }

    /*
    A crash in the middle of a checkpoint: some files already have the
    journal's changes, others don't, and the journal is still full. Replaying
    writes the same bytes again, so every mix recovers the same state.
    */
    @Test
    public void crashDuringCheckpointRecoversTheSameState() throws Exception {
        long seed = 26;
        String path = storagePath(dir, "live");
        run(open(path), seed, 0, 1_500);
        Path image = dexFolder(storagePath(dir, "image")).toPath();
        CrashScript.crashImage(dexFolder(path).toPath(), image);

        // "applied": the same image after recovery, i.e. with the changes in the files.
        String applied = storagePath(dir, "applied");
        CrashScript.crashImage(image, dexFolder(applied).toPath());
        open(applied);
        released(applied);

        String[][] mixes = { { "data.pb" }, { "index.pbi" }, { "data.pb", "index.pbi" } };
        for (int m = 0; m < mixes.length; m++) {
            String mixed = storagePath(dir, "mix" + m);
            CrashScript.crashImage(image, dexFolder(mixed).toPath());
            for (String name : mixes[m]) {
                Files.copy(dexFolder(applied).toPath().resolve(name), dexFolder(mixed).toPath().resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            assertEquals(1_500, CrashScript.completedOperations(CrashScript.contents(open(mixed)), seed, 0, 1_500),
                "files already updated: " + String.join(", ", mixes[m]));
            released(mixed);
        }
    }

    // ---- sync, checkpoint, durability ---------------------------------------------------

    @Test
    public void checkpointEmptiesTheJournalAndCompletesTheFiles() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> storage = open(path);
        run(storage, 27, 0, 200);
        assertTrue(journalOf(path).length() > DexFiles.HEADER_ONLY);

        assertEquals(PBSuccess.CHECKPOINT, storage.checkpoint().getStatus());

        assertEquals(DexFiles.HEADER_ONLY, journalOf(path).length());
        assertEquals(storage.getFileSize(), datFile(path).length(), "data.pb holds everything");
        String image = storagePath(dir, "image");
        CrashScript.crashImage(dexFolder(path).toPath(), dexFolder(image).toPath());
        PBKeyStorageProvider<Integer> fromFiles = open(image);
        assertEquals(0, fromFiles.getRecoveredOperations());
        assertEquals(200, CrashScript.completedOperations(CrashScript.contents(fromFiles), 27, 0, 200));
    }

    @Test
    public void syncAndDurability() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> storage = track(new PBKeyStorageProvider<>(released(path), Integer.class, Durability.SYNC));
        assertEquals(Durability.SYNC, storage.getDurability());
        assertSuccess(storage.write(1, bytes(10, 1)));
        assertEquals(PBSuccess.SYNC, storage.sync().getStatus());
        assertEquals(Durability.NORMAL, open(storagePath(dir, "other")).getDurability());
        assertThrows(IllegalArgumentException.class, () -> new PBKeyStorageProvider<>(storagePath(dir, "x"), Integer.class, null));
    }

    @Test
    public void journalStaysBounded() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> storage = open(path);
        long largest = 0;
        boolean emptied = false;
        long previous = 0;
        for (int i = 0; i < 24_000; i++) {     // about 100 MB of journal records in all
            assertSuccess(storage.write(i % 5_000, bytes(4_000, i)));
            long size = journalOf(path).length();
            largest = Math.max(largest, size);
            emptied |= size < previous;
            previous = size;
        }
        assertTrue(emptied, "a checkpoint emptied the journal");
        assertTrue(largest <= Journal.CHECKPOINT_BYTES + 64 * 1024, "journal reached " + largest);
        for (int k = 0; k < 5_000; k++) {
            int lastWrite = k < 4_000 ? 20_000 + k : 15_000 + k;
            assertArrayEquals(bytes(4_000, lastWrite), data(storage.read(k)), "key " + k);
        }
    }

    /*
    Small helpers shared by the tests above.
    */
    static final class DexFiles {
        static final int HEADER_ONLY = 32;
    }
}

/*
Checks one script operation's result (a delete of a missing key is fine).
*/
final class PBResult0 {
    private PBResult0() {
    }

    static void check(StorageStatus.PB.PBResult result, int i) {
        if (!result.hasSucceeded() && result.getStatus() != StorageStatus.PB.PBFail.KEY_NOT_FOUND) {
            throw new AssertionError("operation " + i + " -> " + result);
        }
    }
}
