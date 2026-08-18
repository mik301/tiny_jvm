package jcvm.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jcvm.util.Hex;

/**
 * Produces a small but complete CAP file containing one hand assembled applet.
 *
 * This exists because a real CAP normally comes out of the Java Card SDK
 * converter, which needs the SDK to be installed. The generated CAP uses the
 * same token assignment as res/api-tokens.txt, so the VM and this builder
 * always agree.
 *
 * The demo applet is the bytecode equivalent of:
 *
 * <pre>
 * package com.example.demo;
 * import javacard.framework.*;
 *
 * public class DemoApplet extends Applet {
 *     private short counter;          // instance field token 0
 *     private byte[] mem;             // instance field token 1 (reference)
 *
 *     protected DemoApplet() {
 *         counter = 0;
 *         mem = new byte[32];
 *         register();
 *     }
 *
 *     public static void install(byte[] bArray, short bOffset, byte bLength) {
 *         new DemoApplet();
 *     }
 *
 *     public void process(APDU apdu) {
 *         if (selectingApplet()) { return; }
 *         byte[] buf = apdu.getBuffer();
 *         switch (buf[ISO7816.OFFSET_INS]) {
 *             case (byte) 0x10:                       // increment, return counter
 *                 counter = (short) (counter + 1);
 *                 Util.setShort(buf, (short) 0, counter);
 *                 apdu.setOutgoingAndSend((short) 0, (short) 2);
 *                 return;
 *             case (byte) 0x20:                       // echo the command data
 *                 short lc = apdu.setIncomingAndReceive();
 *                 apdu.setOutgoingAndSend((short) 5, lc);
 *                 return;
 *             case (byte) 0x30:                       // mem[0] = data[0]
 *                 mem[0] = buf[5];
 *                 return;
 *             case (byte) 0x40:                       // return mem[0]
 *                 buf[0] = mem[0];
 *                 apdu.setOutgoingAndSend((short) 0, (short) 1);
 *                 return;
 *             default:
 *                 ISOException.throwIt((short) 0x6D00);
 *         }
 *     }
 * }
 * </pre>
 */
public final class CapBuilder {

    /* ---------------- identity ---------------- */

    public static final byte[] PACKAGE_AID = {1, 2, 3, 4, 5, 6};
    public static final byte[] APPLET_AID = {1, 2, 3, 4, 5, 6, 7};
    public static final byte[] FRAMEWORK_AID = Hex.parse("A0000000620101");
    private static final String PACKAGE_PATH = "com/example/demo";

    /* ---------------- API tokens (must match res/api-tokens.txt) ------- */

    private static final int CLS_APDU = 1;
    private static final int CLS_APPLET = 3;
    private static final int CLS_ISOEXCEPTION = 8;
    private static final int CLS_UTIL = 15;

    private static final int APPLET_INIT = 0;              // static token
    private static final int APPLET_REGISTER = 4;          // virtual token
    private static final int APPLET_SELECTING = 6;         // virtual token
    private static final int APDU_GETBUFFER = 0;
    private static final int APDU_SETINCOMING = 1;
    private static final int APDU_SETOUTGOING_AND_SEND = 8;
    private static final int ISOEXCEPTION_THROWIT = 1;     // static token
    private static final int UTIL_SETSHORT = 6;            // static token

    /* ---------------- constant pool indices ---------------- */

    private static final int CP_CLASS_DEMO = 0;
    private static final int CP_APPLET_INIT = 1;
    private static final int CP_DEMO_INIT = 2;
    private static final int CP_REGISTER = 3;
    private static final int CP_SELECTING = 4;
    private static final int CP_GETBUFFER = 5;
    private static final int CP_SETINCOMING = 6;
    private static final int CP_SETOUTSEND = 7;
    private static final int CP_THROWIT = 8;
    private static final int CP_SETSHORT = 9;
    private static final int CP_FIELD_COUNTER = 10;
    private static final int CP_FIELD_MEM = 11;
    private static final int CP_COUNT = 12;

    /* ---------------- opcodes used here ---------------- */

