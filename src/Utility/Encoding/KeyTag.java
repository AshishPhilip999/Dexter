package Utility.Encoding;

/*
One-byte tag written in front of every encoded key, so the stored bytes say
which type they hold. SERIALIZED is the fallback for any other key class.
*/
public enum KeyTag {
    INTEGER((byte) 1, Integer.class),
    LONG((byte) 2, Long.class),
    SHORT((byte) 3, Short.class),
    BYTE((byte) 4, Byte.class),
    DOUBLE((byte) 5, Double.class),
    FLOAT((byte) 6, Float.class),
    CHARACTER((byte) 7, Character.class),
    BOOLEAN((byte) 8, Boolean.class),
    STRING((byte) 9, String.class),
    SERIALIZED((byte) 127, null)
    ;

    private final byte value;
    private final Class<?> type;

    KeyTag(byte value, Class<?> type) {
        this.value = value;
        this.type = type;
    }

    public byte getValue() {
        return this.value;
    }

    public Class<?> getType() {
        return this.type;
    }

    /*
    Tag for a key's class; SERIALIZED when it has no compact encoding.
    */
    public static KeyTag forType(Class<?> type) {
        for (KeyTag tag : values()) {
            if (tag.type == type) {
                return tag;
            }
        }
        return SERIALIZED;
    }

    public static KeyTag fromValue(byte value) {
        for (KeyTag tag : values()) {
            if (tag.value == value) {
                return tag;
            }
        }
        throw new IllegalStateException("Unknown key tag: " + value);
    }
}
