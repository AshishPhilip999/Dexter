package Utility.StorageProviders.PageBasedStorageProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;
import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.LruCache;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PBOffSets;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Page;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PageHeader;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;

/*
Thread safety: one instance can be shared by any number of threads. Writes and
deletes take an exclusive lock; reads share a lock, so they run in parallel
with each other but never alongside a write. Use one instance per storage:
two instances (or two programs) on the same .dex folder are not coordinated.
*/
public class PBStorageProvider {
    /*
    Data pages kept in memory at most; older ones are dropped and re-read from
    disk when needed. 1024 pages = about 4 MB of page data.
    */
    public static final int DEFAULT_CACHE_PAGES = 1024;

    private static final byte[] NO_KEY = new byte[0];

    /*
    Exclusive for write / delete, shared for read.
    */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /*
    The last 8 bytes of the file header hold a random id identifying this data
    file, so an index file can tell whether it belongs to it.
    */
    private static final int FILE_ID_OFFSET = PBOffSets.FILE_HEADER.getValue() - Long.BYTES;
    private static final int MAX_KEY_TYPE_NAME = FILE_ID_OFFSET - Short.BYTES;

    /*
    Number of pages in the file. Pages themselves are loaded on demand.
    */
    private int pageCount;

    /*
    Recently used pages, page number -> page. Writes go straight to disk, so an
    evicted page never needs saving.
    */
    private final LruCache<Integer, Page> pageCache;

    /*
    Class name of the key type stored in the file header, or null if none.
    */
    private String storedKeyType;

    private long fileId;

    private RandomAccessFileService randomAccessFileService;

    /*
    The storage's .dex folder.
    */
    private final Path folder;

    /*
    Opens (or creates) the storage: the folder path + ".dex" with the data file
    data.pb inside ("data/users" and "data/users.dex" are the same storage).
    Only the file header is read; pages are loaded when first used, so opening
    takes the same time for any file size.
    */
    public PBStorageProvider(String path) throws Exception {
        this.folder = DexBundle.open(path);
        this.randomAccessFileService = new RandomAccessFileService(DexBundle.dataPath(this.folder), Extension.PB);
        this.pageCache = new LruCache<>(DEFAULT_CACHE_PAGES);
        this.loadFileHeader();
        this.pageCount = this.countPages();
    }

    /*
    Drops all existing pages (in memory and on disk) and writes a single new page
    with this data.
    SUCCESS: PBSuccess.WRITE, result.getData() holds the written data
    FAIL:    NULL_WRITE, DATA_TOO_LARGE, WRITE_ERROR
    */
    public PBResult write(byte[] data) {
        PBFail invalid = this.validateRecord(0, data);
        if (invalid != null) {
            return PBResult.of(invalid);
        }

        this.writeLock().lock();
        try {
            this.createNewPage(data);
        } catch (Exception e) {
            return PBResult.of(PBFail.WRITE_ERROR);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.WRITE, data);
    }

    /*
    Erases everything: every page and record is removed from memory and the
    file is truncated back to just its header (the stored key type is kept).
    SUCCESS: PBSuccess.DELETE
    FAIL:    DELETE_ERROR
    */
    public PBResult delete() {
        this.writeLock().lock();
        try {
            this.clearAll();
        } catch (Exception e) {
            return PBResult.of(PBFail.DELETE_ERROR);
        } finally {
            this.writeLock().unlock();
        }

        return PBResult.of(PBSuccess.DELETE);
    }

    /*
    The data written by write(data): the first live record in the file.
    SUCCESS: PBSuccess.READ, result.getData() holds the record
    FAIL:    NULL_READ (nothing stored), READ_ERROR
    */
    public PBResult read() {
        this.readLock().lock();
        try {
            for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
                for (Slot slot : this.getPage(pageNumber).getSlots()) {
                    if (!slot.isDeleted()) {
                        return PBResult.of(PBSuccess.READ, this.readSlot(slot));
                    }
                }
            }
        } catch (Exception e) {
            return PBResult.of(PBFail.READ_ERROR);
        } finally {
            this.readLock().unlock();
        }

