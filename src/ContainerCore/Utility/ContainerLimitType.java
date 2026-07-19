package ContainerCore.Utility;

public enum ContainerLimitType {
    LENGTH(1),
    BYTES(1),
    KILOBYTES(1024),
    MEGABYTES(1024 * 1024),
    GIGABYTES(1024 * 1024 * 1024),
    NO_LIMIT(1);

    private final int value;

    ContainerLimitType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}