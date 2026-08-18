package jcvm.uicc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jcvm.rt.JCThrow;
import jcvm.util.Hex;

/**
 * A small in-memory UICC file system for uicc.access.FileView.
 *
 * Files are held in a flat map keyed by file identifier. Real UICCs have a
 * DF/ADF hierarchy and path based selection; this keeps only what an applet
 * usually needs - select by FID, then read and update - and is documented as a
 * simplification rather than a faithful ETSI file system.
 */
public final class UiccFileSystem {

    public static final byte TRANSPARENT = 0;
    public static final byte LINEAR_FIXED = 1;
    public static final byte CYCLIC = 2;
    public static final byte DEDICATED = 3;

    /* UICCException reason codes (TS 102 241) */
    public static final short FILE_NOT_FOUND = 1;
    public static final short SECURITY_STATUS_NOT_SATISFIED = 2;
    public static final short NO_EF_SELECTED = 3;
    public static final short INVALID_MODE = 4;
    public static final short COMMAND_INCOMPATIBLE = 5;
    public static final short OUT_OF_FILE_BOUNDARIES = 6;
    public static final short RECORD_NOT_FOUND = 7;
    public static final short MEMORY_PROBLEM = 8;
    public static final short INTERNAL_ERROR = 9;

    /* record access modes */
    public static final byte REC_ACC_MODE_NEXT = 2;
    public static final byte REC_ACC_MODE_PREVIOUS = 3;
    public static final byte REC_ACC_MODE_ABSOLUTE = 4;
    public static final byte REC_ACC_MODE_CURRENT = 4;

    /** One elementary or dedicated file. */
    public static final class Ef {

        public final short fid;
        public final byte type;
        public byte[] data;
        public int recordLength;
        public int recordCount;

        Ef(short fid, byte type, byte[] data, int recordLength, int recordCount) {
            this.fid = fid;
            this.type = type;
            this.data = data;
            this.recordLength = recordLength;
            this.recordCount = recordCount;
        }

        public boolean isRecordBased() {
            return type == LINEAR_FIXED || type == CYCLIC;
        }

        public String toString() {
            String kind = type == TRANSPARENT ? "transparent"
                    : type == LINEAR_FIXED ? "linear-fixed"
                    : type == CYCLIC ? "cyclic" : "df";
            return String.format("%04X  %-12s %d bytes%s",
                    Short.valueOf(fid), kind, Integer.valueOf(data.length),
                    isRecordBased() ? ("  " + recordCount + " x " + recordLength) : "");
        }
    }

    private final Map<Short, Ef> files = new LinkedHashMap<Short, Ef>();
    private Ef current;
    private int currentRecord;

    public UiccFileSystem() {
        // A minimal default so an applet that selects common files finds them.
        addTransparent((short) 0x2FE2, 10);      // EF ICCID
        addTransparent((short) 0x6F07, 9);       // EF IMSI
        addTransparent((short) 0x6F31, 1);       // EF HPPLMN
        addLinearFixed((short) 0x6F3C, 176, 10); // EF SMS
        addLinearFixed((short) 0x6F42, 28, 4);   // EF SMSP
    }

    /* ---------------- definition ---------------- */

    public Ef addTransparent(short fid, int size) {
        Ef e = new Ef(fid, TRANSPARENT, new byte[size], 0, 0);
        files.put(Short.valueOf(fid), e);
        return e;
    }

    public Ef addLinearFixed(short fid, int recordLength, int recordCount) {
        Ef e = new Ef(fid, LINEAR_FIXED, new byte[recordLength * recordCount],
                recordLength, recordCount);
        files.put(Short.valueOf(fid), e);
        return e;
    }

    public Ef addCyclic(short fid, int recordLength, int recordCount) {
        Ef e = new Ef(fid, CYCLIC, new byte[recordLength * recordCount],
                recordLength, recordCount);
        files.put(Short.valueOf(fid), e);
        return e;
    }

    public List<Ef> allFiles() {
        return new ArrayList<Ef>(files.values());
    }

    public Ef file(short fid) {
        return files.get(Short.valueOf(fid));
    }

    public Ef currentFile() {
        return current;
    }

