package PBStorageTests.Status;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.NotSerializableException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

/*
Failures caused by an exception carry it in PBResult.getError(). Each test
causes a real failure (a corrupt page, a damaged index, a blocked temp file)
and checks the reported cause.
*/
public class ErrorReportingTests {
    @TempDir
    Path dir;

    private PBKeyStorageProvider<Integer> withKeys(String path, int count) throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int k = 0; k < count; k++) {
            assertSuccess(ksp.write(k, bytes(100, k)));
        }
        return ksp;
    }

    /*
    Overwrites the page's stored offset, so loading the page fails its
    consistency check. Open a new instance afterwards: cached pages aren't re-read.
    */
    private static void corruptPage(String path, int page) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(datFile(path), "rw")) {
            file.seek(lengthForPages(page));
            file.writeLong(123456789L);
        }
    }

    private static void assertCause(PBFail expected, Class<? extends Exception> causeType, String messagePart, PBResult result) {
        assertFail(expected, result);
        assertNotNull(result.getError(), "no cause reported for " + result);
        assertTrue(causeType.isInstance(result.getError()), "cause was " + result.getError());
        assertTrue(String.valueOf(result.getError().getMessage()).contains(messagePart),
            "message was: " + result.getError().getMessage());
    }

    // ---- read / write / delete -------------------------------------------------------

    @Test
    public void readOfACorruptPageReportsWhy() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 10);
        corruptPage(path, 0);

        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        assertCause(PBFail.READ_ERROR, IllegalStateException.class, "Corrupt", ksp.read(3));
        assertCause(PBFail.READ_ERROR, IllegalStateException.class, "Corrupt", ksp.read());
    }

    @Test
    public void keylessReadOfACorruptPageReportsWhy() throws Exception {
        String path = storagePath(dir, "sp");
        new PBStorageProvider(path).write(bytes(50, 1));
        corruptPage(path, 0);

        assertCause(PBFail.READ_ERROR, IllegalStateException.class, "Corrupt", new PBStorageProvider(path).read());
    }

    @Test
    public void writeToACorruptPageReportsWhy() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 10);
        corruptPage(path, 0);

        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        assertCause(PBFail.WRITE_ERROR, IllegalStateException.class, "Corrupt", ksp.write(50, bytes(10, 1)));
    }

    @Test
    public void deleteOnACorruptPageReportsWhy() throws Exception {
        String path = storagePath(dir, "ksp");
        withKeys(path, 10);
        corruptPage(path, 0);

        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        assertCause(PBFail.DELETE_ERROR, IllegalStateException.class, "Corrupt", ksp.delete(4));
    }

    @Test
    public void keyThatCannotBeEncodedReportsWhy() throws Exception {
        PBKeyStorageProvider<BadKey> ksp = new PBKeyStorageProvider<>(storagePath(dir, "bad"), BadKey.class);

        PBResult result = ksp.write(new BadKey(), bytes(5, 1));

        assertFail(PBFail.KEY_ENCODE_ERROR, result);
        assertTrue(result.getError() instanceof NotSerializableException, "cause was " + result.getError());
    }

    static final class BadKey implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object notSerializable = new Object();
    }

    @Test
    public void damagedIndexIsExplained() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 5);
        Path savedIndex = dir.resolve("saved.pbi");
        Files.copy(indexFile(path).toPath(), savedIndex);
        ksp.delete(2);
        // The old index still says key 2 lives in a slot that is now deleted.
        Files.copy(savedIndex, indexFile(path).toPath(), StandardCopyOption.REPLACE_EXISTING);

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertCause(PBFail.READ_ERROR, IllegalStateException.class, "is deleted", reopened.read(2));
        assertTrue(reopened.read(2).getError().getMessage().contains("reconstructKeyIndex"));

        reopened.reconstructKeyIndex();
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(2));
        assertNull(reopened.read(2).getError());
    }

    // ---- compact -------------------------------------------------------------------

    /*
    A non-empty folder where compact() wants to create its temp file: opening it
    as a file fails, and deleting it during clean-up fails too.
    */
    private static File blockTempFile(String path) throws Exception {
        File blocker = new File(dexFolder(path), "data-compact.pb");
        assertTrue(blocker.mkdir());
        assertTrue(new File(blocker, "inside").createNewFile());
        return blocker;
    }

    @Test
    public void failedCompactReportsTheRealCause() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 200);
        for (int k = 0; k < 200; k += 2) {
            ksp.delete(k);
        }
        blockTempFile(path);

        PBResult result = ksp.compact();

        assertFail(PBFail.COMPACT_ERROR, result);
        assertTrue(result.getError() instanceof FileNotFoundException,
            "the cause, not the failed clean-up: " + result.getError());
        assertEquals(1, result.getError().getSuppressed().length, "the failed clean-up is attached");
    }

    @Test
    public void storageIsUnchangedAndUsableAfterAFailedCompact() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = withKeys(path, 200);
        for (int k = 0; k < 200; k += 2) {
            ksp.delete(k);
        }
        long sizeBefore = fileLength(path);
        long deadBefore = ksp.getDeadBytes();
        File blocker = blockTempFile(path);

        assertFail(PBFail.COMPACT_ERROR, ksp.compact());

        assertEquals(sizeBefore, fileLength(path));
        assertEquals(deadBefore, ksp.getDeadBytes());
        for (int k = 0; k < 200; k++) {
            if (k % 2 == 0) {
                assertFail(PBFail.KEY_NOT_FOUND, ksp.read(k));
            } else {
                assertArrayEquals(bytes(100, k), data(ksp.read(k)));
            }
        }
        assertSuccess(ksp.write(500, bytes(10, 5)));

        assertTrue(new File(blocker, "inside").delete());
        assertTrue(blocker.delete());
        PBResult retried = ksp.compact();
        assertEquals(PBSuccess.COMPACT, retried.getStatus());
        assertNull(retried.getError());
        assertArrayEquals(bytes(10, 5), data(ksp.read(500)));
    }

    // ---- no error where nothing was thrown ---------------------------------------

    @Test
    public void inputFailuresAndSuccessesHaveNoError() throws Exception {
        PBKeyStorageProvider<Integer> ksp = withKeys(storagePath(dir, "ksp"), 3);

        assertNull(ksp.read(1).getError());
        assertNull(ksp.write(1, bytes(10, 1)).getError());
        assertNull(ksp.read(99).getError());
        assertNull(ksp.write(null, bytes(10, 1)).getError());
        assertNull(ksp.write(1, null).getError());
        assertNull(ksp.write(1, new byte[MAX_RECORD]).getError());
        assertNull(ksp.delete(99).getError());
        assertNull(ksp.compact().getError());
        assertNull(new PBStorageProvider(storagePath(dir, "empty")).read().getError());
    }
}
