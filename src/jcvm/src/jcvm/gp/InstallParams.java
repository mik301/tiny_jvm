package jcvm.gp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import jcvm.util.Hex;

/**
 * The install parameters field of INSTALL [for install].
 *
 * GlobalPlatform gives it this shape:
 *
 * <pre>
 *   C9 len  application specific parameters   -> handed to the applet's install()
 *   EF len  system specific parameters
 *             C7 len  volatile memory quota
 *             C8 len  non-volatile memory quota
 *             D7 len  volatile reserved memory
 *             D8 len  non-volatile reserved memory
 *             CA len  UICC toolkit application parameters   (ETSI TS 102 226)
 *             CB len  UICC access application parameters
 *             CC len  UICC administrative access parameters
 * </pre>
 *
 * Some tools send a bare parameter blob with no TLV wrapper at all, so if the
 * field does not start with C9 or EF the whole thing is treated as the
 * application specific parameters.
 */
public final class InstallParams {

    public static final int TAG_APP_PARAMS = 0xC9;
    public static final int TAG_SYSTEM_PARAMS = 0xEF;
    public static final int TAG_VOLATILE_QUOTA = 0xC7;
    public static final int TAG_NONVOLATILE_QUOTA = 0xC8;
    public static final int TAG_VOLATILE_RESERVED = 0xD7;
    public static final int TAG_NONVOLATILE_RESERVED = 0xD8;
    public static final int TAG_UICC_TOOLKIT = 0xCA;
    public static final int TAG_UICC_ACCESS = 0xCB;
    public static final int TAG_UICC_ADMIN = 0xCC;

    /** Value of C9: this is what reaches Applet.install(). */
    public byte[] appParams = new byte[0];
    /** Raw value of EF, if present. */
    public byte[] systemParams = new byte[0];

    public int volatileQuota = -1;
    public int nonVolatileQuota = -1;
    public int volatileReserved = -1;
    public int nonVolatileReserved = -1;

    /** Parsed CA, or null when the applet is not a toolkit application. */
    public ToolkitParams toolkit;
    public byte[] uiccAccessParams;
    public byte[] uiccAdminParams;

    /** True when the field was a bare blob rather than TLV. */
    public boolean untagged;

    /**
     * UICC toolkit application parameters, tag CA of ETSI TS 102 226.
     *
     * <pre>
     *   1  priority level
     *   1  maximum number of timers
     *   1  maximum text length of a menu entry
     *   1  maximum number of menu entries
     *   2n position and identifier of each menu entry
     *   1  maximum number of soft keys          (optional)
     *   ...further optional fields (MSL, TAR) are kept as raw bytes
     * </pre>
     */
    public static final class ToolkitParams {

        public int priority;
        public int maxTimers;
        public int maxMenuTextLength;
        public int maxMenuEntries;
        /** One {position, identifier} pair per reserved menu entry. */
        public final List<int[]> menuEntries = new ArrayList<int[]>();
        public int maxSoftKeys = -1;
        public byte[] trailing = new byte[0];

        public int identifierFor(int index) {
            if (index >= 0 && index < menuEntries.size()) {
                return menuEntries.get(index)[1];
            }
            return -1;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("priority=").append(priority)
                    .append(" timers=").append(maxTimers)
                    .append(" menuEntries=").append(maxMenuEntries)
                    .append(" menuTextLen=").append(maxMenuTextLength);
            if (maxSoftKeys >= 0) {
                sb.append(" softKeys=").append(maxSoftKeys);
            }
            for (int i = 0; i < menuEntries.size(); i++) {
                int[] m = menuEntries.get(i);
                sb.append("\n      menu entry position ").append(m[0])
                        .append(" identifier ").append(m[1]);
            }
            if (trailing.length > 0) {
                sb.append("\n      trailing ").append(Hex.toHex(trailing));
            }
            return sb.toString();
        }
    }

    /* ================================================================== */

    public static InstallParams parse(byte[] field) {
        InstallParams p = new InstallParams();
        if (field == null || field.length == 0) {
            return p;
        }
        int first = field[0] & 0xFF;
        if (first != TAG_APP_PARAMS && first != TAG_SYSTEM_PARAMS) {
            p.untagged = true;
            p.appParams = field;
            return p;
        }
        List<int[]> tlvs = walk(field);
        for (int i = 0; i < tlvs.size(); i++) {
            int[] t = tlvs.get(i);
            byte[] value = slice(field, t[1], t[2]);
            if (t[0] == TAG_APP_PARAMS) {
                p.appParams = value;
            } else if (t[0] == TAG_SYSTEM_PARAMS) {
                p.systemParams = value;
                p.parseSystem(value);
            }
        }
        return p;
    }

