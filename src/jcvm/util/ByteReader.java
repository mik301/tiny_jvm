package jcvm.util;

/** Big-endian reader over a byte[]; all CAP structures are big-endian. */
public final class ByteReader {

    private final byte[] d;
    private int p;

    public ByteReader(byte[] data) {
        this(data, 0);
    }

    public ByteReader(byte[] data, int offset) {
        this.d = data;
        this.p = offset;
    }

    public byte[] data() {
        return d;
    }

    public int pos() {
        return p;
    }

    public void seek(int newPos) {
        this.p = newPos;
    }

    public void skip(int n) {
        this.p += n;
    }

    public int remaining() {
        return d.length - p;
    }

    public boolean hasMore() {
        return p < d.length;
    }

    public int u1() {
        return d[p++] & 0xFF;
    }

    public int s1() {
        return d[p++];
    }

    public int u2() {
        int hi = d[p++] & 0xFF;
        int lo = d[p++] & 0xFF;
        return (hi << 8) | lo;
    }

    public int s2() {
        return (short) u2();
    }

    public int s4() {
        int a = d[p++] & 0xFF;
        int b = d[p++] & 0xFF;
        int c = d[p++] & 0xFF;
        int e = d[p++] & 0xFF;
        return (a << 24) | (b << 16) | (c << 8) | e;
    }

    public long u4() {
        return s4() & 0xFFFFFFFFL;
    }

    public byte[] bytes(int n) {
        byte[] out = new byte[n];
        System.arraycopy(d, p, out, 0, n);
        p += n;
        return out;
    }

    /* ---- static peek helpers, used by the interpreter over the method component ---- */

    public static int u1(byte[] a, int off) {
        return a[off] & 0xFF;
    }

    public static int s1(byte[] a, int off) {
        return a[off];
    }

    public static int u2(byte[] a, int off) {
        return ((a[off] & 0xFF) << 8) | (a[off + 1] & 0xFF);
    }

    public static int s2(byte[] a, int off) {
        return (short) u2(a, off);
    }

    public static int s4(byte[] a, int off) {
        return ((a[off] & 0xFF) << 24) | ((a[off + 1] & 0xFF) << 16)
                | ((a[off + 2] & 0xFF) << 8) | (a[off + 3] & 0xFF);
    }
}
