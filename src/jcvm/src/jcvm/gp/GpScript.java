package jcvm.gp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jcvm.cap.CapFile;
import jcvm.cap.CapPackage;
import jcvm.util.Hex;

/**
 * Builds the APDU sequence a terminal sends to put a CAP on a card:
 * INSTALL [for load], a run of LOAD blocks, then INSTALL [for install and make
 * selectable]. Useful for seeing the real byte level flow, and for replaying it
 * against this VM or a physical card.
 */
public final class GpScript {

    /** Components are loaded in this order, as GlobalPlatform requires. */
    private static final int[] LOAD_ORDER = {
        CapFile.COMPONENT_HEADER,
        CapFile.COMPONENT_DIRECTORY,
        CapFile.COMPONENT_IMPORT,
        CapFile.COMPONENT_APPLET,
        CapFile.COMPONENT_CLASS,
        CapFile.COMPONENT_METHOD,
        CapFile.COMPONENT_STATIC_FIELD,
        CapFile.COMPONENT_EXPORT,
        CapFile.COMPONENT_CONSTANT_POOL,
        CapFile.COMPONENT_REFERENCE_LOCATION
    };

    private GpScript() {
    }

    /** The Load File Data Block: the components concatenated in load order. */
    public static byte[] loadFileDataBlock(CapFile cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < LOAD_ORDER.length; i++) {
            byte[] comp = cap.component(LOAD_ORDER[i]);
            if (comp != null) {
                out.write(comp);
            }
        }
        return out.toByteArray();
    }

    /**
     * The full sequence. {@code blockSize} is the number of data bytes per LOAD
     * command; 200 or 240 is typical for a real card.
     */
    public static List<byte[]> buildSequence(File capFile, byte[] moduleAid,
            byte[] instanceAid, byte[] appParams, int blockSize) throws IOException {
        return buildSequence(capFile, moduleAid, instanceAid, appParams, null, blockSize);
    }

    /**
     * As above, with a system specific parameters block for the EF tag - build
     * one with {@link InstallParams#buildToolkitSystemParams}.
     */
    public static List<byte[]> buildSequence(File capFile, byte[] moduleAid,
            byte[] instanceAid, byte[] appParams, byte[] systemParams, int blockSize)
            throws IOException {
        CapFile cap = CapFile.load(capFile);
        CapPackage pkg = CapPackage.parse(cap);
        byte[] elfAid = pkg.thisPackage.aid;
        if (moduleAid == null) {
            if (pkg.applets.isEmpty()) {
                throw new IllegalArgumentException(
                        "this CAP has no applet module to install");
            }
            moduleAid = pkg.applets.get(0).aid;
        }
        if (instanceAid == null) {
            instanceAid = moduleAid;
        }

        List<byte[]> out = new ArrayList<byte[]>();
        out.addAll(buildLoadSequence(cap, elfAid, blockSize));
        out.add(buildInstallCommand(elfAid, moduleAid, instanceAid,
                appParams, systemParams));
        return out;
    }

    /** The package AID a CAP declares, i.e. the AID of its load file. */
    public static byte[] packageAid(CapFile cap) {
        return CapPackage.parse(cap).thisPackage.aid;
    }

    /** INSTALL [for load] followed by the LOAD blocks, without the install. */
    public static List<byte[]> buildLoadSequence(File capFile, int blockSize)
            throws IOException {
        CapFile cap = CapFile.load(capFile);
        return buildLoadSequence(cap, packageAid(cap), blockSize);
    }

    public static List<byte[]> buildLoadSequence(CapFile cap, byte[] elfAid,
            int blockSize) throws IOException {
        List<byte[]> out = new ArrayList<byte[]>();

        ByteArrayOutputStream d = new ByteArrayOutputStream();
        lv(d, elfAid);              // load file AID
        lv(d, new byte[0]);         // security domain AID (default)
        lv(d, new byte[0]);         // load file data block hash
        lv(d, new byte[0]);         // load parameters
        lv(d, new byte[0]);         // load token
        out.add(apdu(0x80, 0xE6, 0x02, 0x00, d.toByteArray()));

        byte[] block = loadFileDataBlock(cap);
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        wrapped.write(0xC4);
        if (block.length < 0x80) {
            wrapped.write(block.length);
        } else if (block.length < 0x100) {
            wrapped.write(0x81);
            wrapped.write(block.length);
        } else {
            wrapped.write(0x82);
            wrapped.write((block.length >> 8) & 0xFF);
            wrapped.write(block.length & 0xFF);
        }
        wrapped.write(block, 0, block.length);
        byte[] payload = wrapped.toByteArray();

        int offset = 0;
        int blockNumber = 0;
        while (offset < payload.length) {
            int n = Math.min(blockSize, payload.length - offset);
            byte[] chunk = new byte[n];
            System.arraycopy(payload, offset, chunk, 0, n);
            offset += n;
            boolean last = offset >= payload.length;
            out.add(apdu(0x80, 0xE8, last ? 0x80 : 0x00, blockNumber++, chunk));
        }
        return out;
    }

    /** A single INSTALL [for install and make selectable]. */
    public static byte[] buildInstallCommand(byte[] elfAid, byte[] moduleAid,
            byte[] instanceAid, byte[] appParams, byte[] systemParams) {
        ByteArrayOutputStream ins = new ByteArrayOutputStream();
        lv(ins, elfAid);
        lv(ins, moduleAid);
        lv(ins, instanceAid == null || instanceAid.length == 0 ? moduleAid : instanceAid);
        lv(ins, new byte[]{0x00});         // privileges
        lv(ins, InstallParams.build(appParams, systemParams));
        lv(ins, new byte[0]);              // install token
        return apdu(0x80, 0xE6, 0x0C, 0x00, ins.toByteArray());
    }

    /** SELECT by DF name for an AID; an empty AID selects the card manager. */
    public static byte[] selectCommand(byte[] aid) {
        byte[] a = aid == null ? new byte[0] : aid;
        return apdu(0x00, 0xA4, 0x04, 0x00, a);
    }

    private static void lv(ByteArrayOutputStream out, byte[] value) {
        out.write(value.length);
        out.write(value, 0, value.length);
    }

    private static byte[] apdu(int cla, int ins, int p1, int p2, byte[] data) {
        byte[] out = new byte[5 + data.length];
        out[0] = (byte) cla;
        out[1] = (byte) ins;
        out[2] = (byte) p1;
        out[3] = (byte) p2;
        out[4] = (byte) data.length;
        System.arraycopy(data, 0, out, 5, data.length);
        return out;
    }

    /** Renders the sequence as a jcvm script. */
    public static String toScript(List<byte[]> apdus, byte[] instanceAid) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GlobalPlatform load and install sequence\n");
        sb.append("send 00 A4 04 00 00                 # SELECT the card manager\n");
        sb.append("send 80 50 00 00 08 0102030405060708  # INITIALIZE UPDATE\n");
        sb.append("send 84 82 00 00 10 ")
                .append("00112233445566778899AABBCCDDEEFF")
                .append("  # EXTERNAL AUTHENTICATE\n");
        for (int i = 0; i < apdus.size(); i++) {
            byte[] a = apdus.get(i);
            String what;
            int ins = a[1] & 0xFF;
            if (ins == 0xE6) {
                what = (a[2] & 0x02) != 0 ? "INSTALL [for load]"
                        : "INSTALL [for install and make selectable]";
            } else {
                what = "LOAD block " + (a[3] & 0xFF)
                        + ((a[2] & 0x80) != 0 ? " (last)" : "");
            }
            sb.append("send ").append(Hex.toHex(a)).append("   # ").append(what)
                    .append('\n');
        }
        sb.append("send 80 F2 40 00 02 4F00            # GET STATUS, applications\n");
        sb.append("send 00 A4 04 00 ")
                .append(String.format("%02X", Integer.valueOf(instanceAid.length)))
                .append(' ').append(Hex.toHex(instanceAid))
                .append("   # SELECT the applet\n");
        return sb.toString();
    }
}