    private void parseSystem(byte[] sys) {
        List<int[]> tlvs = walk(sys);
        for (int i = 0; i < tlvs.size(); i++) {
            int[] t = tlvs.get(i);
            byte[] value = slice(sys, t[1], t[2]);
            switch (t[0]) {
                case TAG_VOLATILE_QUOTA:
                    volatileQuota = number(value);
                    break;
                case TAG_NONVOLATILE_QUOTA:
                    nonVolatileQuota = number(value);
                    break;
                case TAG_VOLATILE_RESERVED:
                    volatileReserved = number(value);
                    break;
                case TAG_NONVOLATILE_RESERVED:
                    nonVolatileReserved = number(value);
                    break;
                case TAG_UICC_TOOLKIT:
                    toolkit = parseToolkit(value);
                    break;
                case TAG_UICC_ACCESS:
                    uiccAccessParams = value;
                    break;
                case TAG_UICC_ADMIN:
                    uiccAdminParams = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static ToolkitParams parseToolkit(byte[] v) {
        ToolkitParams t = new ToolkitParams();
        if (v.length < 4) {
            return t;
        }
        t.priority = v[0] & 0xFF;
        t.maxTimers = v[1] & 0xFF;
        t.maxMenuTextLength = v[2] & 0xFF;
        t.maxMenuEntries = v[3] & 0xFF;
        int p = 4;
        for (int i = 0; i < t.maxMenuEntries && p + 1 < v.length; i++) {
            t.menuEntries.add(new int[]{v[p] & 0xFF, v[p + 1] & 0xFF});
            p += 2;
        }
        if (p < v.length) {
            t.maxSoftKeys = v[p] & 0xFF;
            p++;
        }
        if (p < v.length) {
            t.trailing = slice(v, p, v.length - p);
        }
        return t;
    }

    /** Returns {tag, valueOffset, valueLength} for each TLV in the buffer. */
    private static List<int[]> walk(byte[] d) {
        List<int[]> out = new ArrayList<int[]>();
        int p = 0;
        while (p + 2 <= d.length) {
            int tag = d[p] & 0xFF;
            int len = d[p + 1] & 0xFF;
            int vOff = p + 2;
            if (len == 0x81) {
                if (p + 3 > d.length) {
                    break;
                }
                len = d[p + 2] & 0xFF;
                vOff = p + 3;
            } else if (len == 0x82) {
                if (p + 4 > d.length) {
                    break;
                }
                len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
                vOff = p + 4;
            } else if (len > 0x82) {
                break;
            }
            if (vOff + len > d.length) {
                break;
            }
            out.add(new int[]{tag, vOff, len});
            p = vOff + len;
        }
        return out;
    }

    private static byte[] slice(byte[] d, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(d, off, out, 0, len);
        return out;
    }

    private static int number(byte[] v) {
        int n = 0;
        for (int i = 0; i < v.length; i++) {
            n = (n << 8) | (v[i] & 0xFF);
        }
        return n;
    }

    /* ---------------- building ---------------- */

    /** Builds an install parameters field from its parts. */
    public static byte[] build(byte[] appParams, byte[] systemParams) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] a = appParams == null ? new byte[0] : appParams;
        out.write(TAG_APP_PARAMS);
        out.write(a.length);
        out.write(a, 0, a.length);
        if (systemParams != null && systemParams.length > 0) {
            out.write(TAG_SYSTEM_PARAMS);
            out.write(systemParams.length);
            out.write(systemParams, 0, systemParams.length);
        }
        return out.toByteArray();
    }

    /** Builds a CA toolkit parameters TLV for the system parameters block. */
    public static byte[] buildToolkitSystemParams(int priority, int maxTimers,
            int maxMenuTextLength, int[][] menuEntries, int maxSoftKeys) {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.write(priority);
        v.write(maxTimers);
        v.write(maxMenuTextLength);
        v.write(menuEntries == null ? 0 : menuEntries.length);
        if (menuEntries != null) {
            for (int i = 0; i < menuEntries.length; i++) {
                v.write(menuEntries[i][0]);
                v.write(menuEntries[i][1]);
            }
        }
        if (maxSoftKeys >= 0) {
            v.write(maxSoftKeys);
        }
        byte[] value = v.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TAG_UICC_TOOLKIT);
        out.write(value.length);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (untagged) {
            sb.append("    untagged parameter blob: ")
                    .append(Hex.toHex(appParams)).append('\n');
            return sb.toString();
        }
        sb.append("    C9 application parameters: ")
                .append(appParams.length == 0 ? "(none)" : Hex.toHex(appParams))
                .append('\n');
        if (systemParams.length > 0) {
            sb.append("    EF system parameters:\n");
            if (volatileQuota >= 0) {
                sb.append("      C7 volatile quota ").append(volatileQuota).append('\n');
            }
            if (nonVolatileQuota >= 0) {
                sb.append("      C8 non-volatile quota ")
                        .append(nonVolatileQuota).append('\n');
            }
            if (volatileReserved >= 0) {
                sb.append("      D7 volatile reserved ")
                        .append(volatileReserved).append('\n');
            }
            if (nonVolatileReserved >= 0) {
                sb.append("      D8 non-volatile reserved ")
                        .append(nonVolatileReserved).append('\n');
            }
            if (toolkit != null) {
                sb.append("      CA toolkit: ").append(toolkit).append('\n');
            }
            if (uiccAccessParams != null) {
                sb.append("      CB access: ")
                        .append(Hex.toHex(uiccAccessParams)).append('\n');
            }
            if (uiccAdminParams != null) {
                sb.append("      CC admin: ")
                        .append(Hex.toHex(uiccAdminParams)).append('\n');
            }
        }
        return sb.toString();
    }
}
