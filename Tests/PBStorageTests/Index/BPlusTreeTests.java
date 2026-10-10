package PBStorageTests.Index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import Utility.Constants.Extension;
import Utility.StorageProviders.PageBasedStorageProvider.Index.BPlusTree;
import Utility.StorageProviders.PageBasedStorageProvider.Index.IndexFile;

public class BPlusTreeTests {
    @TempDir
    Path dir;

    private String path() {
        return dir.resolve("index").toString();
    }

    private IndexFile open(int cachePages) throws Exception {
        return new IndexFile(path(), Extension.PBI, cachePages);
    }

    private static byte[] intKey(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] value(int value) {
        return ByteBuffer.allocate(8).putLong(value * 31L).array();
    }

    // ---- basics -------------------------------------------------------------

    @Test
    public void emptyTree() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);

        assertNull(tree.get(intKey(1)));
        assertNull(tree.remove(intKey(1)));
        assertNull(tree.ceiling(intKey(1)));
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.height());
    }

    @Test
    public void putGetReplaceRemove() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);

        assertNull(tree.put(intKey(1), value(1)));
        assertArrayEquals(value(1), tree.get(intKey(1)));

        assertArrayEquals(value(1), tree.put(intKey(1), value(2)), "put returns the previous value");
        assertArrayEquals(value(2), tree.get(intKey(1)));

        assertArrayEquals(value(2), tree.remove(intKey(1)));
        assertNull(tree.get(intKey(1)));
        assertNull(tree.remove(intKey(1)), "second remove finds nothing");
        assertTrue(tree.isEmpty());
    }

    @Test
    public void emptyKeyAndEmptyValueAreAllowed() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);
        tree.put(new byte[0], new byte[0]);
        tree.put(new byte[] { 0 }, new byte[] { 1 });

        assertArrayEquals(new byte[0], tree.get(new byte[0]));
        assertArrayEquals(new byte[] { 1 }, tree.get(new byte[] { 0 }));
    }

    @Test
    public void sizeLimitsAreEnforced() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);

        assertNull(tree.put(new byte[BPlusTree.MAX_KEY_SIZE], new byte[BPlusTree.MAX_VALUE_SIZE]));
        assertThrows(IllegalArgumentException.class, () -> tree.put(new byte[BPlusTree.MAX_KEY_SIZE + 1], value(1)));
        assertThrows(IllegalArgumentException.class, () -> tree.put(intKey(1), new byte[BPlusTree.MAX_VALUE_SIZE + 1]));
        assertThrows(IllegalArgumentException.class, () -> tree.put(null, value(1)));
        assertThrows(IllegalArgumentException.class, () -> tree.put(intKey(1), null));
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(open(16), IndexFile.MAX_TREES));
    }

    // ---- growth, splits, persistence ----------------------------------------

    @Test
    public void sequentialInsertsSplitIntoSeveralLevels() throws Exception {
        BPlusTree tree = new BPlusTree(open(64), 0);
        int count = 100_000;
        for (int i = 0; i < count; i++) {
            tree.put(intKey(i), value(i));
        }

        assertTrue(tree.height() >= 3, "100k keys need at least 3 levels, got " + tree.height());
        for (int i = 0; i < count; i++) {
            assertArrayEquals(value(i), tree.get(intKey(i)), "key " + i);
        }
        assertNull(tree.get(intKey(count)));
    }

    @Test
    public void reverseOrderInsertsWork() throws Exception {
        BPlusTree tree = new BPlusTree(open(64), 0);
        for (int i = 30_000; i > 0; i--) {
            tree.put(intKey(i), value(i));
        }
        for (int i = 1; i <= 30_000; i++) {
            assertArrayEquals(value(i), tree.get(intKey(i)));
        }
    }

    @Test
    public void treePersistsAcrossReopen() throws Exception {
        BPlusTree tree = new BPlusTree(open(64), 0);
        for (int i = 0; i < 20_000; i++) {
            tree.put(intKey(i * 7), value(i));
        }
        int height = tree.height();

        BPlusTree reopened = new BPlusTree(open(64), 0);
        assertEquals(height, reopened.height());
        for (int i = 0; i < 20_000; i++) {
            assertArrayEquals(value(i), reopened.get(intKey(i * 7)));
        }
        assertNull(reopened.get(intKey(1)));
    }

    @Test
    public void tinyCacheStillGivesCorrectResults() throws Exception {
        IndexFile file = open(2);
        BPlusTree tree = new BPlusTree(file, 0);
        Random random = new Random(7);
        int[] keys = random.ints(30_000, 0, Integer.MAX_VALUE).distinct().toArray();

        for (int key : keys) {
            tree.put(intKey(key), value(key));
        }
        for (int key : keys) {
            assertArrayEquals(value(key), tree.get(intKey(key)));
        }
        assertTrue(file.getCachedPageCount() <= 2, "cache must stay within its capacity");
        assertTrue(file.getPageCount() > 100, "the tree is far larger than the cache");
    }

    @Test
    public void variableLengthKeysUpToTheMaximum() throws Exception {
        BPlusTree tree = new BPlusTree(open(64), 0);
        Random random = new Random(11);
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);

        for (int i = 0; i < 3_000; i++) {
            byte[] key = new byte[1 + random.nextInt(BPlusTree.MAX_KEY_SIZE)];
            random.nextBytes(key);
            byte[] val = new byte[random.nextInt(BPlusTree.MAX_VALUE_SIZE + 1)];
            random.nextBytes(val);
            tree.put(key, val);
            model.put(key, val);
        }

        assertTrue(tree.height() >= 3, "large keys force deep splits");
        BPlusTree reopened = new BPlusTree(open(64), 0);
        for (Map.Entry<byte[], byte[]> e : model.entrySet()) {
            assertArrayEquals(e.getValue(), reopened.get(e.getKey()));
        }
    }

    // ---- randomized check against a reference TreeMap -----------------------

    @Test
    public void randomOperationsMatchTreeMap() throws Exception {
        BPlusTree tree = new BPlusTree(open(32), 0);
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);
        Random random = new Random(42);

        for (int op = 0; op < 60_000; op++) {
            byte[] key = intKey(random.nextInt(5_000));
            int choice = random.nextInt(10);

            if (choice < 5) {
                byte[] val = value(random.nextInt());
                assertArrayEquals(model.put(key, val), tree.put(key, val), "put previous value, op " + op);
            } else if (choice < 8) {
                assertArrayEquals(model.remove(key), tree.remove(key), "remove, op " + op);
            } else if (choice < 9) {
                assertArrayEquals(model.get(key), tree.get(key), "get, op " + op);
            } else {
                Map.Entry<byte[], byte[]> expected = model.ceilingEntry(key);
                Map.Entry<byte[], byte[]> actual = tree.ceiling(key);
                if (expected == null) {
                    assertNull(actual, "ceiling, op " + op);
                } else {
                    assertArrayEquals(expected.getKey(), actual.getKey(), "ceiling key, op " + op);
                    assertArrayEquals(expected.getValue(), actual.getValue(), "ceiling value, op " + op);
                }
            }

            if (op % 20_000 == 19_999) {
                tree = new BPlusTree(open(32), 0);   // reopen mid-run
            }
        }

        for (int k = 0; k < 5_000; k++) {
            assertArrayEquals(model.get(intKey(k)), tree.get(intKey(k)), "final check, key " + k);
        }
    }

    // ---- ceiling ------------------------------------------------------------

    @Test
    public void ceilingFindsExactNextAndNothingPastTheEnd() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);
        for (int i = 10; i <= 100; i += 10) {
            tree.put(intKey(i), value(i));
        }

        assertArrayEquals(intKey(10), tree.ceiling(intKey(0)).getKey(), "before first");
        assertArrayEquals(intKey(50), tree.ceiling(intKey(50)).getKey(), "exact");
        assertArrayEquals(intKey(60), tree.ceiling(intKey(51)).getKey(), "between");
        assertNull(tree.ceiling(intKey(101)), "after last");
    }

    @Test
    public void ceilingCrossesLeavesAndSkipsEmptiedLeaves() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);
        for (int i = 0; i < 10_000; i++) {
            tree.put(intKey(i), value(i));
        }
        // Empty out a long run of keys, which leaves whole leaves empty.
        for (int i = 1_000; i < 9_000; i++) {
            tree.remove(intKey(i));
        }

        assertArrayEquals(intKey(9_000), tree.ceiling(intKey(1_000)).getKey());
        assertArrayEquals(intKey(999), tree.ceiling(intKey(999)).getKey());
        assertNull(tree.ceiling(intKey(10_000)));
    }

    @Test
    public void keysCompareAsUnsignedBytes() throws Exception {
        BPlusTree tree = new BPlusTree(open(16), 0);
        tree.put(new byte[] { (byte) 0xFF }, value(1));
        tree.put(new byte[] { (byte) 0x80 }, value(2));
        tree.put(new byte[] { 0x7F }, value(3));
        tree.put(new byte[] { 0x00 }, value(4));

        assertArrayEquals(new byte[] { 0x00 }, tree.ceiling(new byte[0]).getKey());
        assertArrayEquals(new byte[] { (byte) 0x80 }, tree.ceiling(new byte[] { 0x7F, 0x01 }).getKey());
        assertArrayEquals(new byte[] { (byte) 0xFF }, tree.ceiling(new byte[] { (byte) 0x81 }).getKey());
    }

    // ---- removal ------------------------------------------------------------

    @Test
    public void removeEverythingThenReinsert() throws Exception {
        BPlusTree tree = new BPlusTree(open(32), 0);
        for (int i = 0; i < 20_000; i++) {
            tree.put(intKey(i), value(i));
        }
        for (int i = 0; i < 20_000; i++) {
            assertArrayEquals(value(i), tree.remove(intKey(i)));
        }
        assertTrue(tree.isEmpty());

        for (int i = 0; i < 20_000; i++) {
            tree.put(intKey(i), value(i + 1));
        }
        for (int i = 0; i < 20_000; i++) {
            assertArrayEquals(value(i + 1), tree.get(intKey(i)));
        }
    }

    // ---- several trees, clearing, corruption --------------------------------

    @Test
    public void treesInOneFileAreIndependent() throws Exception {
        IndexFile file = open(32);
        BPlusTree a = new BPlusTree(file, 0);
        BPlusTree b = new BPlusTree(file, 1);
        for (int i = 0; i < 5_000; i++) {
            a.put(intKey(i), value(i));
            b.put(intKey(i), value(-i));
        }
        b.remove(intKey(3));

        IndexFile reopened = open(32);
        assertArrayEquals(value(3), new BPlusTree(reopened, 0).get(intKey(3)));
        assertNull(new BPlusTree(reopened, 1).get(intKey(3)));
        assertArrayEquals(value(-4), new BPlusTree(reopened, 1).get(intKey(4)));
    }

    @Test
    public void clearEmptiesEveryTreeButKeepsDataFileId() throws Exception {
        IndexFile file = open(32);
        file.setDataFileId(1234L);
        BPlusTree a = new BPlusTree(file, 0);
        BPlusTree b = new BPlusTree(file, 1);
        for (int i = 0; i < 5_000; i++) {
            a.put(intKey(i), value(i));
            b.put(intKey(i), value(i));
        }

        file.clear();

        assertTrue(a.isEmpty());
        assertTrue(b.isEmpty());
        assertEquals(0, file.getPageCount());
        assertEquals(1234L, file.getDataFileId());

        a.put(intKey(1), value(1));
        IndexFile reopened = open(32);
        assertEquals(1234L, reopened.getDataFileId());
        assertArrayEquals(value(1), new BPlusTree(reopened, 0).get(intKey(1)));
        assertTrue(new BPlusTree(reopened, 1).isEmpty());
    }

    @Test
    public void openingAFileThatIsNotAnIndexThrows() throws Exception {
        try (RandomAccessFile junk = new RandomAccessFile(path() + ".pbi", "rw")) {
            junk.write(new byte[128]);
        }
        assertThrows(IllegalStateException.class, () -> open(16));
    }
}
