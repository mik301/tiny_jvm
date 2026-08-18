package jcvm.uicc;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jcvm.rt.JCThrow;
import jcvm.util.Hex;

/**
 * A small CAT (Card Application Toolkit) runtime: the part of a UICC that
 * registers toolkit applets, triggers them on events, and collects the
 * proactive commands they issue.
 *
 * This models the behaviour an applet can observe, not a conformant TS 102 223
 * terminal. Proactive commands are recorded and printed rather than executed,
 * and the response to a fetch is whatever the shell supplies.
 */
public final class CatRuntime {

    /* a few event codes from TS 102 241; applets pass these to setEvent */
    public static final byte EVENT_FORMATTED_SMS_PP_ENV = 1;
    public static final byte EVENT_UNFORMATTED_SMS_PP_ENV = 2;
    public static final byte EVENT_FORMATTED_SMS_PP_UPD = 3;
    public static final byte EVENT_UNFORMATTED_SMS_PP_UPD = 4;
    public static final byte EVENT_MENU_SELECTION = 5;
    public static final byte EVENT_MENU_SELECTION_HELP_REQUEST = 6;
    public static final byte EVENT_TIMER_EXPIRATION = 8;
    public static final byte EVENT_EVENT_DOWNLOAD_LOCATION_STATUS = 12;
    public static final byte EVENT_STATUS_COMMAND = 19;
    public static final byte EVENT_PROFILE_DOWNLOAD = 20;
    public static final byte EVENT_FIRST_COMMAND_AFTER_ATR = 21;
    public static final byte EVENT_APPLICATION_DESELECT = 22;
    public static final byte EVENT_EXTERNAL_FILE_UPDATE = 23;

    /** ToolkitException reason codes. */
    public static final short HANDLER_NOT_AVAILABLE = 2;
    public static final short MENU_ENTRY_NOT_FOUND = 3;
    public static final short REGISTRY_ERROR = 5;
    public static final short OUT_OF_TLV_BOUNDARIES = 6;
    public static final short UNAVAILABLE_ELEMENT = 7;
    public static final short BAD_INPUT_PARAMETER = 1;
    public static final short NO_TIMER_AVAILABLE = 4;

    /** One applet's toolkit registration. */
    public static final class Registry {

        public final Object applet;
        public final boolean[] events = new boolean[128];
        public final Map<Byte, String> menuEntries = new LinkedHashMap<Byte, String>();

        /** Configuration from the CA tag of the install parameters, if any. */
        public jcvm.gp.InstallParams.ToolkitParams config;
        public int priority = 1;
        public int maxTimers;
        public int maxMenuEntries = 8;
        public int maxMenuTextLength = 32;

        public Registry(Object applet) {
            this.applet = applet;
        }

        public void configure(jcvm.gp.InstallParams.ToolkitParams t) {
            if (t == null) {
                return;
            }
            this.config = t;
            this.priority = t.priority;
            this.maxTimers = t.maxTimers;
            this.maxMenuEntries = t.maxMenuEntries;
            this.maxMenuTextLength = t.maxMenuTextLength;
        }

        /**
         * The menu identifier the install parameters reserved for the next
         * entry, or 0 when none was given and the VM should pick one.
         */
        public int reservedIdentifier(int index) {
            return config == null ? 0 : Math.max(0, config.identifierFor(index));
        }

        private final java.util.Set<Integer> timers = new java.util.TreeSet<Integer>();

        /** Allocates a timer identifier, 1..maxTimers. */
        public int allocateTimer() {
            for (int i = 1; i <= Math.max(maxTimers, 8); i++) {
                if (timers.add(Integer.valueOf(i))) {
                    return i;
                }
            }
            throw toolkit(NO_TIMER_AVAILABLE);
        }

        public void releaseTimer(int id) {
            if (!timers.remove(Integer.valueOf(id))) {
                throw toolkit(BAD_INPUT_PARAMETER);
            }
        }

        public boolean isEventSet(int event) {
            return event >= 0 && event < events.length && events[event];
        }
    }

    private final Map<Object, Registry> registries = new LinkedHashMap<Object, Registry>();