        return PBResult.of(PBFail.NULL_READ);
    }

    /*
    Shared lock for operations that only read. Subclasses wrap their read
    operations in it.
    */
    protected Lock readLock() {
        return this.lock.readLock();
    }

    /*
    Exclusive lock for operations that change the storage. Reentrant, so a
    subclass holding it can call the parent's write / delete.
    */
    protected Lock writeLock() {
        return this.lock.writeLock();
    }

    /*
    Data of every live slot, in page and slot order, one entry per record, so
    each entry is exactly the byte[] that was written and can be deserialized
    back into its own object. Key bytes of keyed records are not included;
    deleted slots (old versions of overwritten keys) are skipped.
    */
    protected byte[][] readAllRecords() throws Exception {
        List<byte[]> records = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < this.pageCount; pageNumber++) {
            for (Slot slot : this.getPage(pageNumber).getSlots()) {
                if (!slot.isDeleted()) {
                    records.add(this.readSlot(slot));
                }
            }
        }

        return records.toArray(new byte[0][]);
    }

    /*
    Key type class name stored in the file header, or null if none was stored.
    */
    protected String getStoredKeyType() {
        return this.storedKeyType;
    }

    /*
    Stores the key type class name in the file header.
    */
    protected void setStoredKeyType(String keyTypeName) throws Exception {
        this.storedKeyType = keyTypeName;
        this.writeFileHeader();
    }

    /*
    The storage's .dex folder.
    */
    public Path getFolder() {
        return this.folder;
    }

    /*
    Random id of this data file, kept in the file header (never 0).
    */
    protected long getFileId() {
        return this.fileId;
    }

    protected int getPageCount() {
        return this.pageCount;
    }

    /*
    Data pages currently held in memory.
    */
    protected int getCachedPageCount() {
        return this.pageCache.size();
    }

    /*
    The page, from the cache or, if not cached, read from disk in one call.
    */
    protected Page getPage(int pageNumber) throws Exception {
        if (pageNumber < 0 || pageNumber >= this.pageCount) {
            return null;
        }

        Page page = this.pageCache.get(pageNumber);
        if (page == null) {
            page = this.loadPage(pageNumber);
            this.pageCache.put(pageNumber, page);
        }
        return page;
    }

    /*
    Checks that data is present and key + data fit into one page.
    Returns the failure, or null if the record is valid.
    */
    protected PBFail validateRecord(int keyLength, byte[] data) {
        if (data == null || data.length == 0) {
            return PBFail.NULL_WRITE;
        }

        int maxRecordSize = PBOffSets.PAGE.getValue() - PBOffSets.HEADER.getValue() - PBOffSets.SLOT.getValue();
        if (keyLength + data.length > maxRecordSize) {
            return PBFail.DATA_TOO_LARGE;
        }

        return null;
    }

    /*
    Places the record [key bytes][data bytes] and returns where it went:
    1. A deleted slot offered by takeFreeSlot (best fit) is reused in place.
    2. Otherwise appended to the last page: slot entry at freeSpaceStart, data
       just below freeSpaceEnd.
    3. If the last page is full too, a new page is allocated and used.
    Callers must have checked the record fits a page (validateRecord).
    */
    protected RecordID placeRecord(byte[] keyBytes, byte[] data) throws Exception {
        short recordLength = (short) (keyBytes.length + data.length);

        RecordID reused = this.takeFreeSlot(recordLength);
        if (reused != null) {
            Page page = this.getPage(reused.pageNumber);
            Slot slot = this.getSlot(reused);

            slot.reuse(recordLength, keyBytes.length);
            this.writeRecord(slot.getOffset(), keyBytes, data);
            this.writeSlotEntry(this.getSlotOffSet(page.getHeader(), reused.slotNumber), slot);

            return reused;
        }

        Page page = this.getPageWithSpace(recordLength);
        short slotNumber = this.appendSlot(page, keyBytes, data);
        return new RecordID(page.getPageNumber(), slotNumber);
    }

    /*
    Hook: a deleted slot whose fullLength fits recordLength, removed from the
    free list, or null to append instead. The base class keeps no free list
    (keyless storage never frees single slots); PBKeyStorageProvider does.
    The returned slot must be deleted and big enough.
    */
    protected RecordID takeFreeSlot(short recordLength) throws Exception {
        return null;
    }

    /*
    Hook: called after a slot has been marked deleted, so it can be offered for
    reuse later.
    */
    protected void onSlotFreed(RecordID recordID, Slot slot) throws Exception {
    }

    protected void markSlotDeleted(RecordID recordID) throws Exception {
        Page page = this.getPage(recordID.pageNumber);
        Slot slot = this.getSlot(recordID);
        if (page == null || slot == null) {
            return;
        }

        if (slot.isDeleted()) {
            return;
        }

        slot.deletePage(true);
        this.writeSlotEntry(this.getSlotOffSet(page.getHeader(), recordID.slotNumber), slot);
        this.onSlotFreed(recordID, slot);
    }

    protected Slot getSlot(RecordID recordID) throws Exception {
        Page page = this.getPage(recordID.pageNumber);
        if (page == null) {
            return null;
        }

        List<Slot> slots = page.getSlots();
        if (recordID.slotNumber < 0 || recordID.slotNumber >= slots.size()) {
            return null;
        }
        return slots.get(recordID.slotNumber);
    }

    /*
    Data bytes of a slot (the bytes after the key).
    */
    protected byte[] readSlot(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getDataOffset(), slot.getDataLength());
    }

    /*
    Key bytes of a slot (the first keyLength bytes at its offset).
    */
    protected byte[] readSlotKey(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getOffset(), slot.getKeyLength());
    }

    /*
    The whole record [key bytes][data bytes] of a slot, in one read.
    */
    protected byte[] readRecord(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getOffset(), (int) slot.getLength());
    }

    private Page getPageWithSpace(short recordLength) throws Exception {
        if (this.pageCount > 0) {
            Page lastPage = this.getPage(this.pageCount - 1);
            if (this.hasSpace(lastPage, recordLength)) {
                return lastPage;
            }
        }

        return this.allocatePage();
    }

    private boolean hasSpace(Page page, short recordLength) {
        PageHeader header = page.getHeader();
        long available = header.getFreeSpaceEnd() - header.getFreeSpaceStart();
        return available >= (PBOffSets.SLOT.getValue() + recordLength);
    }

    private Page allocatePage() throws Exception {
        int pageNumber = this.pageCount;
        long pageOffSet = this.getPageOffSet(pageNumber);

        PageHeader pageHeader = new PageHeader(pageOffSet);
        Page page = new Page(pageNumber);
        page.setPageHeader(pageHeader);

        this.pageCount++;
        this.pageCache.put(pageNumber, page);
        this.writePageHeader(pageHeader);

        return page;
    }

    /*
    Places a new slot into the page with the record [key bytes][data bytes],
    persists the record, the slot entry and the updated header, and returns
    the slot number within the page. keyBytes is empty for keyless records.
    The slot's fullLength is the record length it is created with.
    */
    private short appendSlot(Page page, byte[] keyBytes, byte[] data) throws Exception {
        PageHeader header = page.getHeader();

        short recordLength = (short) (keyBytes.length + data.length);
        short slotNumber = (short) page.getSlots().size();
        long recordOffSet = header.getFreeSpaceEnd() - recordLength;

        Slot slot = new Slot(recordOffSet, recordLength, recordLength, keyBytes.length, false);
        page.addSlot(slot, recordLength);

        this.writeSlotEntry(this.getSlotOffSet(header, slotNumber), slot);
        this.writeRecord(slot.getOffset(), keyBytes, data);
        this.writePageHeader(header);

        return slotNumber;
    }

    private long getSlotOffSet(PageHeader header, short slotNumber) {
        return header.getOffSet() + PBOffSets.HEADER.getValue() + ((long) slotNumber * PBOffSets.SLOT.getValue());
    }

    /*
    Truncates the file back to its header and drops every page, so nothing old
    comes back on the next load. The file gets a new id: any index built for the
    old contents no longer matches it and is rebuilt when next opened.
    */
    private void clearAll() throws Exception {
        this.randomAccessFileService.truncate();
        this.fileId = newFileId();
        this.writeFileHeader();
        this.pageCount = 0;
        this.pageCache.clear();
    }

    private void createNewPage(byte[] data) throws Exception {
        this.clearAll();

        Page page = this.allocatePage();
        this.appendSlot(page, NO_KEY, data);
    }

    /*
    Pages sit back to back after the file header.
    */
    private long getPageOffSet(int pageNumber) {
        return PBOffSets.FILE_HEADER.getValue() + ((long) pageNumber * PBOffSets.PAGE.getValue());
    }

    private int countPages() throws Exception {
        long pagesLength = this.randomAccessFileService.length() - PBOffSets.FILE_HEADER.getValue();
        long pageSize = PBOffSets.PAGE.getValue();
        return (int) Math.max(0, (pagesLength + pageSize - 1) / pageSize);
    }

    /*
    A new (empty) file gets a header with a fresh file id; an existing one has
    its key type and id read. Files written before ids existed get one now.
    */
    private void loadFileHeader() throws Exception {
        long fileLength = this.randomAccessFileService.length();
        if (fileLength == 0) {
            this.storedKeyType = null;
            this.fileId = newFileId();
            this.writeFileHeader();
            return;
        }

        if (fileLength < PBOffSets.FILE_HEADER.getValue()) {
            throw new IllegalStateException("Corrupt or incompatible storage file: missing file header");
        }

        byte[] header = this.randomAccessFileService.read(0, (int) PBOffSets.FILE_HEADER.getValue());
        this.storedKeyType = parseFileHeader(header);
        this.fileId = ByteBuffer.wrap(header).getLong(FILE_ID_OFFSET);

        if (this.fileId == 0) {
            this.fileId = newFileId();
            this.writeFileHeader();
        }
    }

    private static long newFileId() {
        SecureRandom random = new SecureRandom();
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        return id;
    }

    private void writeFileHeader() throws Exception {
        byte[] name = this.storedKeyType == null
            ? new byte[0]
            : this.storedKeyType.getBytes(StandardCharsets.UTF_8);

        if (name.length > MAX_KEY_TYPE_NAME) {
            throw new IllegalArgumentException(
                "Key type name " + this.storedKeyType + " exceeds " + MAX_KEY_TYPE_NAME + " bytes"
            );
        }

        byte[] header = ByteBuffer.allocate(PBOffSets.FILE_HEADER.getValue())
                                  .putShort((short) name.length)
                                  .put(name)
                                  .putLong(FILE_ID_OFFSET, this.fileId)
                                  .array();
        this.randomAccessFileService.write(0, header);
    }

    private static String parseFileHeader(byte[] header) {
        ByteBuffer buffer = ByteBuffer.wrap(header);
        short length = buffer.getShort();
        if (length == 0) {
            return null;
        }
        if (length < 0 || length > MAX_KEY_TYPE_NAME) {
            throw new IllegalStateException("Corrupt or incompatible storage file: bad file header");
        }

        byte[] name = new byte[length];
        buffer.get(name);
        return new String(name, StandardCharsets.UTF_8);
    }

    /*
    Reads one page from disk in a single call and rebuilds its header and slots.
    */
    private Page loadPage(int pageNumber) throws Exception {
        long pageOffSet = this.getPageOffSet(pageNumber);
        int length = (int) Math.min(PBOffSets.PAGE.getValue(), this.randomAccessFileService.length() - pageOffSet);
        ByteBuffer buffer = ByteBuffer.wrap(this.randomAccessFileService.read(pageOffSet, length));

        long offSet = buffer.getLong();
        short slotCount = buffer.getShort();
        long freeSpaceStart = buffer.getLong();
        long freeSpaceEnd = buffer.getLong();
        long freeBytes = buffer.getLong();

        if (offSet != pageOffSet) {
            throw new IllegalStateException(
                "Corrupt or incompatible storage file: page at " + pageOffSet + " has header offset " + offSet
            );
        }

        Page page = new Page(pageNumber);
        page.setPageHeader(new PageHeader(offSet, slotCount, freeSpaceStart, freeSpaceEnd, freeBytes));

        for (int slotNumber = 0; slotNumber < slotCount; slotNumber++) {
            buffer.position(PBOffSets.HEADER.getValue() + slotNumber * PBOffSets.SLOT.getValue());
            long slotOffSet = buffer.getLong();
            short fullLength = buffer.getShort();
            short slotLength = buffer.getShort();
            int keyLength = buffer.getInt();
            boolean deleted = buffer.get() != 0;
            page.restoreSlot(new Slot(slotOffSet, fullLength, slotLength, keyLength, deleted));
        }

        return page;
    }

    private void writePageHeader(PageHeader pageHeader) throws Exception {
        long offSetValue = pageHeader.getOffSet();

        byte[] offSet = this.getLongBytes(offSetValue);
        byte[] slotCount = this.getShortBytes(pageHeader.getSlotCount());
        byte[] freeSpaceStart = this.getLongBytes(pageHeader.getFreeSpaceStart());
        byte[] freeSpaceEnd = this.getLongBytes(pageHeader.getFreeSpaceEnd());
        byte[] freeBytes = this.getLongBytes(pageHeader.getFreeBytes());

        byte[][] headerValues = new byte[][] { offSet, slotCount, freeSpaceStart, freeSpaceEnd, freeBytes };

        this.randomAccessFileService.writeInSequence(offSetValue, headerValues);
    }

    private void writeSlotEntry(long offSet, Slot slot) throws Exception {
        byte[] offSetValue = this.getLongBytes(slot.getOffset());
        byte[] fullLength = this.getShortBytes(slot.getFullLength());
        byte[] length = this.getShortBytes(slot.getLength());
        byte[] keyLength = this.getIntBytes(slot.getKeyLength());
        byte[] deleted = this.getBooleanBytes(slot.isDeleted());

        byte[][] slotValues = new byte[][] { offSetValue, fullLength, length, keyLength, deleted };
        this.randomAccessFileService.writeInSequence(offSet, slotValues);
    }

    /*
    Writes the record [key bytes][data bytes] at offSet. Key and data are
    adjacent on disk, so they are copied into one array and written together.
    */
    private void writeRecord(long offSet, byte[] keyBytes, byte[] data) throws Exception {
        if (keyBytes.length == 0) {
            this.writeData(offSet, data);
            return;
        }

        byte[] record = new byte[keyBytes.length + data.length];
        System.arraycopy(keyBytes, 0, record, 0, keyBytes.length);
        System.arraycopy(data, 0, record, keyBytes.length, data.length);
        this.writeData(offSet, record);
    }

    private void writeData(long offSet, byte[] data) throws Exception {
        if (data.length == 0) {
            return;
        }
        this.randomAccessFileService.write(offSet, data);
    }

    /*
    Move this to Utility!!!!
    */
    private byte[] getLongBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                         .putLong(value)
                         .array();
    }

    private byte[] getIntBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                         .putInt(value)
                         .array();
    }

    private byte[] getShortBytes(short value) {
        return ByteBuffer.allocate(Short.BYTES)
                         .putShort(value)
                         .array();
    }

    private byte[] getBooleanBytes(boolean value) {
        return new byte[] { (byte) (value ? 1 : 0) };
    }
}
