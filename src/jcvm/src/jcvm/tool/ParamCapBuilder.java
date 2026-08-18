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
 * Builds a CAP whose applet parses the buffer the JCRE hands to install() and
 * gives the pieces back over APDUs, so install parameter handling can be
 * checked end to end without a Java Card SDK.
 *
 * The buffer a card passes to install() is the GlobalPlatform layout
 * {@code [len][instance AID][len][privileges][len][application parameters]},
 * where the last field is the value of tag C9 of INSTALL [for install].
 *
 * <pre>
 * package com.example.params;
 * import javacard.framework.*;
 *
 * public class ParamApplet extends Applet {
 *     private byte privileges;      // field token 0
 *     private byte[] instanceAid;   // field token 1 (reference)
 *     private byte[] params;        // field token 2 (reference)
 *
 *     private ParamApplet(byte[] bArray, short bOffset, byte bLength) {
 *         short i = bOffset;
 *         short len = bArray[i++];
 *         instanceAid = new byte[len];
 *         Util.arrayCopy(bArray, i, instanceAid, (short) 0, len);
 *         i += len;
 *
 *         len = bArray[i++];
 *         if (len &gt; 0) { privileges = bArray[i]; }
 *         i += len;
 *
 *         len = bArray[i++];
 *         params = new byte[len];
 *         Util.arrayCopy(bArray, i, params, (short) 0, len);
 *         register();
 *     }
 *
 *     public static void install(byte[] bArray, short bOffset, byte bLength) {
 *         new ParamApplet(bArray, bOffset, bLength);
 *     }
 *
 *     public void process(APDU apdu) {
 *         if (selectingApplet()) { return; }
 *         byte[] buf = apdu.getBuffer();
 *         switch (buf[ISO7816.OFFSET_INS]) {
 *             case (byte) 0x10: send(apdu, params);       return;  // C9 value
 *             case (byte) 0x20: send(apdu, instanceAid);  return;  // instance AID
 *             case (byte) 0x30:                                    // privileges
 *                 buf[0] = privileges;
 *                 apdu.setOutgoingAndSend((short) 0, (short) 1);
 *                 return;
 *             case (byte) 0x40:                                    // the lengths
 *                 buf[0] = (byte) instanceAid.length;
 *                 buf[1] = (byte) params.length;
 *                 apdu.setOutgoingAndSend((short) 0, (short) 2);
 *                 return;
 *             default:
 *                 ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
 *         }
 *     }
 *
 *     private void send(APDU apdu, byte[] data) {
 *         Util.arrayCopy(data, (short) 0, apdu.getBuffer(), (short) 0,
 *                        (short) data.length);
 *         apdu.setOutgoingAndSend((short) 0, (short) data.length);
 *     }
 * }
 * </pre>
 */
public final class ParamCapBuilder {

    public static final byte[] PACKAGE_AID = Hex.parse("A00000505241");
    public static final byte[] APPLET_AID = Hex.parse("A0000050524101");
    public static final byte[] FRAMEWORK_AID = Hex.parse("A0000000620101");
    private static final String PACKAGE_PATH = "com/example/params";

    /* API tokens, must agree with res/api-tokens.txt */
    private static final int CLS_APDU = 1;
    private static final int CLS_APPLET = 3;
    private static final int CLS_ISOEXCEPTION = 8;
    private static final int CLS_UTIL = 15;

    private static final int APPLET_INIT = 0;
    private static final int APPLET_REGISTER = 4;
    private static final int APPLET_SELECTING = 6;
    private static final int APDU_GETBUFFER = 0;
    private static final int APDU_SETOUTGOING_AND_SEND = 8;
    private static final int ISOEXCEPTION_THROWIT = 1;
    private static final int UTIL_ARRAYCOPY = 0;