    /* ---- current triggering context ---- */
    public byte currentEvent;
    public byte[] envelope = new byte[0];
    public int envelopeTag;
    private int tlvCursor = -1;      // offset of the current TLV inside envelope value
    private int tlvValueOffset;
    private int tlvValueLength;

    /* ---- proactive command being built ---- */
    private ByteArrayOutputStream proactive;
    private int proactiveTag;
    private final List<byte[]> issued = new ArrayList<byte[]>();
    /** General result the "terminal" reports for a proactive command. */
    public byte generalResult = 0x00;   // command performed successfully

    /* ------------------------------------------------------------------ */
    /* registry                                                            */
    /* ------------------------------------------------------------------ */

    public Registry registryFor(Object applet) {
        Registry r = registries.get(applet);
        if (r == null) {
            r = new Registry(applet);
            registries.put(applet, r);
        }
        return r;
    }

    public List<Registry> registries() {
        return new ArrayList<Registry>(registries.values());
    }

    public void clear() {
        registries.clear();
        issued.clear();
        proactive = null;
        envelope = new byte[0];
        tlvCursor = -1;
    }

    /* ------------------------------------------------------------------ */
    /* envelope handling                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Installs an envelope for triggering. {@code data} is the full BER-TLV
     * envelope, e.g. D1 xx ... for an SMS-PP download.
     */
    public void setEnvelope(byte[] data) {
        this.envelope = data;
        this.envelopeTag = data.length > 0 ? (data[0] & 0xFF) : 0;
        this.tlvCursor = -1;
    }

    /** Offset of the first inner TLV, i.e. just past the envelope tag/length. */
    private int valueStart() {
        if (envelope.length < 2) {
            throw toolkit(UNAVAILABLE_ELEMENT);
        }
        int len = envelope[1] & 0xFF;
        if (len == 0x81) {
            return 3;
        }
        if (len == 0x82) {
            return 4;
        }
        if (len > 0x80) {
            throw toolkit(BAD_INPUT_PARAMETER);
        }
        return 2;
    }

    private int valueLength() {
        int len = envelope[1] & 0xFF;
        if (len == 0x81) {
            return envelope[2] & 0xFF;
        }
        if (len == 0x82) {
            return ((envelope[2] & 0xFF) << 8) | (envelope[3] & 0xFF);
        }
        return len;
    }

    public int envelopeLength() {
        return valueLength();
    }

    /**
     * Locates the occurrence'th TLV with the given tag inside the envelope.
     * Returns the tag found, and remembers it for getValue* calls.
     */
    public int findTlv(int tag, int occurrence) {
        int p = valueStart();
        int end = p + valueLength();
        int seen = 0;
        while (p + 2 <= end && p + 2 <= envelope.length) {
            int t = envelope[p] & 0xFF;
            int l = envelope[p + 1] & 0xFF;
            int vOff = p + 2;
            if (l == 0x81) {
                l = envelope[p + 2] & 0xFF;
                vOff = p + 3;
            }
            if ((t & 0x7F) == (tag & 0x7F)) {
                seen++;
                if (seen >= occurrence) {
                    tlvCursor = p;
                    tlvValueOffset = vOff;
                    tlvValueLength = l;
                    return t;
                }
            }
            p = vOff + l;
        }
        throw toolkit(UNAVAILABLE_ELEMENT);
    }

    private void requireTlv() {
        if (tlvCursor < 0) {
            throw toolkit(UNAVAILABLE_ELEMENT);
        }
    }

    public int tlvValueLength() {
        requireTlv();
        return tlvValueLength;
    }

    public int tlvValueByte(int offset) {
        requireTlv();
        if (offset < 0 || offset >= tlvValueLength) {
            throw toolkit(OUT_OF_TLV_BOUNDARIES);
        }
        return envelope[tlvValueOffset + offset];
    }

    public int copyTlvValue(int valueOffset, byte[] dst, int dstOffset, int length) {
        requireTlv();
        if (valueOffset < 0 || length < 0 || valueOffset + length > tlvValueLength) {
            throw toolkit(OUT_OF_TLV_BOUNDARIES);
        }
        System.arraycopy(envelope, tlvValueOffset + valueOffset, dst, dstOffset, length);
        return dstOffset + length;
    }

