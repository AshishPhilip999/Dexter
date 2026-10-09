package Utility.StorageProviders.PageBasedStorageProvider.Utility;

public class RecordID {

    public final int pageNumber;
    public final short slotNumber;

    public RecordID(int pageNumber, short slotNumber) {
        this.pageNumber = pageNumber;
        this.slotNumber = slotNumber;
    }
}