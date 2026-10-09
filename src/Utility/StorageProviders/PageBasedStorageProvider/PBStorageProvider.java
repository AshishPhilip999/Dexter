package Utility.StorageProviders.PageBasedStorageProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.Constants.Extension;
import Utility.FileServices.RandomAccessFileService;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PBOffSets;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Page;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.PageHeader;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.RecordID;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.Slot;

public class PBStorageProvider {
    private static final byte[] NO_KEY = new byte[0];

    private List<Page> pages;

    /*
    Deleted slots available for reuse, ordered by fullLength (a red-black tree),
    so the smallest slot that fits a record is found in O(log n).
    fullLength -> deleted slots of that size.
    */
    private TreeMap<Short, Deque<RecordID>> freeSlots;

    /*
    Class name of the key type stored in the file header, or null if none.
    */
    private String storedKeyType;

    private RandomAccessFileService randomAccessFileService;

    /*
    Opens (or creates) the storage file. Pages and slots already in the file are
    loaded back, so the storage survives a program restart.
    */
    public PBStorageProvider(String path) throws Exception {
        this.randomAccessFileService = new RandomAccessFileService(path, Extension.PB);
        this.pages = new ArrayList<>();
        this.freeSlots = new TreeMap<>();
        this.loadFileHeader();
        this.loadPages();
    }

    /*
    Drops all existing pages (in memory and on disk) and writes a single new page
    with this data.
    SUCCESS: PBSuccess.WRITE, getData() holds the written data
    FAIL:    NULL_WRITE, DATA_TOO_LARGE, WRITE_ERROR
    */
    public PBStatus write(byte[] data) {
        PBFail invalid = this.validateRecord(0, data);
        if (invalid != null) {
            return invalid;
        }

        try {
            this.createNewPage(data);
        } catch (Exception e) {
            return PBFail.WRITE_ERROR;
        }

        return PBSuccess.write(data);
    }

    /*
    Erases everything: every page and record is removed from memory and the
    file is truncated back to just its header (the stored key type is kept).
    SUCCESS: PBSuccess.DELETE
    FAIL:    DELETE_ERROR
    */
    public PBStatus delete() {
        try {
            this.clearAll();
        } catch (Exception e) {
            return PBFail.DELETE_ERROR;
        }

        return PBSuccess.DELETE;
    }

    /*
    The data written by write(data): the first live record in the file.
    SUCCESS: PBSuccess.READ, getData() holds the record
    FAIL:    NULL_READ (nothing stored), READ_ERROR
    */
    public PBStatus read() {
        try {
            for (Page page : this.pages) {
                for (Slot slot : page.getSlots()) {
                    if (!slot.isDeleted()) {
                        return PBSuccess.read(this.readSlot(slot));
                    }
                }
            }
        } catch (Exception e) {
            return PBFail.READ_ERROR;
        }

        return PBFail.NULL_READ;
    }

