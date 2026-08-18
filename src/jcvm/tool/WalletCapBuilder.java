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
 * Builds a CAP for the classic Wallet applet without needing the Java Card SDK
 * converter. The bytecode below is a faithful hand assembly of:
 *
 * <pre>
 * package com.example.wallet;
 * import javacard.framework.*;
 *
 * public class Wallet extends Applet {
 *     private static final byte WALLET_CLA   = (byte) 0xB0;
 *     private static final byte INS_CREDIT   = (byte) 0x30;
 *     private static final byte INS_DEBIT    = (byte) 0x40;
 *     private static final byte INS_BALANCE  = (byte) 0x50;
 *     private static final short MAX_BALANCE = 10000;
 *
 *     private short balance;                          // field token 0
 *
 *     private Wallet() { balance = 0; register(); }
 *
 *     public static void install(byte[] b, short o, byte l) { new Wallet(); }
 *
 *     public void process(APDU apdu) {
 *         if (selectingApplet()) return;
 *         byte[] buffer = apdu.getBuffer();
 *         if (buffer[ISO7816.OFFSET_CLA] != WALLET_CLA)
 *             ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
 *         switch (buffer[ISO7816.OFFSET_INS]) {
 *             case INS_CREDIT:  credit(apdu);     break;
 *             case INS_DEBIT:   debit(apdu);      break;
 *             case INS_BALANCE: getBalance(apdu); break;
 *             default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
 *         }
 *     }
 *
 *     private void credit(APDU apdu) { ... }
 *     private void debit(APDU apdu) { ... }
 *     private void getBalance(APDU apdu) { ... }
 * }
 * </pre>
 *
 * The private methods are reached with invokespecial through an internal
 * CONSTANT_StaticMethodref, exactly as a converter would emit them.
 */
public final class WalletCapBuilder {

    public static final byte[] PACKAGE_AID = Hex.parse("A00000010203");
    public static final byte[] APPLET_AID = Hex.parse("A0000001020304");
    public static final byte[] FRAMEWORK_AID = Hex.parse("A0000000620101");
    private static final String PACKAGE_PATH = "com/example/wallet";

    /* API tokens, must agree with res/api-tokens.txt */
    private static final int CLS_APDU = 1;
    private static final int CLS_APPLET = 3;
    private static final int CLS_ISOEXCEPTION = 8;
    private static final int CLS_UTIL = 15;

    private static final int APPLET_INIT = 0;
    private static final int APPLET_REGISTER = 4;
    private static final int APPLET_SELECTING = 6;
    private static final int APDU_GETBUFFER = 0;
    private static final int APDU_SETINCOMING = 1;
    private static final int APDU_SETOUTGOING = 2;
    private static final int APDU_SETOUTGOING_LENGTH = 4;
    private static final int APDU_SENDBYTES = 6;
    private static final int ISOEXCEPTION_THROWIT = 1;
    private static final int UTIL_GETSHORT = 5;
    private static final int UTIL_SETSHORT = 6;

    /* constant pool layout */
    private static final int CP_CLASS_WALLET = 0;
    private static final int CP_APPLET_INIT = 1;
    private static final int CP_WALLET_INIT = 2;
    private static final int CP_REGISTER = 3;
    private static final int CP_SELECTING = 4;
    private static final int CP_GETBUFFER = 5;
    private static final int CP_SETINCOMING = 6;
    private static final int CP_SETOUTGOING = 7;
    private static final int CP_SETOUTGOING_LENGTH = 8;
    private static final int CP_SENDBYTES = 9;
    private static final int CP_THROWIT = 10;
    private static final int CP_GETSHORT = 11;
    private static final int CP_SETSHORT = 12;
    private static final int CP_FIELD_BALANCE = 13;
    private static final int CP_CREDIT = 14;
    private static final int CP_DEBIT = 15;
    private static final int CP_GETBALANCE = 16;
    private static final int CP_COUNT = 17;

