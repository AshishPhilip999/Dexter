package Utility.StorageProviders.PageBasedStorageProvider.Utility;

import java.util.ArrayList;
import java.util.List;

public class Page {
    private final int pageNumber;

    private final List<Slot> slots;

    private PageHeader header;

    public Page(int pageNumber) {
        this.pageNumber = pageNumber;
        this.slots = new ArrayList<>();
    }

    public int getPageNumber() {
        return this.pageNumber;
    }

    public PageHeader getHeader() {
        return header;
    }

    public List<Slot> getSlots() {
        return slots;
    }

    public void setPageHeader(PageHeader pageHeader) {
        this.header = pageHeader;
    }

    /*
    Adds a slot read back from disk. The header was restored with its counts
    already, so it is not updated here.
    */
    public void restoreSlot(Slot slot) {
        slots.add(slot);
    }

    public void addSlot(Slot slot, short dataLength) {
        slots.add(slot);
        this.header.updateFreeSace(dataLength);
        header.incrementSlotCount();
    }
}