    /*
    Data of every live slot, in page and slot order, one entry per record, so
    each entry is exactly the byte[] that was written and can be deserialized
    back into its own object. Key bytes of keyed records are not included;
    deleted slots (old versions of overwritten keys) are skipped.
    */
    protected byte[][] readAllRecords() throws Exception {
        List<byte[]> records = new ArrayList<>();
        for(Page page : this.pages) {
            this.readPage(page, records);
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
    Read-only view of the pages, for subclasses walking every slot.
    */
    protected List<Page> getPages() {
        return Collections.unmodifiableList(this.pages);
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
    1. Best fit: the smallest deleted slot whose fullLength fits the record is
       reused in place (same offset, new length / keyLength).
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
    Removes and returns the best-fit deleted slot for this record length,
    or null if no deleted slot is big enough.
    */
    private RecordID takeFreeSlot(short recordLength) {
        Map.Entry<Short, Deque<RecordID>> entry = this.freeSlots.ceilingEntry(recordLength);
        if (entry == null) {
            return null;
        }

        Deque<RecordID> sameSize = entry.getValue();
        RecordID recordID = sameSize.pollFirst();
        if (sameSize.isEmpty()) {
            this.freeSlots.remove(entry.getKey());
        }
        return recordID;
    }

    private void addFreeSlot(RecordID recordID, Slot slot) {
        this.freeSlots
            .computeIfAbsent(slot.getFullLength(), size -> new ArrayDeque<>())
            .addLast(recordID);
    }

    private Page getPageWithSpace(short recordLength) throws Exception {
        if (!this.pages.isEmpty()) {
            Page lastPage = this.pages.get(this.pages.size() - 1);
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
        int pageNumber = this.pages.size();
        long pageOffSet = this.getPageOffSet(pageNumber);

        PageHeader pageHeader = new PageHeader(pageOffSet);
        Page page = new Page(pageNumber);
        page.setPageHeader(pageHeader);

        this.pages.add(page);
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
        this.addFreeSlot(recordID, slot);
    }

    private long getSlotOffSet(PageHeader header, short slotNumber) {
        return header.getOffSet() + PBOffSets.HEADER.getValue() + ((long) slotNumber * PBOffSets.SLOT.getValue());
    }

    private Page getPage(int pageNumber) {
        if (pageNumber < 0 || pageNumber >= this.pages.size()) {
            return null;
        }
        return this.pages.get(pageNumber);
    }

    protected Slot getSlot(RecordID recordID) {
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
    Truncates the file back to its header and drops every page and free slot,
    so nothing old comes back on the next load.
    */
    private void clearAll() throws Exception {
        this.randomAccessFileService.truncate();
        this.writeFileHeader();
        this.pages = new ArrayList<>();
        this.freeSlots.clear();
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

    /*
    A new (empty) file gets an empty header; an existing one has its key type read.
    */
    private void loadFileHeader() throws Exception {
        long fileLength = this.randomAccessFileService.length();
        if (fileLength == 0) {
            this.storedKeyType = null;
            this.writeFileHeader();
            return;
        }

        if (fileLength < PBOffSets.FILE_HEADER.getValue()) {
            throw new IllegalStateException("Corrupt or incompatible storage file: missing file header");
        }

        byte[] header = this.randomAccessFileService.read(0, PBOffSets.FILE_HEADER.getValue());
        this.storedKeyType = parseFileHeader(header);
    }

    private void writeFileHeader() throws Exception {
        byte[] name = this.storedKeyType == null
            ? new byte[0]
            : this.storedKeyType.getBytes(StandardCharsets.UTF_8);

        int maxLength = PBOffSets.FILE_HEADER.getValue() - Short.BYTES;
        if (name.length > maxLength) {
            throw new IllegalArgumentException(
                "Key type name " + this.storedKeyType + " exceeds " + maxLength + " bytes"
            );
        }

        byte[] header = ByteBuffer.allocate(PBOffSets.FILE_HEADER.getValue())
                                  .putShort((short) name.length)
                                  .put(name)
                                  .array();
        this.randomAccessFileService.write(0, header);
    }

    private static String parseFileHeader(byte[] header) {
        ByteBuffer buffer = ByteBuffer.wrap(header);
        short length = buffer.getShort();
        if (length == 0) {
            return null;
        }
        if (length < 0 || length > header.length - Short.BYTES) {
            throw new IllegalStateException("Corrupt or incompatible storage file: bad file header");
        }

        byte[] name = new byte[length];
        buffer.get(name);
        return new String(name, StandardCharsets.UTF_8);
    }

    /*
    Rebuilds the in-memory pages from the file: every page header, then every
    slot entry of that page.
    */
    private void loadPages() throws Exception {
        long pagesLength = this.randomAccessFileService.length() - PBOffSets.FILE_HEADER.getValue();
        long pageSize = PBOffSets.PAGE.getValue();
        int pageCount = (int) ((pagesLength + pageSize - 1) / pageSize);

        for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
            long pageOffSet = this.getPageOffSet(pageNumber);
            PageHeader header = this.readPageHeader(pageOffSet);

            Page page = new Page(pageNumber);
            page.setPageHeader(header);
            
            for (short slotNumber = 0; slotNumber < header.getSlotCount(); slotNumber++) {
                Slot slot = this.readSlotEntry(this.getSlotOffSet(header, slotNumber));
                page.restoreSlot(slot);

                if (slot.isDeleted()) {
                    this.addFreeSlot(new RecordID(pageNumber, slotNumber), slot);
                }
            }

            this.pages.add(page);
        }
    }

    private PageHeader readPageHeader(long pageOffSet) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(
            this.randomAccessFileService.read(pageOffSet, PBOffSets.HEADER.getValue())
        );

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

        return new PageHeader(offSet, slotCount, freeSpaceStart, freeSpaceEnd, freeBytes);
    }

    private Slot readSlotEntry(long entryOffSet) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(
            this.randomAccessFileService.read(entryOffSet, PBOffSets.SLOT.getValue())
        );

        long offSet = buffer.getLong();
        short fullLength = buffer.getShort();
        short length = buffer.getShort();
        int keyLength = buffer.getInt();
        boolean deleted = buffer.get() != 0;

        return new Slot(offSet, fullLength, length, keyLength, deleted);
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

    private void readPage(Page page, List<byte[]> records) throws Exception {
        for(Slot slot : page.getSlots()) {
            if (slot.isDeleted()) {
                continue;
            }

            records.add(this.readSlot(slot));
        }
    }

    /*
    Data bytes of a slot (the bytes after the key).
    */
    protected byte[] readSlot(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getDataOffset(), (short) slot.getDataLength());
    }

    /*
    Key bytes of a slot (the first keyLength bytes at its offset).
    */
    protected byte[] readSlotKey(Slot slot) throws Exception {
        return this.randomAccessFileService.read(slot.getOffset(), (short) slot.getKeyLength());
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
