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

    /**
     * Parses hex that may use the {@code #( ... )} notation of GlobalPlatform
     * scripting tools, where a group is replaced by its length followed by its
     * contents. Groups nest, so
     *
     * <pre>80E60C00#(#(A0000001)#(00))</pre>
     *
     * becomes {@code 80 E6 0C 00 07 04 A0 00 00 01 01 00}.
     */
    public static byte[] parseScript(String s) {
        if (s == null) {
            return new byte[0];
        }
        if (s.indexOf('#') < 0) {
            return parse(s);
        }
        int[] cursor = new int[]{0};
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        parseGroup(s, cursor, out, false);
        return out.toByteArray();
    }

    private static void parseGroup(String s, int[] at,
            java.io.ByteArrayOutputStream out, boolean nested) {
        int hi = -1;
        while (at[0] < s.length()) {
            char c = s.charAt(at[0]);
            if (c == ')') {
                at[0]++;
                if (!nested) {
                    throw new IllegalArgumentException("unbalanced ')' in " + s);
                }
                if (hi >= 0) {
                    throw new IllegalArgumentException("odd hex digit before ')'");
                }
                return;
            }
            if (c == '#') {
                if (hi >= 0) {
                    throw new IllegalArgumentException("odd hex digit before '#('");
                }
                at[0]++;
                if (at[0] >= s.length() || s.charAt(at[0]) != '(') {
                    throw new IllegalArgumentException("'#' must be followed by '('");
                }
                at[0]++;
                java.io.ByteArrayOutputStream inner =
                        new java.io.ByteArrayOutputStream();
                parseGroup(s, at, inner, true);
                byte[] body = inner.toByteArray();
                if (body.length > 0xFF) {
                    throw new IllegalArgumentException(
                            "a #( ) group of " + body.length
                            + " bytes does not fit a single length byte");
                }
                out.write(body.length);
                out.write(body, 0, body.length);
                continue;
            }
            at[0]++;
            if (c == ':' || c == '-' || c == ' ' || c == '_' || c == '.'
                    || c == '\t') {
                continue;
            }
            int d = Character.digit(c, 16);
            if (d < 0) {
                throw new IllegalArgumentException("bad hex character '" + c + "'");
            }
            if (hi < 0) {
                hi = d;
            } else {
                out.write((hi << 4) | d);
                hi = -1;
            }
        }
        if (nested) {
            throw new IllegalArgumentException("unterminated '#(' in " + s);
        }
        if (hi >= 0) {
            throw new IllegalArgumentException("odd number of hex digits: " + s);
        }
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
