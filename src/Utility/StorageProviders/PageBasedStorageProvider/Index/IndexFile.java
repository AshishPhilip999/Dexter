package Utility.StorageProviders.PageBasedStorageProvider.Index;

import java.nio.ByteBuffer;

import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.LruCache;

/*
The index file (.pbi): a header followed by fixed-size pages, each holding one
B+ tree node. Several trees share the file; each has its root page number in
the header.

Header (64 bytes, big-endian):
    magic(4)        "PBI1"
    pageCount(4)    pages allocated so far
    dataFileId(8)   id of the data file this index belongs to
    roots(4 x 8)    root page number per tree, -1 = empty tree
    padding

Pages are read through an LRU cache and written straight to disk.
*/
public final class IndexFile {
    public static final int PAGE_SIZE = 4096;
    public static final int MAX_TREES = 8;
    public static final int DEFAULT_CACHE_PAGES = 1024;

    static final int HEADER_SIZE = 64;
    private static final int MAGIC = 0x50424931; // "PBI1"

    private final RandomAccessFileService file;
    private final LruCache<Integer, Node> cache;

    private int pageCount;
    private long dataFileId;
    private final int[] roots = new int[MAX_TREES];

    public IndexFile(String path, Extension extension) throws Exception {
        this(path, extension, DEFAULT_CACHE_PAGES);
    }

    public IndexFile(String path, Extension extension, int cachePages) throws Exception {
        this.file = new RandomAccessFileService(path, extension);
        this.cache = new LruCache<>(cachePages);

        long length = this.file.length();
        if (length == 0) {
            this.resetState();
            this.writeHeader();
        } else if (length < HEADER_SIZE) {
            throw new IllegalStateException("Corrupt index file: missing header");
        } else {
            this.readHeader();
        }
    }

    public long getDataFileId() {
        return this.dataFileId;
    }

    public void setDataFileId(long dataFileId) throws Exception {
        this.dataFileId = dataFileId;
        this.writeHeader();
    }

    public int getPageCount() {
        return this.pageCount;
    }

    public int getCachedPageCount() {
        return this.cache.size();
    }

    /*
    Drops every tree and page. The data file id is kept.
    */
    public void clear() throws Exception {
        this.file.truncate();
        long id = this.dataFileId;
        this.resetState();
        this.dataFileId = id;
        this.cache.clear();
        this.writeHeader();
    }

    int getRoot(int tree) {
        return this.roots[tree];
    }

    void setRoot(int tree, int pageNumber) throws Exception {
        this.roots[tree] = pageNumber;
        this.writeHeader();
    }

    /*
    A new, empty node with the next page number. The caller writes it.
    */
    Node allocate(boolean leaf) throws Exception {
        Node node = new Node(this.pageCount, leaf);
        this.pageCount++;
        this.writeHeader();
        return node;
    }

    Node read(int pageNumber) throws Exception {
        Node node = this.cache.get(pageNumber);
        if (node != null) {
            return node;
        }
        if (pageNumber < 0 || pageNumber >= this.pageCount) {
            throw new IllegalStateException("Index page " + pageNumber + " does not exist");
        }

        byte[] page = this.file.read(this.offsetOf(pageNumber), PAGE_SIZE);
        node = Node.parse(pageNumber, page);
        this.cache.put(pageNumber, node);
        return node;
    }

    void write(Node node) throws Exception {
        this.file.write(this.offsetOf(node.pageNumber), node.serialize(PAGE_SIZE));
        this.cache.put(node.pageNumber, node);
    }

    private long offsetOf(int pageNumber) {
        return HEADER_SIZE + (long) pageNumber * PAGE_SIZE;
    }

    private void resetState() {
        this.pageCount = 0;
        this.dataFileId = 0;
        for (int i = 0; i < MAX_TREES; i++) {
            this.roots[i] = -1;
        }
    }

    private void readHeader() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(this.file.read(0, HEADER_SIZE));
        if (buffer.getInt() != MAGIC) {
            throw new IllegalStateException("Not an index file (bad magic number)");
        }
        this.pageCount = buffer.getInt();
        this.dataFileId = buffer.getLong();
        for (int i = 0; i < MAX_TREES; i++) {
            this.roots[i] = buffer.getInt();
        }
    }

    private void writeHeader() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putInt(MAGIC);
        buffer.putInt(this.pageCount);
        buffer.putLong(this.dataFileId);
        for (int i = 0; i < MAX_TREES; i++) {
            buffer.putInt(this.roots[i]);
        }
        this.file.write(0, buffer.array());
    }
}
