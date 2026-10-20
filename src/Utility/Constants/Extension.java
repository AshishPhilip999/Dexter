package Utility.Constants;

public enum Extension {
    DAT(".dat"),
    JSON(".json"),
    PB(".pb"),
    PBI(".pbi"),
    DEX(".dex"),
    WAL(".wal")
    ;

    private String value;

    Extension(String value) {
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }
}