    private static final int SCONST_0 = 3;
    private static final int SCONST_1 = 4;
    private static final int SCONST_2 = 5;
    private static final int BSPUSH = 16;
    private static final int SSPUSH = 17;
    private static final int ALOAD_0 = 24;
    private static final int ALOAD_1 = 25;
    private static final int ALOAD_2 = 26;
    private static final int SLOAD_3 = 31;
    private static final int BALOAD = 37;
    private static final int ASTORE_2 = 45;
    private static final int SSTORE_3 = 50;
    private static final int BASTORE = 56;
    private static final int POP = 59;
    private static final int DUP = 61;
    private static final int SADD = 65;
    private static final int RETURN = 122;
    private static final int GETFIELD_A = 131;
    private static final int GETFIELD_S = 133;
    private static final int PUTFIELD_A = 135;
    private static final int PUTFIELD_S = 137;
    private static final int INVOKEVIRTUAL = 139;
    private static final int INVOKESPECIAL = 140;
    private static final int INVOKESTATIC = 141;
    private static final int NEW = 143;
    private static final int NEWARRAY = 144;
    private static final int IFEQ_W = 152;
    private static final int IF_SCMPNE_W = 163;

    private static final int T_BYTE = 11;

    /* ================================================================== */

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "demo.cap");
        writeCap(out);
        System.out.println("wrote " + out.getPath());
        System.out.println("  package AID  " + Hex.toHex(PACKAGE_AID));
        System.out.println("  applet AID   " + Hex.toHex(APPLET_AID));
    }

    /* ---------------- code generation ---------------- */

    private static byte[] installCode() {
        Asm a = new Asm();
        a.op2(NEW, CP_CLASS_DEMO);
        a.op(DUP);
        a.op2(INVOKESPECIAL, CP_DEMO_INIT);
        a.op(POP);
        a.op(RETURN);
        return a.toBytes();
    }

    private static byte[] initCode() {
        Asm a = new Asm();
        a.op(ALOAD_0);
        a.op2(INVOKESPECIAL, CP_APPLET_INIT);
        a.op(ALOAD_0);
        a.op(SCONST_0);
        a.op1(PUTFIELD_S, CP_FIELD_COUNTER);
        a.op(ALOAD_0);
        a.op1(BSPUSH, 32);
        a.op1(NEWARRAY, T_BYTE);
        a.op1(PUTFIELD_A, CP_FIELD_MEM);
        a.op(ALOAD_0);
        a.op2(INVOKEVIRTUAL, CP_REGISTER);
        a.op(RETURN);
        return a.toBytes();
    }

    private static byte[] processCode() {
        Asm a = new Asm();

        // if (selectingApplet()) return;
        a.op(ALOAD_0);
        a.op2(INVOKEVIRTUAL, CP_SELECTING);
        a.branch(IFEQ_W, "notSelect");
        a.op(RETURN);

        a.label("notSelect");
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(ASTORE_2);

        // ins = buf[1]
        a.op(ALOAD_2);
        a.op(SCONST_1);
        a.op(BALOAD);
        a.op(SSTORE_3);

        // case 0x10
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x10);
        a.branch(IF_SCMPNE_W, "case20");
        a.op(ALOAD_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_COUNTER);
        a.op(SCONST_1);
        a.op(SADD);
        a.op1(PUTFIELD_S, CP_FIELD_COUNTER);
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_COUNTER);
        a.op2(INVOKESTATIC, CP_SETSHORT);
        a.op(POP);
        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(SCONST_2);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);

        // case 0x20
        a.label("case20");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x20);
        a.branch(IF_SCMPNE_W, "case30");
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_SETINCOMING);
        a.op(SSTORE_3);
        a.op(ALOAD_1);
        a.op1(BSPUSH, 5);
        a.op(SLOAD_3);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);

        // case 0x30 : mem[0] = buf[5]
        a.label("case30");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x30);
        a.branch(IF_SCMPNE_W, "case40");
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_FIELD_MEM);
        a.op(SCONST_0);
        a.op(ALOAD_2);
        a.op1(BSPUSH, 5);
        a.op(BALOAD);
        a.op(BASTORE);
        a.op(RETURN);

        // case 0x40 : buf[0] = mem[0]; send 1 byte
        a.label("case40");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x40);
        a.branch(IF_SCMPNE_W, "unknown");
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_FIELD_MEM);
        a.op(SCONST_0);
        a.op(BALOAD);
        a.op(BASTORE);
        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(SCONST_1);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);

        a.label("unknown");
        a.op2(SSPUSH, 0x6D00);
        a.op2(INVOKESTATIC, CP_THROWIT);
        a.op(RETURN);

        return a.toBytes();
    }

    /* ---------------- components ---------------- */

    /** All components concatenated, i.e. a GlobalPlatform load file data block. */
    public static byte[] buildComponentStream() throws IOException {
        byte[] install = installCode();
        byte[] init = initCode();
        byte[] process = processCode();

        // method component: handler_count, then the three methods
        int offInstall = 1;
        int offInit = offInstall + 2 + install.length;
        int offProcess = offInit + 2 + init.length;

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(0);                                  // handler_count
        writeMethod(m, 2, 3, 0, install);            // maxStack, nargs, maxLocals
        writeMethod(m, 2, 1, 0, init);
        writeMethod(m, 5, 2, 2, process);
        byte[] methodInfo = m.toByteArray();

        byte[] headerInfo = header();
        byte[] appletInfo = applet(offInstall);
        byte[] importInfo = imports();
        byte[] cpInfo = constantPool(offInit);
        byte[] classInfo = classes(offProcess);
        byte[] staticInfo = staticField();
        byte[] refLocInfo = new byte[]{0, 0, 0, 0};
        byte[] exportInfo = new byte[]{0};

        int[] sizes = new int[11];
        sizes[0] = headerInfo.length;
        sizes[1] = 0;                                // filled in below
        sizes[2] = appletInfo.length;
        sizes[3] = importInfo.length;
        sizes[4] = cpInfo.length;
        sizes[5] = classInfo.length;
        sizes[6] = methodInfo.length;
        sizes[7] = staticInfo.length;
        sizes[8] = refLocInfo.length;
        sizes[9] = exportInfo.length;
        sizes[10] = 0;
        byte[] dirInfo = directory(sizes);
        sizes[1] = dirInfo.length;
        dirInfo = directory(sizes);

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(component(1, headerInfo));
        all.write(component(2, dirInfo));
        all.write(component(3, appletInfo));
        all.write(component(4, importInfo));
        all.write(component(5, cpInfo));
        all.write(component(6, classInfo));
        all.write(component(7, methodInfo));
        all.write(component(8, staticInfo));
        all.write(component(9, refLocInfo));
        all.write(component(10, exportInfo));
        return all.toByteArray();
    }

    public static void writeCap(File target) throws IOException {
        byte[] stream = buildComponentStream();
        String[] names = {"Header", "Directory", "Applet", "Import", "ConstantPool",
            "Class", "Method", "StaticField", "RefLocation", "Export"};
        OutputStream fos = new FileOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(fos);
        try {
            int p = 0;
            int i = 0;
            while (p + 3 <= stream.length) {
                int size = ((stream[p + 1] & 0xFF) << 8) | (stream[p + 2] & 0xFF);
                int total = size + 3;
                byte[] comp = new byte[total];
                System.arraycopy(stream, p, comp, 0, total);
                zip.putNextEntry(new ZipEntry(PACKAGE_PATH + "/javacard/"
                        + names[i] + ".cap"));
                zip.write(comp);
                zip.closeEntry();
                p += total;
                i++;
            }
        } finally {
            zip.close();
        }
    }

    /* ---------------- component bodies ---------------- */

    private static void writeMethod(ByteArrayOutputStream out, int maxStack,
            int nargs, int maxLocals, byte[] code) {
        out.write((0 << 4) | (maxStack & 0x0F));
        out.write(((nargs & 0x0F) << 4) | (maxLocals & 0x0F));
        out.write(code, 0, code.length);
    }

    private static byte[] header() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xDE);
        o.write(0xCA);
        o.write(0xFF);
        o.write(0xED);
        o.write(1);                       // CAP minor version
        o.write(2);                       // CAP major version -> 2.1
        o.write(0);                       // flags
        o.write(0);                       // package minor
        o.write(1);                       // package major
        o.write(PACKAGE_AID.length);
        o.write(PACKAGE_AID, 0, PACKAGE_AID.length);
        return o.toByteArray();
    }

    private static byte[] directory(int[] sizes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < 11; i++) {
            o.write((sizes[i] >> 8) & 0xFF);
            o.write(sizes[i] & 0xFF);
        }
        o.write(0);
        o.write(0);   // static field image size
        o.write(0);
        o.write(0);   // array init count
        o.write(0);
        o.write(0);   // array init size
        o.write(1);   // import count
        o.write(1);   // applet count
        o.write(0);   // custom count
        return o.toByteArray();
    }

    private static byte[] applet(int installOffset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        o.write(APPLET_AID.length);
        o.write(APPLET_AID, 0, APPLET_AID.length);
        o.write((installOffset >> 8) & 0xFF);
        o.write(installOffset & 0xFF);
        return o.toByteArray();
    }

    private static byte[] imports() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        o.write(3);   // minor
        o.write(1);   // major
        o.write(FRAMEWORK_AID.length);
        o.write(FRAMEWORK_AID, 0, FRAMEWORK_AID.length);
        return o.toByteArray();
    }

    private static byte[] constantPool(int demoInitOffset) {
        byte[][] cp = new byte[CP_COUNT][];
        cp[CP_CLASS_DEMO] = new byte[]{1, 0, 0, 0};
        cp[CP_APPLET_INIT] = external(6, CLS_APPLET, APPLET_INIT);
        cp[CP_DEMO_INIT] = new byte[]{6, 0,
            (byte) (demoInitOffset >> 8), (byte) demoInitOffset};
        cp[CP_REGISTER] = external(3, CLS_APPLET, APPLET_REGISTER);
        cp[CP_SELECTING] = external(3, CLS_APPLET, APPLET_SELECTING);
        cp[CP_GETBUFFER] = external(3, CLS_APDU, APDU_GETBUFFER);
        cp[CP_SETINCOMING] = external(3, CLS_APDU, APDU_SETINCOMING);
        cp[CP_SETOUTSEND] = external(3, CLS_APDU, APDU_SETOUTGOING_AND_SEND);
        cp[CP_THROWIT] = external(6, CLS_ISOEXCEPTION, ISOEXCEPTION_THROWIT);
        cp[CP_SETSHORT] = external(6, CLS_UTIL, UTIL_SETSHORT);
        cp[CP_FIELD_COUNTER] = new byte[]{2, 0, 0, 0};
        cp[CP_FIELD_MEM] = new byte[]{2, 0, 0, 1};

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((CP_COUNT >> 8) & 0xFF);
        o.write(CP_COUNT & 0xFF);
        for (int i = 0; i < CP_COUNT; i++) {
            o.write(cp[i], 0, 4);
        }
        return o.toByteArray();
    }

    /** tag + external class ref (package token 0) + method/field token. */
    private static byte[] external(int tag, int classToken, int token) {
        return new byte[]{(byte) tag, (byte) 0x80, (byte) classToken, (byte) token};
    }

    private static byte[] classes(int processOffset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00);        // flags 0, interface_count 0
        o.write(0x80);        // super: external, package token 0
        o.write(CLS_APPLET);  // ... class token 3 (javacard.framework.Applet)
        o.write(2);           // declared_instance_size: counter + mem
        o.write(1);           // first_reference_token
        o.write(1);           // reference_count
        o.write(1);           // public_method_table_base  (process has token 1)
        o.write(1);           // public_method_table_count
        o.write(0);           // package_method_table_base
        o.write(0);           // package_method_table_count
        o.write((processOffset >> 8) & 0xFF);
        o.write(processOffset & 0xFF);
        return o.toByteArray();
    }

    private static byte[] staticField() {
        return new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    private static byte[] component(int tag, byte[] info) {
        byte[] out = new byte[info.length + 3];
        out[0] = (byte) tag;
        out[1] = (byte) (info.length >> 8);
        out[2] = (byte) info.length;
        System.arraycopy(info, 0, out, 3, info.length);
        return out;
    }
}
