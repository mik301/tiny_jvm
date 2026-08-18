package jcvm.util;

/** Hex encode/decode helpers. */
public final class Hex {

    private static final char[] D = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    public static String toHex(byte[] a) {
        return a == null ? "" : toHex(a, 0, a.length);
    }

    public static String toHex(byte[] a, int off, int len) {
        if (a == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = a[off + i] & 0xFF;
            sb.append(D[v >> 4]).append(D[v & 0x0F]);
        }
        return sb.toString();
    }

    public static String toSpaced(byte[] a) {
        if (a == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int v = a[i] & 0xFF;
            sb.append(D[v >> 4]).append(D[v & 0x0F]);
        }
        return sb.toString();
    }

    public static byte[] parse(String s) {
        if (s == null) {
            return new byte[0];
        }
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ':' || ch == '-' || ch == ' ' || ch == '_' || ch == '.') {
                continue;
            }
            c.append(ch);
        }
        String t = c.toString();
        if ((t.length() & 1) != 0) {
            throw new IllegalArgumentException("odd number of hex digits: " + s);
        }
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex string: " + s);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
