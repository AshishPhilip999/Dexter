package Utility.StorageProviders.PageBasedStorageProvider.Index;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/*
One B+ tree node, stored as one index page.

Page layout (big-endian):
    type(1)            1 = leaf, 2 = internal
    count(2)           number of keys
    next(4)            leaf: page number of the next leaf (-1 if none); internal: unused
    leaf entries:      [keyLength(2)][key][valueLength(2)][value] ...
    internal entries:  [child0(4)] then [keyLength(2)][key][child(4)] ...

Internal node with keys K0..Kn-1 has children C0..Cn: Ci holds keys in
[K(i-1), Ki). A key equal to a separator goes to the right child.
*/
public final class Node {
    static final byte LEAF = 1;
    static final byte INTERNAL = 2;
    static final int HEADER_SIZE = 1 + 2 + 4;

    final int pageNumber;
    final boolean leaf;
    final List<byte[]> keys = new ArrayList<>();

    /*
    Leaf only: value for each key.
    */
    final List<byte[]> values;

    /*
    Internal only: keys.size() + 1 child page numbers.
    */
    final List<Integer> children;

    /*
    Leaf only: page number of the next leaf, -1 for the last leaf.
    */
    int next = -1;

    Node(int pageNumber, boolean leaf) {
        this.pageNumber = pageNumber;
        this.leaf = leaf;
        this.values = leaf ? new ArrayList<>() : null;
        this.children = leaf ? null : new ArrayList<>();
    }

    int size() {
        return this.keys.size();
    }

    /*
    Bytes one entry takes on the page.
    */
    int entrySize(int index) {
        return this.leaf
            ? Short.BYTES + this.keys.get(index).length + Short.BYTES + this.values.get(index).length
            : Short.BYTES + this.keys.get(index).length + Integer.BYTES;
    }

    int serializedSize() {
        int size = HEADER_SIZE + (this.leaf ? 0 : Integer.BYTES);
        for (int i = 0; i < this.keys.size(); i++) {
            size += this.entrySize(i);
        }
        return size;
    }

    byte[] serialize(int pageSize) {
        if (this.serializedSize() > pageSize) {
            throw new IllegalStateException("Node " + this.pageNumber + " does not fit a page");
        }

        ByteBuffer buffer = ByteBuffer.allocate(pageSize);
        buffer.put(this.leaf ? LEAF : INTERNAL);
        buffer.putShort((short) this.keys.size());
        buffer.putInt(this.next);

        if (this.leaf) {
            for (int i = 0; i < this.keys.size(); i++) {
                byte[] key = this.keys.get(i);
                byte[] value = this.values.get(i);
                buffer.putShort((short) key.length).put(key);
                buffer.putShort((short) value.length).put(value);
            }
        } else {
            buffer.putInt(this.children.get(0));
            for (int i = 0; i < this.keys.size(); i++) {
                byte[] key = this.keys.get(i);
                buffer.putShort((short) key.length).put(key);
                buffer.putInt(this.children.get(i + 1));
            }
        }
        return buffer.array();
    }

    static Node parse(int pageNumber, byte[] page) {
        ByteBuffer buffer = ByteBuffer.wrap(page);
        byte type = buffer.get();
        if (type != LEAF && type != INTERNAL) {
            throw new IllegalStateException("Corrupt index page " + pageNumber + ": unknown node type " + type);
        }

        Node node = new Node(pageNumber, type == LEAF);
        int count = buffer.getShort();
        node.next = buffer.getInt();

        if (node.leaf) {
            for (int i = 0; i < count; i++) {
                node.keys.add(readBytes(buffer, buffer.getShort()));
                node.values.add(readBytes(buffer, buffer.getShort()));
            }
        } else {
            node.children.add(buffer.getInt());
            for (int i = 0; i < count; i++) {
                node.keys.add(readBytes(buffer, buffer.getShort()));
                node.children.add(buffer.getInt());
            }
        }
        return node;
    }

    private static byte[] readBytes(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }
}