    /* constant pool */
    private static final int CP_CLASS = 0;
    private static final int CP_APPLET_INIT = 1;
    private static final int CP_OWN_INIT = 2;
    private static final int CP_REGISTER = 3;
    private static final int CP_SELECTING = 4;
    private static final int CP_GETBUFFER = 5;
    private static final int CP_SETOUTSEND = 6;
    private static final int CP_THROWIT = 7;
    private static final int CP_ARRAYCOPY = 8;
    private static final int CP_F_PRIV = 9;
    private static final int CP_F_AID = 10;
    private static final int CP_F_PARAMS = 11;
    private static final int CP_SEND = 12;
    private static final int CP_COUNT = 13;

    /* opcodes */
    private static final int SCONST_0 = 3;
    private static final int SCONST_1 = 4;
    private static final int SCONST_2 = 5;
    private static final int BSPUSH = 16;
    private static final int SSPUSH = 17;
    private static final int SLOAD = 22;
    private static final int ALOAD_0 = 24;
    private static final int ALOAD_1 = 25;
    private static final int ALOAD_2 = 26;
    private static final int SLOAD_2 = 30;
    private static final int SLOAD_3 = 31;
    private static final int BALOAD = 37;
    private static final int SSTORE = 41;
    private static final int ASTORE_2 = 45;
    private static final int SSTORE_3 = 50;
    private static final int BASTORE = 56;
    private static final int POP = 59;
    private static final int DUP = 61;
    private static final int SADD = 65;
    private static final int SINC = 89;
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
    private static final int ARRAYLENGTH = 146;
    private static final int IFEQ_W = 152;
    private static final int IFLE_W = 157;
    private static final int IF_SCMPNE_W = 163;

    private static final int T_BYTE = 11;

    /* locals inside the constructor */
    private static final int LOCAL_I = 4;
    private static final int LOCAL_LEN = 5;

