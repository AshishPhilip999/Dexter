package Utility.StorageProviders.PageBasedStorageProvider.Index;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/*
Disk-based B+ tree mapping byte[] keys to byte[] values, stored in an
IndexFile. Keys are ordered by unsigned byte-by-byte comparison.

- get / put / remove: walk from the root to a leaf, reading each page through
  the index file's cache, so only the pages on the path are in memory.
- put splits a node when it no longer fits its page; a root split adds a level.
- remove deletes the entry from its leaf without merging nodes, so a leaf can
  become empty; searches still work, and its space is reused by later inserts.
- ceiling finds the smallest key >= a given key (used for best-fit lookups).
*/
public final class BPlusTree {
    public static final int MAX_KEY_SIZE = 1024;
    public static final int MAX_VALUE_SIZE = 64;

    private final IndexFile file;
    private final int tree;

    public BPlusTree(IndexFile file, int tree) {
        if (tree < 0 || tree >= IndexFile.MAX_TREES) {
            throw new IllegalArgumentException("Tree number must be 0.." + (IndexFile.MAX_TREES - 1));
        }
        this.file = file;
        this.tree = tree;
    }

    /*
    Value stored under key, or null.
    */
    public byte[] get(byte[] key) throws Exception {
        Node leaf = this.findLeaf(key);
        if (leaf == null) {
            return null;
        }
        int index = indexOf(leaf.keys, key);
        return index >= 0 ? leaf.values.get(index) : null;
    }

    /*
    Inserts or replaces. Returns the previous value, or null if the key is new.
    */
    public byte[] put(byte[] key, byte[] value) throws Exception {
        checkSizes(key, value);

        int root = this.file.getRoot(this.tree);
        if (root < 0) {
            Node leaf = this.file.allocate(true);
            leaf.keys.add(key);
            leaf.values.add(value);
            this.file.write(leaf);
            this.file.setRoot(this.tree, leaf.pageNumber);
            return null;
        }

        byte[][] previous = new byte[1][];
        Split split = this.insert(root, key, value, previous);
        if (split != null) {
            Node newRoot = this.file.allocate(false);
            newRoot.children.add(root);
            newRoot.keys.add(split.separator);
            newRoot.children.add(split.right);
            this.file.write(newRoot);
            this.file.setRoot(this.tree, newRoot.pageNumber);
        }
        return previous[0];
    }

    /*
    Removes key. Returns its value, or null if it wasn't there.
    */
    public byte[] remove(byte[] key) throws Exception {
        Node leaf = this.findLeaf(key);
        if (leaf == null) {
            return null;
        }
        int index = indexOf(leaf.keys, key);
        if (index < 0) {
            return null;
        }
        leaf.keys.remove(index);
        byte[] value = leaf.values.remove(index);
        this.file.write(leaf);
        return value;
    }

    /*
    Entry with the smallest key >= key, or null if there is none.
    */
    public Map.Entry<byte[], byte[]> ceiling(byte[] key) throws Exception {
        Node leaf = this.findLeaf(key);
        if (leaf == null) {
            return null;
        }

        int index = lowerBound(leaf.keys, key);
        while (true) {
            if (index < leaf.size()) {
                return new AbstractMap.SimpleImmutableEntry<>(leaf.keys.get(index), leaf.values.get(index));
            }
            if (leaf.next < 0) {
                return null;
            }
            leaf = this.file.read(leaf.next);
            index = 0;
        }
    }

    public boolean isEmpty() throws Exception {
        return this.ceiling(new byte[0]) == null;
    }

    /*
    Number of levels: 0 for an empty tree, 1 when the root is a leaf.
    */
    public int height() throws Exception {
        int page = this.file.getRoot(this.tree);
        int height = 0;
        while (page >= 0) {
            height++;
            Node node = this.file.read(page);
            page = node.leaf ? -1 : node.children.get(0);
        }
        return height;
    }

    // ---- internals ----------------------------------------------------------

    private static final class Split {
        final byte[] separator;
        final int right;

        Split(byte[] separator, int right) {
            this.separator = separator;
            this.right = right;
        }
    }

    private Node findLeaf(byte[] key) throws Exception {
        int page = this.file.getRoot(this.tree);
        if (page < 0) {
            return null;
        }
        Node node = this.file.read(page);
        while (!node.leaf) {
            node = this.file.read(node.children.get(upperBound(node.keys, key)));
        }
        return node;
    }

