package Utility.Encoding;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import Utility.Serialization.Serializer;

/*
Compact key encoding: [tag (1 byte)][value bytes].

Common key types store only their value (an Integer key is 1 + 4 = 5 bytes
instead of 81 with Java serialization). Any other Serializable key falls back
to Java serialization. The tag lets decode() rebuild the right type, and lets
callers detect a file written with a different key type.
*/
public final class KeyCodec {
    private KeyCodec() {
    }

    public static byte[] encode(Serializable key) throws Exception {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        KeyTag tag = KeyTag.forType(key.getClass());
        byte[] value = encodeValue(tag, key);

        return ByteBuffer.allocate(1 + value.length)
                         .put(tag.getValue())
                         .put(value)
                         .array();
    }

    public static Object decode(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Encoded key cannot be empty");
        }

        KeyTag tag = KeyTag.fromValue(bytes[0]);
        ByteBuffer value = ByteBuffer.wrap(bytes, 1, bytes.length - 1).slice();

        return decodeValue(tag, value);
    }

    private static byte[] encodeValue(KeyTag tag, Serializable key) throws Exception {
        switch (tag) {
            case INTEGER:   return ByteBuffer.allocate(Integer.BYTES).putInt((Integer) key).array();
            case LONG:      return ByteBuffer.allocate(Long.BYTES).putLong((Long) key).array();
            case SHORT:     return ByteBuffer.allocate(Short.BYTES).putShort((Short) key).array();
            case BYTE:      return new byte[] { (Byte) key };
            case DOUBLE:    return ByteBuffer.allocate(Double.BYTES).putDouble((Double) key).array();
            case FLOAT:     return ByteBuffer.allocate(Float.BYTES).putFloat((Float) key).array();
            case CHARACTER: return ByteBuffer.allocate(Character.BYTES).putChar((Character) key).array();
            case BOOLEAN:   return new byte[] { (byte) (((Boolean) key) ? 1 : 0) };
            case STRING:    return ((String) key).getBytes(StandardCharsets.UTF_8);
            default:        return Serializer.serialize(key);
        }
    }

    private static Object decodeValue(KeyTag tag, ByteBuffer value) throws Exception {
        switch (tag) {
            case INTEGER:   return expect(value, Integer.BYTES, tag).getInt();
            case LONG:      return expect(value, Long.BYTES, tag).getLong();
            case SHORT:     return expect(value, Short.BYTES, tag).getShort();
            case BYTE:      return expect(value, Byte.BYTES, tag).get();
            case DOUBLE:    return expect(value, Double.BYTES, tag).getDouble();
            case FLOAT:     return expect(value, Float.BYTES, tag).getFloat();
            case CHARACTER: return expect(value, Character.BYTES, tag).getChar();
            case BOOLEAN:   return expect(value, 1, tag).get() != 0;
            case STRING:    return new String(remaining(value), StandardCharsets.UTF_8);
            default:        return Serializer.deserialize(remaining(value));
        }
    }

    private static ByteBuffer expect(ByteBuffer value, int size, KeyTag tag) {
        if (value.remaining() != size) {
            throw new IllegalStateException(
                "Corrupt " + tag + " key: expected " + size + " bytes, found " + value.remaining()
            );
        }
        return value;
    }

    private static byte[] remaining(ByteBuffer value) {
        byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        return bytes;
    }
}