    /* ================================================================== */

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "params.cap");
        writeCap(out);
        System.out.println("wrote " + out.getPath());
        System.out.println("  package AID " + Hex.toHex(PACKAGE_AID));
        System.out.println("  applet AID  " + Hex.toHex(APPLET_AID));
    }

    /* ---------------- methods ---------------- */

    private static byte[] installCode() {
        Asm a = new Asm();
        a.op2(NEW, CP_CLASS);
        a.op(DUP);
        a.op(ALOAD_0);          // bArray
        a.op(SLOAD_1());        // bOffset
        a.op(SLOAD_2);          // bLength
        a.op2(INVOKESPECIAL, CP_OWN_INIT);
        a.op(POP);
        a.op(RETURN);
        return a.toBytes();
    }

    private static int SLOAD_1() {
        return 29;              // sload_1
    }

    private static byte[] initCode() {
        Asm a = new Asm();
        a.op(ALOAD_0);
        a.op2(INVOKESPECIAL, CP_APPLET_INIT);

        // i = bOffset
        a.op(SLOAD_2);
        a.op1(SSTORE, LOCAL_I);

        /* ---- instance AID ---- */
        readLengthByte(a);
        // instanceAid = new byte[len]
        a.op(ALOAD_0);
        a.op1(SLOAD, LOCAL_LEN);
        a.op1(NEWARRAY, T_BYTE);
        a.op1(PUTFIELD_A, CP_F_AID);
        copyInto(a, CP_F_AID);
        advance(a);

        /* ---- privileges ---- */
        readLengthByte(a);
        a.op1(SLOAD, LOCAL_LEN);
        a.branch(IFLE_W, "noPriv");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op1(SLOAD, LOCAL_I);
        a.op(BALOAD);
        a.op1(PUTFIELD_S, CP_F_PRIV);
        a.label("noPriv");
        advance(a);

        /* ---- application parameters, the C9 value ---- */
        readLengthByte(a);
        a.op(ALOAD_0);
        a.op1(SLOAD, LOCAL_LEN);
        a.op1(NEWARRAY, T_BYTE);
        a.op1(PUTFIELD_A, CP_F_PARAMS);
        copyInto(a, CP_F_PARAMS);

        a.op(ALOAD_0);
        a.op2(INVOKEVIRTUAL, CP_REGISTER);
        a.op(RETURN);
        return a.toBytes();
    }

    /** len = bArray[i]; i++ */
    private static void readLengthByte(Asm a) {
        a.op(ALOAD_1);
        a.op1(SLOAD, LOCAL_I);
        a.op(BALOAD);
        a.op1(SSTORE, LOCAL_LEN);
        a.op1(SINC, LOCAL_I).u1(1);
    }

    /** Util.arrayCopy(bArray, i, field, 0, len) */
    private static void copyInto(Asm a, int fieldCp) {
        a.op(ALOAD_1);
        a.op1(SLOAD, LOCAL_I);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, fieldCp);
        a.op(SCONST_0);
        a.op1(SLOAD, LOCAL_LEN);
        a.op2(INVOKESTATIC, CP_ARRAYCOPY);
        a.op(POP);
    }

    /** i += len */
    private static void advance(Asm a) {
        a.op1(SLOAD, LOCAL_I);
        a.op1(SLOAD, LOCAL_LEN);
        a.op(SADD);
        a.op1(SSTORE, LOCAL_I);
    }

    private static byte[] processCode() {
        Asm a = new Asm();
        a.op(ALOAD_0);
        a.op2(INVOKEVIRTUAL, CP_SELECTING);
        a.branch(IFEQ_W, "go");
        a.op(RETURN);

        a.label("go");
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(ASTORE_2);
        a.op(ALOAD_2);
        a.op(SCONST_1);
        a.op(BALOAD);
        a.op(SSTORE_3);

        // 0x10 -> the C9 application parameters
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x10);
        a.branch(IF_SCMPNE_W, "case20");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_F_PARAMS);
        a.op2(INVOKESPECIAL, CP_SEND);
        a.op(RETURN);

        // 0x20 -> the instance AID
        a.label("case20");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x20);
        a.branch(IF_SCMPNE_W, "case30");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_F_AID);
        a.op2(INVOKESPECIAL, CP_SEND);
        a.op(RETURN);

        // 0x30 -> the privileges byte
        a.label("case30");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x30);
        a.branch(IF_SCMPNE_W, "case40");
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_F_PRIV);
        a.op(BASTORE);
        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(SCONST_1);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);

        // 0x40 -> lengths of the AID and the parameters
        a.label("case40");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x40);
        a.branch(IF_SCMPNE_W, "unknown");
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_F_AID);
        a.op(ARRAYLENGTH);
        a.op(BASTORE);
        a.op(ALOAD_2);
        a.op(SCONST_1);
        a.op(ALOAD_0);
        a.op1(GETFIELD_A, CP_F_PARAMS);
        a.op(ARRAYLENGTH);
        a.op(BASTORE);
        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(SCONST_2);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);

        a.label("unknown");
        a.op2(SSPUSH, 0x6D00);
        a.op2(INVOKESTATIC, CP_THROWIT);
        a.op(RETURN);
        return a.toBytes();
    }

    /** send(APDU apdu, byte[] data) */
    private static byte[] sendCode() {
        Asm a = new Asm();
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(SCONST_0);
        a.op(ALOAD_2);
        a.op(ARRAYLENGTH);
        a.op2(INVOKESTATIC, CP_ARRAYCOPY);
        a.op(POP);
        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(ALOAD_2);
        a.op(ARRAYLENGTH);
        a.op2(INVOKEVIRTUAL, CP_SETOUTSEND);
        a.op(RETURN);
        return a.toBytes();
    }

    /* ---------------- assembly ---------------- */

    public static byte[] buildComponentStream() throws IOException {
        byte[] install = installCode();
        byte[] init = initCode();
        byte[] process = processCode();
        byte[] send = sendCode();

        int offInstall = 1;
        int offInit = offInstall + 2 + install.length;
        int offProcess = offInit + 2 + init.length;
        int offSend = offProcess + 2 + process.length;

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(0);
        writeMethod(m, 5, 3, 0, install);
        writeMethod(m, 6, 4, 2, init);
        writeMethod(m, 5, 2, 2, process);
        writeMethod(m, 6, 3, 0, send);
        byte[] methodInfo = m.toByteArray();

        byte[] headerInfo = header();
        byte[] appletInfo = applet(offInstall);
        byte[] importInfo = imports();
        byte[] cpInfo = constantPool(offInit, offSend);
        byte[] classInfo = classes(offProcess);
        byte[] staticInfo = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] refLocInfo = new byte[]{0, 0, 0, 0};
        byte[] exportInfo = new byte[]{0};

        int[] sizes = new int[11];
        sizes[0] = headerInfo.length;
        sizes[2] = appletInfo.length;
        sizes[3] = importInfo.length;
        sizes[4] = cpInfo.length;
        sizes[5] = classInfo.length;
        sizes[6] = methodInfo.length;
        sizes[7] = staticInfo.length;
        sizes[8] = refLocInfo.length;
        sizes[9] = exportInfo.length;
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

    /* ---------------- components ---------------- */

    private static void writeMethod(ByteArrayOutputStream out, int maxStack,
            int nargs, int maxLocals, byte[] code) {
        out.write(maxStack & 0x0F);
        out.write(((nargs & 0x0F) << 4) | (maxLocals & 0x0F));
        out.write(code, 0, code.length);
    }

    private static byte[] header() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xDE);
        o.write(0xCA);
        o.write(0xFF);
        o.write(0xED);
        o.write(1);
        o.write(2);
        o.write(0);
        o.write(0);
        o.write(1);
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
        for (int i = 0; i < 6; i++) {
            o.write(0);
        }
        o.write(1);
        o.write(1);
        o.write(0);
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
        o.write(3);
        o.write(1);
        o.write(FRAMEWORK_AID.length);
        o.write(FRAMEWORK_AID, 0, FRAMEWORK_AID.length);
        return o.toByteArray();
    }

    private static byte[] constantPool(int initOff, int sendOff) {
        byte[][] cp = new byte[CP_COUNT][];
        cp[CP_CLASS] = new byte[]{1, 0, 0, 0};
        cp[CP_APPLET_INIT] = ext(6, CLS_APPLET, APPLET_INIT);
        cp[CP_OWN_INIT] = internal(initOff);
        cp[CP_REGISTER] = ext(3, CLS_APPLET, APPLET_REGISTER);
        cp[CP_SELECTING] = ext(3, CLS_APPLET, APPLET_SELECTING);
        cp[CP_GETBUFFER] = ext(3, CLS_APDU, APDU_GETBUFFER);
        cp[CP_SETOUTSEND] = ext(3, CLS_APDU, APDU_SETOUTGOING_AND_SEND);
        cp[CP_THROWIT] = ext(6, CLS_ISOEXCEPTION, ISOEXCEPTION_THROWIT);
        cp[CP_ARRAYCOPY] = ext(6, CLS_UTIL, UTIL_ARRAYCOPY);
        cp[CP_F_PRIV] = new byte[]{2, 0, 0, 0};
        cp[CP_F_AID] = new byte[]{2, 0, 0, 1};
        cp[CP_F_PARAMS] = new byte[]{2, 0, 0, 2};
        cp[CP_SEND] = internal(sendOff);

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((CP_COUNT >> 8) & 0xFF);
        o.write(CP_COUNT & 0xFF);
        for (int i = 0; i < CP_COUNT; i++) {
            o.write(cp[i], 0, 4);
        }
        return o.toByteArray();
    }

    private static byte[] ext(int tag, int classToken, int token) {
        return new byte[]{(byte) tag, (byte) 0x80, (byte) classToken, (byte) token};
    }

    private static byte[] internal(int offset) {
        return new byte[]{6, 0, (byte) (offset >> 8), (byte) offset};
    }

    private static byte[] classes(int processOffset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00);
        o.write(0x80);
        o.write(CLS_APPLET);
        o.write(3);           // declared_instance_size: priv, aid, params
        o.write(1);           // first_reference_token
        o.write(2);           // reference_count
        o.write(1);           // public_method_table_base
        o.write(1);           // public_method_table_count
        o.write(0);
        o.write(0);
        o.write((processOffset >> 8) & 0xFF);
        o.write(processOffset & 0xFF);
        return o.toByteArray();
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