    private Split insert(int page, byte[] key, byte[] value, byte[][] previous) throws Exception {
        Node node = this.file.read(page);

        if (node.leaf) {
            int index = indexOf(node.keys, key);
            if (index >= 0) {
                previous[0] = node.values.set(index, value);
                this.file.write(node);
                return null;
            }
            int position = -(index + 1);
            node.keys.add(position, key);
            node.values.add(position, value);
            return this.writeOrSplit(node);
        }

        int childIndex = upperBound(node.keys, key);
        Split split = this.insert(node.children.get(childIndex), key, value, previous);
        if (split == null) {
            return null;
        }
        node.keys.add(childIndex, split.separator);
        node.children.add(childIndex + 1, split.right);
        return this.writeOrSplit(node);
    }

    private Split writeOrSplit(Node node) throws Exception {
        if (node.serializedSize() <= IndexFile.PAGE_SIZE) {
            this.file.write(node);
            return null;
        }
        return node.leaf ? this.splitLeaf(node) : this.splitInternal(node);
    }

    /*
    Moves the upper half (by bytes) of a full leaf into a new right sibling.
    The right sibling's first key becomes the separator in the parent.
    */
    private Split splitLeaf(Node left) throws Exception {
        int at = splitPoint(left, 1, left.size() - 1);
        Node right = this.file.allocate(true);

        right.keys.addAll(left.keys.subList(at, left.size()));
        right.values.addAll(left.values.subList(at, left.size()));
        truncate(left.keys, at);
        truncate(left.values, at);

        right.next = left.next;
        left.next = right.pageNumber;

        this.file.write(right);
        this.file.write(left);
        return new Split(right.keys.get(0), right.pageNumber);
    }

    /*
    Splits a full internal node around its middle key (by bytes), which moves
    up to the parent and is kept in neither half.
    */
    private Split splitInternal(Node left) throws Exception {
        int at = splitPoint(left, 1, left.size() - 2);
        Node right = this.file.allocate(false);
        byte[] separator = left.keys.get(at);

        right.keys.addAll(left.keys.subList(at + 1, left.size()));
        right.children.addAll(left.children.subList(at + 1, left.children.size()));
        truncate(left.keys, at);
        truncate(left.children, at + 1);

        this.file.write(right);
        this.file.write(left);
        return new Split(separator, right.pageNumber);
    }

    /*
    First index at which the entries before it hold at least half the node's
    bytes, clamped to [min, max] so both halves keep entries.
    */
    private static int splitPoint(Node node, int min, int max) {
        int total = 0;
        for (int i = 0; i < node.size(); i++) {
            total += node.entrySize(i);
        }
        int running = 0;
        int at = node.size() / 2;
        for (int i = 0; i < node.size(); i++) {
            running += node.entrySize(i);
            if (running >= total / 2) {
                at = i + 1;
                break;
            }
        }
        return Math.max(min, Math.min(max, at));
    }

    private static <T> void truncate(List<T> list, int size) {
        list.subList(size, list.size()).clear();
    }

    private static void checkSizes(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Key and value cannot be null");
        }
        if (key.length > MAX_KEY_SIZE) {
            throw new IllegalArgumentException("Key of " + key.length + " bytes exceeds " + MAX_KEY_SIZE);
        }
        if (value.length > MAX_VALUE_SIZE) {
            throw new IllegalArgumentException("Value of " + value.length + " bytes exceeds " + MAX_VALUE_SIZE);
        }
    }

    static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /*
    Index of key, or -(insertion point) - 1 if absent.
    */
    private static int indexOf(List<byte[]> keys, byte[] key) {
        int low = 0;
        int high = keys.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int c = compare(keys.get(mid), key);
            if (c < 0) {
                low = mid + 1;
            } else if (c > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    /*
    Number of keys <= key: the child to follow in an internal node.
    */
    private static int upperBound(List<byte[]> keys, byte[] key) {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (compare(keys.get(mid), key) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /*
    Number of keys < key: first position whose key is >= key.
    */
    private static int lowerBound(List<byte[]> keys, byte[] key) {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (compare(keys.get(mid), key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
