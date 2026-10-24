package PBStorageTests.Bundle;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.SP.SPFail;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

/*
Storage lives in a single .dex folder holding data.pb (and index.pbi for keyed
storage).
*/
public class DexBundleTests {
    @AfterEach
    void closeStorages() throws Exception {
        closeAllStorages();
    }

    @TempDir
    Path dir;

    private static String[] namesIn(File folder) {
        String[] names = folder.list();
        Arrays.sort(names);
        return names;
    }

    private static void copyFolder(Path from, Path to) throws Exception {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : (Iterable<Path>) files::iterator) {
                Files.copy(source, to.resolve(from.relativize(source).toString()));
            }
        }
    }

    // ---- layout --------------------------------------------------------------

    @Test
    public void keyedStorageIsOneDexFolderWithDataAndIndex() throws Exception {
        String path = storagePath(dir, "users");
        track(new PBKeyStorageProvider<>(released(path), Integer.class)).write(1, bytes(10, 1));

        assertTrue(dexFolder(path).isDirectory());
        assertArrayEquals(new String[] { "data.pb", "dex.lock", "index.pbi", "journal.wal" }, namesIn(dexFolder(path)));
        assertArrayEquals(new String[] { "users.dex" }, namesIn(dir.toFile()), "nothing else is created beside it");
    }

    @Test
    public void keylessStorageIsOneDexFolderWithData() throws Exception {
        String path = storagePath(dir, "settings");
        track(new PBStorageProvider(released(path))).write(bytes(10, 1));

        assertArrayEquals(new String[] { "data.pb", "dex.lock", "journal.wal" }, namesIn(dexFolder(path)));
        assertArrayEquals(new String[] { "settings.dex" }, namesIn(dir.toFile()));
    }

    @Test
    public void getFolderReturnsTheDexFolder() throws Exception {
        String path = storagePath(dir, "users");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertEquals(dexFolder(path).toPath(), ksp.getFolder());
    }

    // ---- naming --------------------------------------------------------------

    @Test
    public void pathWithOrWithoutDexOpensTheSameStorage() throws Exception {
        String plain = storagePath(dir, "users");
        track(new PBKeyStorageProvider<>(released(plain), Integer.class)).write(1, bytes(10, 7));

        PBKeyStorageProvider<Integer> viaDex = track(new PBKeyStorageProvider<>(released(plain + ".dex"), Integer.class));
        assertArrayEquals(bytes(10, 7), data(viaDex.read(1)));
        assertArrayEquals(new String[] { "users.dex" }, namesIn(dir.toFile()), "no users.dex.dex is created");
    }

    @Test
    public void emptyOrNullPathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> track(new PBStorageProvider(released(""))));
        assertThrows(IllegalArgumentException.class, () -> track(new PBStorageProvider(released(null))));
    }

    // ---- moving and copying the folder --------------------------------------

    @Test
    public void movedFolderOpensWithAllData() throws Exception {
        String path = storagePath(dir, "users");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 1_000; i++) {
            ksp.write(i, bytes(10, i));
        }
        ksp.delete(5);

        released(path);   // close before moving the folder
        Path moved = Files.createDirectory(dir.resolve("elsewhere")).resolve("renamed.dex");
        Files.move(dexFolder(path).toPath(), moved);

        PBKeyStorageProvider<Integer> reopened = track(new PBKeyStorageProvider<>(released(moved.toString()), Integer.class));
        assertArrayEquals(bytes(10, 999), data(reopened.read(999)));
        assertFail(SPFail.KEY_NOT_FOUND, reopened.read(5));
        assertEquals(999, dataArray(reopened.read()).length);
    }

    @Test
    public void copiedFolderIsAnIndependentStorage() throws Exception {
        String original = storagePath(dir, "users");
        track(new PBKeyStorageProvider<>(released(original), Integer.class)).write(1, bytes(10, 1));

        String copy = storagePath(dir, "copy");
        copyFolder(dexFolder(original).toPath(), dexFolder(copy).toPath());

        PBKeyStorageProvider<Integer> copied = track(new PBKeyStorageProvider<>(released(copy), Integer.class));
        copied.write(1, bytes(10, 2));
        copied.write(2, bytes(10, 3));

        PBKeyStorageProvider<Integer> untouched = track(new PBKeyStorageProvider<>(released(original), Integer.class));
        assertArrayEquals(bytes(10, 1), data(untouched.read(1)));
        assertFail(SPFail.KEY_NOT_FOUND, untouched.read(2));
        assertArrayEquals(bytes(10, 2), data(track(new PBKeyStorageProvider<>(released(copy), Integer.class)).read(1)));
    }

    @Test
    public void deletingTheFolderRemovesTheStorage() throws Exception {
        String path = storagePath(dir, "users");
        track(new PBKeyStorageProvider<>(released(path), Integer.class)).write(1, bytes(10, 1));

        try (Stream<Path> files = Files.walk(dexFolder(path).toPath())) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }

        PBKeyStorageProvider<Integer> fresh = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        assertFail(SPFail.NULL_READ, fresh.read());
    }

    // ---- files from before .dex folders --------------------------------------

    /*
    Writes a storage, then moves its files out of the folder to look like the
    old loose users.pb / users.pbi layout.
    */
    private String makeLooseLayout(boolean withIndex) throws Exception {
        String path = storagePath(dir, "legacy");
        PBKeyStorageProvider<Integer> ksp = track(new PBKeyStorageProvider<>(released(path), Integer.class));
        for (int i = 0; i < 500; i++) {
            ksp.write(i, bytes(10, i));
        }
        ksp.delete(7);
        released(path);

        Files.move(datFile(path).toPath(), Path.of(path + ".pb"));
        if (withIndex) {
            Files.move(indexFile(path).toPath(), Path.of(path + ".pbi"));
        } else {
            Files.delete(indexFile(path).toPath());
        }
        Files.delete(new File(dexFolder(path), "dex.lock").toPath());
        Files.delete(new File(dexFolder(path), "journal.wal").toPath());
        Files.delete(dexFolder(path).toPath());
        return path;
    }

    @Test
    public void looseDataAndIndexFilesAreMovedIntoTheFolder() throws Exception {
        String path = makeLooseLayout(true);

        PBKeyStorageProvider<Integer> opened = track(new PBKeyStorageProvider<>(released(path), Integer.class));

        assertArrayEquals(new String[] { "legacy.dex" }, namesIn(dir.toFile()), "loose files moved, none left behind");
        assertArrayEquals(new String[] { "data.pb", "dex.lock", "index.pbi", "journal.wal" }, namesIn(dexFolder(path)));
        assertArrayEquals(bytes(10, 499), data(opened.read(499)));
        assertFail(SPFail.KEY_NOT_FOUND, opened.read(7));
    }

    @Test
    public void looseDataFileWithoutIndexIsMovedAndIndexed() throws Exception {
        String path = makeLooseLayout(false);

        PBKeyStorageProvider<Integer> opened = track(new PBKeyStorageProvider<>(released(path), Integer.class));

        assertArrayEquals(new String[] { "data.pb", "dex.lock", "index.pbi", "journal.wal" }, namesIn(dexFolder(path)));
        assertArrayEquals(bytes(10, 250), data(opened.read(250)));
        assertEquals(499, dataArray(opened.read()).length);
    }

    @Test
    public void looseFilesAreLeftAloneWhenTheFolderAlreadyExists() throws Exception {
        String path = storagePath(dir, "users");
        track(new PBKeyStorageProvider<>(released(path), Integer.class)).write(1, bytes(10, 1));
        Files.write(Path.of(path + ".pb"), new byte[] { 1, 2, 3 });

        PBKeyStorageProvider<Integer> opened = track(new PBKeyStorageProvider<>(released(path), Integer.class));

        assertArrayEquals(bytes(10, 1), data(opened.read(1)), "the .dex folder wins");
        assertTrue(new File(path + ".pb").isFile(), "the stray file isn't touched");
    }

    // ---- errors --------------------------------------------------------------

    @Test
    public void regularFileNamedDexIsRejected() throws Exception {
        String path = storagePath(dir, "users");
        Files.write(Path.of(path + ".dex"), new byte[] { 1 });

        assertThrows(java.io.IOException.class, () -> track(new PBKeyStorageProvider<>(released(path), Integer.class)));
    }

    @Test
    public void missingParentFolderIsRejected() {
        String path = dir.resolve("no-such-folder").resolve("users").toString();

        assertThrows(java.io.IOException.class, () -> track(new PBStorageProvider(released(path))));
        assertFalse(new File(dir.toFile(), "no-such-folder").exists(), "parent folders are not created");
    }
}
