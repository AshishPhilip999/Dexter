package Utility.IO;

public enum FileType {
    BINARY(""),
    DAT(".dat"),
    JSON(".json"),
    ANY("");

    private final String value;

    FileType(String value) {
        this.value = value;
    }

    public String getExtension() {
        return value;
    }
}