    public int compareTlvValue(int valueOffset, byte[] other, int otherOffset, int length) {
        requireTlv();
        if (valueOffset < 0 || length < 0 || valueOffset + length > tlvValueLength) {
            throw toolkit(OUT_OF_TLV_BOUNDARIES);
        }
        for (int i = 0; i < length; i++) {
            int a = envelope[tlvValueOffset + valueOffset + i] & 0xFF;
            int b = other[otherOffset + i] & 0xFF;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* proactive commands                                                  */
    /* ------------------------------------------------------------------ */

    public void proactiveInit(int commandType, int commandQualifier, int deviceId) {
        proactive = new ByteArrayOutputStream();
        proactiveTag = commandType;
        // command details TLV: tag 01, length 03, cmd number, type, qualifier
        proactive.write(0x01);
        proactive.write(0x03);
        proactive.write(0x01);
        proactive.write(commandType & 0xFF);
        proactive.write(commandQualifier & 0xFF);
        // device identities: source UICC (81), destination as given
        proactive.write(0x02);
        proactive.write(0x02);
        proactive.write(0x81);
        proactive.write(deviceId & 0xFF);
    }

    private ByteArrayOutputStream requireProactive() {
        if (proactive == null) {
            throw toolkit(HANDLER_NOT_AVAILABLE);
        }
        return proactive;
    }

    public void appendTlv(int tag, byte[] value, int offset, int length) {
        ByteArrayOutputStream p = requireProactive();
        p.write(tag & 0xFF);
        p.write(length & 0xFF);
        p.write(value, offset, length);
    }

    public void appendTlvByte(int tag, int value) {
        ByteArrayOutputStream p = requireProactive();
        p.write(tag & 0xFF);
        p.write(1);
        p.write(value & 0xFF);
    }

    public void appendTlvBytes(int tag, int value1, byte[] value, int offset, int length) {
        ByteArrayOutputStream p = requireProactive();
        p.write(tag & 0xFF);
        p.write(length + 1);
        p.write(value1 & 0xFF);
        p.write(value, offset, length);
    }

    /** Completes the command, records it, and returns the general result. */
    public byte proactiveSend() {
        ByteArrayOutputStream body = requireProactive();
        byte[] value = body.toByteArray();
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(0xD0);
        full.write(value.length);
        full.write(value, 0, value.length);
        issued.add(full.toByteArray());
        proactive = null;
        return generalResult;
    }

    public List<byte[]> issuedCommands() {
        return issued;
    }

    public void clearIssued() {
        issued.clear();
    }

    public int proactiveCommandType() {
        return proactiveTag;
    }

    /* ------------------------------------------------------------------ */

    public String describe() {
        StringBuilder sb = new StringBuilder();
        List<Registry> rs = registries();
        sb.append("toolkit registrations: ").append(rs.size()).append('\n');
        for (int i = 0; i < rs.size(); i++) {
            Registry r = rs.get(i);
            sb.append("  ").append(r.applet).append('\n');
            StringBuilder ev = new StringBuilder();
            for (int e = 0; e < r.events.length; e++) {
                if (r.events[e]) {
                    ev.append(ev.length() > 0 ? ", " : "").append(e);
                }
            }
            sb.append("    events: ").append(ev.length() > 0 ? ev : "none").append('\n');
            sb.append("    config: priority=").append(r.priority)
                    .append(" timers=").append(r.maxTimers)
                    .append(" maxMenuEntries=").append(r.maxMenuEntries)
                    .append(" maxMenuTextLength=").append(r.maxMenuTextLength)
                    .append(r.config == null ? "  (defaults, no CA tag)" : "  (from CA)")
                    .append('\n');
            if (!r.menuEntries.isEmpty()) {
                sb.append("    menu  : ").append(r.menuEntries).append('\n');
            }
        }
        if (!issued.isEmpty()) {
            sb.append("proactive commands issued:\n");
            for (int i = 0; i < issued.size(); i++) {
                sb.append("  ").append(Hex.toSpaced(issued.get(i))).append('\n');
            }
        }
        return sb.toString();
    }

    public static JCThrow toolkit(short reason) {
        return JCThrow.framework("uicc/toolkit/ToolkitException", reason);
    }
}