    /* opcodes */
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
    private static final int POP = 59;
    private static final int DUP = 61;
    private static final int SADD = 65;
    private static final int SSUB = 67;
    private static final int RETURN = 122;
    private static final int GETFIELD_S = 133;
    private static final int PUTFIELD_S = 137;
    private static final int INVOKEVIRTUAL = 139;
    private static final int INVOKESPECIAL = 140;
    private static final int INVOKESTATIC = 141;
    private static final int NEW = 143;
    private static final int IFEQ_W = 152;
    private static final int IFGT_W = 156;
    private static final int IF_SCMPEQ_W = 162;
    private static final int IF_SCMPNE_W = 163;
    private static final int IF_SCMPLE_W = 167;

    /* ISO 7816 constants, inlined by a converter exactly like this */
    private static final int OFFSET_CLA = 0;
    private static final int OFFSET_INS = 1;
    private static final int OFFSET_CDATA = 5;
    private static final int WALLET_CLA = 0xB0;
    private static final int MAX_BALANCE = 10000;
    private static final int SW_NO_ERROR = 0x9000;
    private static final int SW_WRONG_DATA = 0x6A80;
    private static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    private static final int SW_CLA_NOT_SUPPORTED = 0x6E00;
    private static final int SW_OVER_MAX = 0x6A84;
    private static final int SW_INSUFFICIENT = 0x6A85;