    /**
     * Loads a file system description. One file per line:
     *
     *   transparent 6F07 9
     *   linear      6F3C 176 10
     *   cyclic      6F43 20 5
     *   data        6F07 001122334455667788
     */
    public void load(File f) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String line;
            int no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                String[] t = line.split("\\s+");
                try {
                    short fid = (short) Integer.parseInt(t[1], 16);
                    if ("transparent".equalsIgnoreCase(t[0])) {
                        addTransparent(fid, Integer.parseInt(t[2]));
                    } else if ("linear".equalsIgnoreCase(t[0])) {
                        addLinearFixed(fid, Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                    } else if ("cyclic".equalsIgnoreCase(t[0])) {
                        addCyclic(fid, Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                    } else if ("data".equalsIgnoreCase(t[0])) {
                        Ef e = file(fid);
                        if (e == null) {
                            throw new IllegalArgumentException("no such file " + t[1]);
                        }
                        byte[] v = Hex.parse(t[2]);
                        System.arraycopy(v, 0, e.data, 0,
                                Math.min(v.length, e.data.length));
                    } else {
                        throw new IllegalArgumentException("unknown kind " + t[0]);
                    }
                } catch (RuntimeException e) {
                    throw new IOException(f.getName() + ":" + no + ": " + e.getMessage());
                }
            }
        } finally {
            r.close();
        }
    }

    /* ---------------- FileView operations ---------------- */

    public void select(short fid) {
        Ef e = files.get(Short.valueOf(fid));
        if (e == null) {
            throw uicc(FILE_NOT_FOUND);
        }
        current = e;
        currentRecord = e.isRecordBased() ? 1 : 0;
    }

    private Ef requireSelected() {
        if (current == null) {
            throw uicc(NO_EF_SELECTED);
        }
        return current;
    }

    private Ef requireTransparent() {
        Ef e = requireSelected();
        if (e.type != TRANSPARENT) {
            throw uicc(COMMAND_INCOMPATIBLE);
        }
        return e;
    }

    private Ef requireRecords() {
        Ef e = requireSelected();
        if (!e.isRecordBased()) {
            throw uicc(COMMAND_INCOMPATIBLE);
        }
        return e;
    }

    public void readBinary(int fileOffset, byte[] dst, int dstOffset, int length) {
        Ef e = requireTransparent();
        if (fileOffset < 0 || length < 0 || fileOffset + length > e.data.length) {
            throw uicc(OUT_OF_FILE_BOUNDARIES);
        }
        System.arraycopy(e.data, fileOffset, dst, dstOffset, length);
    }

    public void updateBinary(int fileOffset, byte[] src, int srcOffset, int length) {
        Ef e = requireTransparent();
        if (fileOffset < 0 || length < 0 || fileOffset + length > e.data.length) {
            throw uicc(OUT_OF_FILE_BOUNDARIES);
        }
        System.arraycopy(src, srcOffset, e.data, fileOffset, length);
    }

    /** Resolves a record number against an access mode, updating the pointer. */
    private int resolveRecord(Ef e, int recNumber, byte mode) {
        int n;
        if (mode == REC_ACC_MODE_ABSOLUTE) {
            n = recNumber;
        } else if (mode == REC_ACC_MODE_NEXT) {
            n = currentRecord + 1;
        } else if (mode == REC_ACC_MODE_PREVIOUS) {
            n = currentRecord - 1;
        } else {
            throw uicc(INVALID_MODE);
        }
        if (n < 1 || n > e.recordCount) {
            throw uicc(RECORD_NOT_FOUND);
        }
        currentRecord = n;
        return n;
    }

    public void readRecord(int recNumber, byte mode, int recOffset,
            byte[] dst, int dstOffset, int length) {
        Ef e = requireRecords();
        int n = resolveRecord(e, recNumber, mode);
        if (recOffset < 0 || length < 0 || recOffset + length > e.recordLength) {
            throw uicc(OUT_OF_FILE_BOUNDARIES);
        }
        System.arraycopy(e.data, (n - 1) * e.recordLength + recOffset,
                dst, dstOffset, length);
    }

    public void updateRecord(int recNumber, byte mode, int recOffset,
            byte[] src, int srcOffset, int length) {
        Ef e = requireRecords();
        int n = resolveRecord(e, recNumber, mode);
        if (recOffset < 0 || length < 0 || recOffset + length > e.recordLength) {
            throw uicc(OUT_OF_FILE_BOUNDARIES);
        }
        System.arraycopy(src, srcOffset, e.data,
                (n - 1) * e.recordLength + recOffset, length);
    }

    /** Linear search for a pattern, returning the 1 based record number. */
    public int searchRecord(byte[] pattern, int patOffset, int patLength) {
        Ef e = requireRecords();
        for (int rec = 1; rec <= e.recordCount; rec++) {
            int base = (rec - 1) * e.recordLength;
            for (int at = 0; at + patLength <= e.recordLength; at++) {
                boolean hit = true;
                for (int i = 0; i < patLength; i++) {
                    if (e.data[base + at + i] != pattern[patOffset + i]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    currentRecord = rec;
                    return rec;
                }
            }
        }
        throw uicc(RECORD_NOT_FOUND);
    }

    public int fileSize() {
        return requireSelected().data.length;
    }

    public int recordLength() {
        return requireRecords().recordLength;
    }

    public int recordCount() {
        return requireRecords().recordCount;
    }

    public int currentRecordNumber() {
        return currentRecord;
    }

    public void reset() {
        current = null;
        currentRecord = 0;
    }

    private static JCThrow uicc(short reason) {
        return JCThrow.framework("uicc/access/UICCException", reason);
    }
}
