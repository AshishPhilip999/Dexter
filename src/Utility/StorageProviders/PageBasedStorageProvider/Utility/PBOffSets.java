package Utility.StorageProviders.PageBasedStorageProvider.Utility;

public enum PBOffSets {
    /*
    Fixed region at the start of the file, before page 0:
    [short keyTypeLength][UTF-8 key type class name], zero-padded. Length 0 = no key type.
    */
    FILE_HEADER((short)256),
    PAGE((short)4090),
    HEADER((short)34),
    SLOT((short)17)
    ;

    private final short value;

    PBOffSets(short value) {
        this.value = value;
    }

    public short getValue() {
        return this.value;
    }
}
