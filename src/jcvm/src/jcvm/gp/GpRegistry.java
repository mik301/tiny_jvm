package jcvm.gp;

import java.util.ArrayList;
import java.util.List;

import jcvm.util.Hex;

/**
 * The GlobalPlatform registry: what is on the card and what state it is in.
 *
 * Three kinds of entry matter here:
 *   ELF          an Executable Load File, i.e. a loaded CAP (package)
 *   MODULE       an Executable Module inside an ELF, i.e. an applet class
 *   APPLICATION  an installed applet instance
 */
public final class GpRegistry {

    public static final int TYPE_ISD = 0;
    public static final int TYPE_ELF = 1;
    public static final int TYPE_MODULE = 2;
    public static final int TYPE_APPLICATION = 3;

    /* card life cycle */
    public static final int CARD_OP_READY = 0x01;
    public static final int CARD_INITIALIZED = 0x07;
    public static final int CARD_SECURED = 0x0F;
    public static final int CARD_LOCKED = 0x7F;
    public static final int CARD_TERMINATED = 0xFF;

    /* ELF life cycle */
    public static final int ELF_LOADED = 0x01;

    /* application life cycle */
    public static final int APP_INSTALLED = 0x03;
    public static final int APP_SELECTABLE = 0x07;
    public static final int APP_LOCKED = 0x83;

    public static final class Entry {

        public final byte[] aid;
        public final int type;
        public int lifeCycle;
        public byte privileges;
        /** For a MODULE or APPLICATION: the ELF it came from. */
        public byte[] elfAid;
        /** For an ELF: the module AIDs it contains. */
        public final List<byte[]> modules = new ArrayList<byte[]>();
        /** Security domain this entry is associated with, set by extradition. */
        public byte[] associatedSd;

        Entry(byte[] aid, int type, int lifeCycle) {
            this.aid = aid;
            this.type = type;
            this.lifeCycle = lifeCycle;
        }

        public String typeName() {
            switch (type) {
                case TYPE_ISD: return "ISD";
                case TYPE_ELF: return "ELF";
                case TYPE_MODULE: return "MODULE";
                default: return "APP";
            }
        }

        public String lifeCycleName() {
            if (type == TYPE_ELF) {
                return "LOADED";
            }
            switch (lifeCycle) {
                case APP_INSTALLED: return "INSTALLED";
                case APP_SELECTABLE: return "SELECTABLE";
                case APP_LOCKED: return "LOCKED";
                default: return "0x" + Integer.toHexString(lifeCycle);
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-7s %-32s %-11s priv=%02X",
                    typeName(), Hex.toHex(aid), lifeCycleName(),
                    Integer.valueOf(privileges & 0xFF)));
            if (elfAid != null) {
                sb.append("  in ").append(Hex.toHex(elfAid));
            }
            if (associatedSd != null) {
                sb.append("  sd ").append(Hex.toHex(associatedSd));
            }
            if (!modules.isEmpty()) {
                sb.append("  modules:");
                for (int i = 0; i < modules.size(); i++) {
                    sb.append(' ').append(Hex.toHex(modules.get(i)));
                }
            }
            return sb.toString();
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    public int cardLifeCycle = CARD_OP_READY;

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(byte[] aid) {
        for (int i = 0; i < entries.size(); i++) {
            if (java.util.Arrays.equals(entries.get(i).aid, aid)) {
                return entries.get(i);
            }
        }
        return null;
    }

    public Entry find(byte[] aid, int type) {
        Entry e = find(aid);
        return (e != null && e.type == type) ? e : null;
    }

    public Entry addIsd(byte[] aid) {
        Entry e = new Entry(aid, TYPE_ISD, APP_SELECTABLE);
        e.privileges = (byte) 0x9E;   // security domain, card lock, card terminate...
        entries.add(e);
        return e;
    }

    public Entry addElf(byte[] aid) {
        Entry e = new Entry(aid, TYPE_ELF, ELF_LOADED);
        entries.add(e);
        return e;
    }

    public Entry addModule(byte[] aid, byte[] elfAid) {
        Entry e = new Entry(aid, TYPE_MODULE, ELF_LOADED);
        e.elfAid = elfAid;
        entries.add(e);
        return e;
    }

    public Entry addApplication(byte[] aid, byte[] elfAid, byte privileges,
            boolean selectable) {
        Entry e = new Entry(aid, TYPE_APPLICATION,
                selectable ? APP_SELECTABLE : APP_INSTALLED);
        e.elfAid = elfAid;
        e.privileges = privileges;
        entries.add(e);
        return e;
    }

    /** Every application installed from a given load file. */
    public List<Entry> applicationsOf(byte[] elfAid) {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type == TYPE_APPLICATION && e.elfAid != null
                    && java.util.Arrays.equals(e.elfAid, elfAid)) {
                out.add(e);
            }
        }
        return out;
    }

    public List<Entry> modulesOf(byte[] elfAid) {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type == TYPE_MODULE && e.elfAid != null
                    && java.util.Arrays.equals(e.elfAid, elfAid)) {
                out.add(e);
            }
        }
        return out;
    }

    public boolean remove(byte[] aid) {
        Entry e = find(aid);
        if (e == null) {
            return false;
        }
        entries.remove(e);
        // deleting an ELF removes its modules too
        if (e.type == TYPE_ELF) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry k = entries.get(i);
                if (k.type == TYPE_MODULE && java.util.Arrays.equals(k.elfAid, aid)) {
                    entries.remove(i);
                }
            }
        }
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("card life cycle: 0x")
                .append(Integer.toHexString(cardLifeCycle).toUpperCase()).append('\n');
        for (int i = 0; i < entries.size(); i++) {
            sb.append("  ").append(entries.get(i)).append('\n');
        }
        return sb.toString();
    }
}
