package Utility.IO.Std;

import java.util.ArrayList;
import java.util.List;

public class Page {

    private final int pageNumber;
    private final PageHeader header;
    private final List<Slot> slots;

    public Page(int pageNumber, int pageSize) {
        this.pageNumber = pageNumber;
        this.header = new PageHeader(pageSize);
        this.slots = new ArrayList<>();
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public PageHeader getHeader() {
        return header;
    }

    public List<Slot> getSlots() {
        return slots;
    }

    public void addSlot(Slot slot) {
        slots.add(slot);
        header.incrementSlotCount();
    }
}