    /* ================================================================== */

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "wallet.cap");
        writeCap(out);
        System.out.println("wrote " + out.getPath());
        System.out.println("  package AID " + Hex.toHex(PACKAGE_AID));
        System.out.println("  applet AID  " + Hex.toHex(APPLET_AID));
    }

    /* ---------------- methods ---------------- */

    private static byte[] installCode() {
        Asm a = new Asm();
        a.op2(NEW, CP_CLASS_WALLET);
        a.op(DUP);
        a.op2(INVOKESPECIAL, CP_WALLET_INIT);
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
        a.op1(PUTFIELD_S, CP_FIELD_BALANCE);
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

        // if (buffer[OFFSET_CLA] != WALLET_CLA) ISOException.throwIt(0x6E00);
        a.op(ALOAD_2);
        a.op1(BSPUSH, OFFSET_CLA);
        a.op(BALOAD);
        a.op1(BSPUSH, WALLET_CLA);
        a.branch(IF_SCMPEQ_W, "claOk");
        a.op2(SSPUSH, SW_CLA_NOT_SUPPORTED);
        a.op2(INVOKESTATIC, CP_THROWIT);

        a.label("claOk");
        a.op(ALOAD_2);
        a.op1(BSPUSH, OFFSET_INS);
        a.op(BALOAD);
        a.op(SSTORE_3);

        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x30);
        a.branch(IF_SCMPNE_W, "tryDebit");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op2(INVOKESPECIAL, CP_CREDIT);
        a.op(RETURN);

        a.label("tryDebit");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x40);
        a.branch(IF_SCMPNE_W, "tryBalance");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op2(INVOKESPECIAL, CP_DEBIT);
        a.op(RETURN);

        a.label("tryBalance");
        a.op(SLOAD_3);
        a.op1(BSPUSH, 0x50);
        a.branch(IF_SCMPNE_W, "unknownIns");
        a.op(ALOAD_0);
        a.op(ALOAD_1);
        a.op2(INVOKESPECIAL, CP_GETBALANCE);
        a.op(RETURN);

        a.label("unknownIns");
        a.op2(SSPUSH, SW_INS_NOT_SUPPORTED);
        a.op2(INVOKESTATIC, CP_THROWIT);
        a.op(RETURN);
        return a.toBytes();
    }

    private static byte[] creditCode() {
        Asm a = new Asm();
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(ASTORE_2);
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_SETINCOMING);
        a.op(POP);

        // short amount = Util.getShort(buffer, OFFSET_CDATA);
        a.op(ALOAD_2);
        a.op1(BSPUSH, OFFSET_CDATA);
        a.op2(INVOKESTATIC, CP_GETSHORT);
        a.op(SSTORE_3);

        // if (amount <= 0) throwIt(SW_WRONG_DATA);
        a.op(SLOAD_3);
        a.branch(IFGT_W, "amountOk");
        a.op2(SSPUSH, SW_WRONG_DATA);
        a.op2(INVOKESTATIC, CP_THROWIT);

        // if ((short)(balance + amount) > MAX_BALANCE) throwIt(0x6A84);
        a.label("amountOk");
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_BALANCE);
        a.op(SLOAD_3);
        a.op(SADD);
        a.op2(SSPUSH, MAX_BALANCE);
        a.branch(IF_SCMPLE_W, "withinMax");
        a.op2(SSPUSH, SW_OVER_MAX);
        a.op2(INVOKESTATIC, CP_THROWIT);

        // balance += amount;
        a.label("withinMax");
        a.op(ALOAD_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_BALANCE);
        a.op(SLOAD_3);
        a.op(SADD);
        a.op1(PUTFIELD_S, CP_FIELD_BALANCE);

        a.op2(SSPUSH, SW_NO_ERROR);
        a.op2(INVOKESTATIC, CP_THROWIT);
        a.op(RETURN);
        return a.toBytes();
    }

    private static byte[] debitCode() {
        Asm a = new Asm();
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(ASTORE_2);
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_SETINCOMING);
        a.op(POP);

        a.op(ALOAD_2);
        a.op1(BSPUSH, OFFSET_CDATA);
        a.op2(INVOKESTATIC, CP_GETSHORT);
        a.op(SSTORE_3);

        a.op(SLOAD_3);
        a.branch(IFGT_W, "amountOk");
        a.op2(SSPUSH, SW_WRONG_DATA);
        a.op2(INVOKESTATIC, CP_THROWIT);

        // if (amount > balance) throwIt(0x6A85);
        a.label("amountOk");
        a.op(SLOAD_3);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_BALANCE);
        a.branch(IF_SCMPLE_W, "enough");
        a.op2(SSPUSH, SW_INSUFFICIENT);
        a.op2(INVOKESTATIC, CP_THROWIT);

        // balance -= amount;
        a.label("enough");
        a.op(ALOAD_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_BALANCE);
        a.op(SLOAD_3);
        a.op(SSUB);
        a.op1(PUTFIELD_S, CP_FIELD_BALANCE);

        a.op2(SSPUSH, SW_NO_ERROR);
        a.op2(INVOKESTATIC, CP_THROWIT);
        a.op(RETURN);
        return a.toBytes();
    }

    private static byte[] getBalanceCode() {
        Asm a = new Asm();
        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_GETBUFFER);
        a.op(ASTORE_2);

        // Util.setShort(buffer, (short) 0, balance);
        a.op(ALOAD_2);
        a.op(SCONST_0);
        a.op(ALOAD_0);
        a.op1(GETFIELD_S, CP_FIELD_BALANCE);
        a.op2(INVOKESTATIC, CP_SETSHORT);
        a.op(POP);

        a.op(ALOAD_1);
        a.op2(INVOKEVIRTUAL, CP_SETOUTGOING);
        a.op(POP);

        a.op(ALOAD_1);
        a.op(SCONST_2);
        a.op2(INVOKEVIRTUAL, CP_SETOUTGOING_LENGTH);

        a.op(ALOAD_1);
        a.op(SCONST_0);
        a.op(SCONST_2);
        a.op2(INVOKEVIRTUAL, CP_SENDBYTES);
        a.op(RETURN);
        return a.toBytes();
    }

    /* ---------------- assembly ---------------- */

    public static byte[] buildComponentStream() throws IOException {
        byte[] install = installCode();
        byte[] init = initCode();
        byte[] process = processCode();
        byte[] credit = creditCode();
        byte[] debit = debitCode();
        byte[] getBalance = getBalanceCode();

        int offInstall = 1;
        int offInit = offInstall + 2 + install.length;
        int offProcess = offInit + 2 + init.length;
        int offCredit = offProcess + 2 + process.length;
        int offDebit = offCredit + 2 + credit.length;
        int offGetBalance = offDebit + 2 + debit.length;

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(0);                                     // handler_count
        writeMethod(m, 2, 3, 0, install);               // maxStack, nargs, maxLocals
        writeMethod(m, 2, 1, 0, init);
        writeMethod(m, 3, 2, 2, process);
        writeMethod(m, 3, 2, 2, credit);
        writeMethod(m, 3, 2, 2, debit);
        writeMethod(m, 4, 2, 1, getBalance);
        byte[] methodInfo = m.toByteArray();

        byte[] headerInfo = header();
        byte[] appletInfo = applet(offInstall);
        byte[] importInfo = imports();
        byte[] cpInfo = constantPool(offInit, offCredit, offDebit, offGetBalance);
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

    /* ---------------- component bodies ---------------- */

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
        o.write(1);   // CAP minor -> 2.1
        o.write(2);   // CAP major
        o.write(0);   // flags
        o.write(0);   // package minor
        o.write(1);   // package major
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
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(0);
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
        o.write(3);
        o.write(1);
        o.write(FRAMEWORK_AID.length);
        o.write(FRAMEWORK_AID, 0, FRAMEWORK_AID.length);
        return o.toByteArray();
    }

    private static byte[] constantPool(int initOff, int creditOff, int debitOff,
            int balanceOff) {
        byte[][] cp = new byte[CP_COUNT][];
        cp[CP_CLASS_WALLET] = new byte[]{1, 0, 0, 0};
        cp[CP_APPLET_INIT] = ext(6, CLS_APPLET, APPLET_INIT);
        cp[CP_WALLET_INIT] = internalMethod(initOff);
        cp[CP_REGISTER] = ext(3, CLS_APPLET, APPLET_REGISTER);
        cp[CP_SELECTING] = ext(3, CLS_APPLET, APPLET_SELECTING);
        cp[CP_GETBUFFER] = ext(3, CLS_APDU, APDU_GETBUFFER);
        cp[CP_SETINCOMING] = ext(3, CLS_APDU, APDU_SETINCOMING);
        cp[CP_SETOUTGOING] = ext(3, CLS_APDU, APDU_SETOUTGOING);
        cp[CP_SETOUTGOING_LENGTH] = ext(3, CLS_APDU, APDU_SETOUTGOING_LENGTH);
        cp[CP_SENDBYTES] = ext(3, CLS_APDU, APDU_SENDBYTES);
        cp[CP_THROWIT] = ext(6, CLS_ISOEXCEPTION, ISOEXCEPTION_THROWIT);
        cp[CP_GETSHORT] = ext(6, CLS_UTIL, UTIL_GETSHORT);
        cp[CP_SETSHORT] = ext(6, CLS_UTIL, UTIL_SETSHORT);
        cp[CP_FIELD_BALANCE] = new byte[]{2, 0, 0, 0};
        cp[CP_CREDIT] = internalMethod(creditOff);
        cp[CP_DEBIT] = internalMethod(debitOff);
        cp[CP_GETBALANCE] = internalMethod(balanceOff);

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

    private static byte[] internalMethod(int offset) {
        return new byte[]{6, 0, (byte) (offset >> 8), (byte) offset};
    }

    private static byte[] classes(int processOffset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00);        // flags 0, interface_count 0
        o.write(0x80);        // super external, package token 0
        o.write(CLS_APPLET);  // javacard.framework.Applet
        o.write(1);           // declared_instance_size: balance
        o.write(0);           // first_reference_token
        o.write(0);           // reference_count
        o.write(1);           // public_method_table_base (process is token 1)
        o.write(1);           // public_method_table_count
        o.write(0);           // package_method_table_base
        o.write(0);           // package_method_table_count
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
