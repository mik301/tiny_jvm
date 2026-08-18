package jcvm.rt;

/** A Java Card array instance. */
public final class JCArray {

    /* atype codes as used by the newarray bytecode */
    public static final int T_BOOLEAN = 10;
    public static final int T_BYTE = 11;
    public static final int T_SHORT = 12;
    public static final int T_INT = 13;
    public static final int T_REFERENCE = 14;

    /* transient kinds, mirroring JCSystem */
    public static final byte NOT_A_TRANSIENT_OBJECT = 0;
    public static final byte CLEAR_ON_RESET = 1;
    public static final byte CLEAR_ON_DESELECT = 2;

    public final int type;
    public final int length;
    public byte transientKind = NOT_A_TRANSIENT_OBJECT;

    public byte[] bytes;
    public short[] shorts;
    public int[] ints;
    public Object[] refs;

    /** Element class of a reference array; may be null. */
    public Object elementClass;

    public JCArray(int type, int length) {
        this.type = type;
        this.length = length;
        switch (type) {
            case T_BOOLEAN:
            case T_BYTE:
                bytes = new byte[length];
                break;
            case T_SHORT:
                shorts = new short[length];
                break;
            case T_INT:
                ints = new int[length];
                break;
            case T_REFERENCE:
                refs = new Object[length];
                break;
            default:
                throw new IllegalArgumentException("bad array type " + type);
        }
    }

    /** Wraps an existing byte[] so natives (APDU buffer) can share storage. */
    public static JCArray wrap(byte[] storage) {
        JCArray a = new JCArray(T_BYTE, storage.length, storage);
        return a;
    }

    private JCArray(int type, int length, byte[] storage) {
        this.type = type;
        this.length = length;
        this.bytes = storage;
    }

    public boolean isPrimitive() {
        return type != T_REFERENCE;
    }

    public int getPrim(int index) {
        check(index);
        switch (type) {
            case T_BOOLEAN:
            case T_BYTE:
                return bytes[index];
            case T_SHORT:
                return shorts[index];
            case T_INT:
                return ints[index];
            default:
                throw new IllegalStateException("not a primitive array");
        }
    }

    public void setPrim(int index, int value) {
        check(index);
        switch (type) {
            case T_BOOLEAN:
                bytes[index] = (byte) (value != 0 ? 1 : 0);
                break;
            case T_BYTE:
                bytes[index] = (byte) value;
                break;
            case T_SHORT:
                shorts[index] = (short) value;
                break;
            case T_INT:
                ints[index] = value;
                break;
            default:
                throw new IllegalStateException("not a primitive array");
        }
    }

    public Object getRef(int index) {
        check(index);
        return refs[index];
    }

    public void setRef(int index, Object v) {
        check(index);
        refs[index] = v;
    }

    private void check(int index) {
        if (index < 0 || index >= length) {
            throw new JCThrow(JCThrow.ARRAY_INDEX_OUT_OF_BOUNDS,
                    "array index " + index + " out of range 0.." + (length - 1));
        }
    }

    /** Zeroes the contents; used when clearing transient memory. */
    public void clear() {
        if (bytes != null) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        if (shorts != null) {
            java.util.Arrays.fill(shorts, (short) 0);
        }
        if (ints != null) {
            java.util.Arrays.fill(ints, 0);
        }
        if (refs != null) {
            java.util.Arrays.fill(refs, null);
        }
    }

    public String typeName() {
        switch (type) {
            case T_BOOLEAN: return "boolean[]";
            case T_BYTE: return "byte[]";
            case T_SHORT: return "short[]";
            case T_INT: return "int[]";
            default: return "Object[]";
        }
    }

    public String toString() {
        return typeName() + "(" + length + ")";
    }
}
