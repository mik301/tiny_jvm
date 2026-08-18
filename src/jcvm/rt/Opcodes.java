package jcvm.rt;

import jcvm.util.ByteReader;

/** Java Card bytecode constants, mnemonics and instruction sizing. */
public final class Opcodes {

    public static final int NOP = 0;
    public static final int ACONST_NULL = 1;
    public static final int SCONST_M1 = 2;
    public static final int SCONST_0 = 3;
    public static final int SCONST_5 = 8;
    public static final int ICONST_M1 = 9;
    public static final int ICONST_0 = 10;
    public static final int ICONST_5 = 15;
    public static final int BSPUSH = 16;
    public static final int SSPUSH = 17;
    public static final int BIPUSH = 18;
    public static final int SIPUSH = 19;
    public static final int IIPUSH = 20;
    public static final int ALOAD = 21;
    public static final int SLOAD = 22;
    public static final int ILOAD = 23;
    public static final int ALOAD_0 = 24;
    public static final int SLOAD_0 = 28;
    public static final int ILOAD_0 = 32;
    public static final int AALOAD = 36;
    public static final int BALOAD = 37;
    public static final int SALOAD = 38;
    public static final int IALOAD = 39;
    public static final int ASTORE = 40;
    public static final int SSTORE = 41;
    public static final int ISTORE = 42;
    public static final int ASTORE_0 = 43;
    public static final int SSTORE_0 = 47;
    public static final int ISTORE_0 = 51;
    public static final int AASTORE = 55;
    public static final int BASTORE = 56;
    public static final int SASTORE = 57;
    public static final int IASTORE = 58;
    public static final int POP = 59;
    public static final int POP2 = 60;
    public static final int DUP = 61;
    public static final int DUP2 = 62;
    public static final int DUP_X = 63;
    public static final int SWAP_X = 64;
    public static final int SADD = 65;
    public static final int IADD = 66;
    public static final int SSUB = 67;
    public static final int ISUB = 68;
    public static final int SMUL = 69;
    public static final int IMUL = 70;
    public static final int SDIV = 71;
    public static final int IDIV = 72;
    public static final int SREM = 73;
    public static final int IREM = 74;
    public static final int SNEG = 75;
    public static final int INEG = 76;
    public static final int SSHL = 77;
    public static final int ISHL = 78;
    public static final int SSHR = 79;
    public static final int ISHR = 80;
    public static final int SUSHR = 81;
    public static final int IUSHR = 82;
    public static final int SAND = 83;
    public static final int IAND = 84;
    public static final int SOR = 85;
    public static final int IOR = 86;
    public static final int SXOR = 87;
    public static final int IXOR = 88;
    public static final int SINC = 89;
    public static final int IINC = 90;
    public static final int S2B = 91;
    public static final int S2I = 92;
    public static final int I2B = 93;
    public static final int I2S = 94;
    public static final int ICMP = 95;
    public static final int IFEQ = 96;
    public static final int IFNE = 97;
    public static final int IFLT = 98;
    public static final int IFGE = 99;
    public static final int IFGT = 100;
    public static final int IFLE = 101;
    public static final int IFNULL = 102;
    public static final int IFNONNULL = 103;
    public static final int IF_ACMPEQ = 104;
    public static final int IF_ACMPNE = 105;
    public static final int IF_SCMPEQ = 106;
    public static final int IF_SCMPNE = 107;
    public static final int IF_SCMPLT = 108;
    public static final int IF_SCMPGE = 109;
    public static final int IF_SCMPGT = 110;
    public static final int IF_SCMPLE = 111;
    public static final int GOTO = 112;
    public static final int JSR = 113;
    public static final int RET = 114;
    public static final int STABLESWITCH = 115;
    public static final int ITABLESWITCH = 116;
    public static final int SLOOKUPSWITCH = 117;
    public static final int ILOOKUPSWITCH = 118;
    public static final int ARETURN = 119;
    public static final int SRETURN = 120;
    public static final int IRETURN = 121;
    public static final int RETURN = 122;
    public static final int GETSTATIC_A = 123;
    public static final int GETSTATIC_B = 124;
    public static final int GETSTATIC_S = 125;
    public static final int GETSTATIC_I = 126;
    public static final int PUTSTATIC_A = 127;
    public static final int PUTSTATIC_B = 128;
    public static final int PUTSTATIC_S = 129;
    public static final int PUTSTATIC_I = 130;
    public static final int GETFIELD_A = 131;
    public static final int GETFIELD_B = 132;
    public static final int GETFIELD_S = 133;
    public static final int GETFIELD_I = 134;
    public static final int PUTFIELD_A = 135;
    public static final int PUTFIELD_B = 136;
    public static final int PUTFIELD_S = 137;
    public static final int PUTFIELD_I = 138;
    public static final int INVOKEVIRTUAL = 139;
    public static final int INVOKESPECIAL = 140;
    public static final int INVOKESTATIC = 141;
    public static final int INVOKEINTERFACE = 142;
    public static final int NEW = 143;
    public static final int NEWARRAY = 144;
    public static final int ANEWARRAY = 145;
    public static final int ARRAYLENGTH = 146;
    public static final int ATHROW = 147;
    public static final int CHECKCAST = 148;
    public static final int INSTANCEOF = 149;
    public static final int SINC_W = 150;
    public static final int IINC_W = 151;
    public static final int IFEQ_W = 152;
    public static final int IFNE_W = 153;
    public static final int IFLT_W = 154;
    public static final int IFGE_W = 155;
    public static final int IFGT_W = 156;
    public static final int IFLE_W = 157;
    public static final int IFNULL_W = 158;
    public static final int IFNONNULL_W = 159;
    public static final int IF_ACMPEQ_W = 160;
    public static final int IF_ACMPNE_W = 161;
    public static final int IF_SCMPEQ_W = 162;
    public static final int IF_SCMPNE_W = 163;
    public static final int IF_SCMPLT_W = 164;
    public static final int IF_SCMPGE_W = 165;
    public static final int IF_SCMPGT_W = 166;
    public static final int IF_SCMPLE_W = 167;
    public static final int GOTO_W = 168;
    public static final int GETFIELD_A_W = 169;
    public static final int GETFIELD_B_W = 170;
    public static final int GETFIELD_S_W = 171;
    public static final int GETFIELD_I_W = 172;
    public static final int GETFIELD_A_THIS = 173;
    public static final int GETFIELD_B_THIS = 174;
    public static final int GETFIELD_S_THIS = 175;
    public static final int GETFIELD_I_THIS = 176;
    public static final int PUTFIELD_A_W = 177;
    public static final int PUTFIELD_B_W = 178;
    public static final int PUTFIELD_S_W = 179;
    public static final int PUTFIELD_I_W = 180;
    public static final int PUTFIELD_A_THIS = 181;
    public static final int PUTFIELD_B_THIS = 182;
    public static final int PUTFIELD_S_THIS = 183;
    public static final int PUTFIELD_I_THIS = 184;
    public static final int IMPDEP1 = 185;
    public static final int IMPDEP2 = 186;

    private static final String[] NAME = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            NAME[i] = "unknown_" + i;
        }
        n(0, "nop");
        n(1, "aconst_null");
        n(2, "sconst_m1");
        for (int i = 0; i <= 5; i++) {
            n(3 + i, "sconst_" + i);
        }
        n(9, "iconst_m1");
        for (int i = 0; i <= 5; i++) {
            n(10 + i, "iconst_" + i);
        }
        n(16, "bspush");
        n(17, "sspush");
        n(18, "bipush");
        n(19, "sipush");
        n(20, "iipush");
        n(21, "aload");
        n(22, "sload");
        n(23, "iload");
        for (int i = 0; i < 4; i++) {
            n(24 + i, "aload_" + i);
            n(28 + i, "sload_" + i);
            n(32 + i, "iload_" + i);
            n(43 + i, "astore_" + i);
            n(47 + i, "sstore_" + i);
            n(51 + i, "istore_" + i);
        }
        n(36, "aaload");
        n(37, "baload");
        n(38, "saload");
        n(39, "iaload");
        n(40, "astore");
        n(41, "sstore");
        n(42, "istore");
        n(55, "aastore");
        n(56, "bastore");
        n(57, "sastore");
        n(58, "iastore");
        n(59, "pop");
        n(60, "pop2");
        n(61, "dup");
        n(62, "dup2");
        n(63, "dup_x");
        n(64, "swap_x");
        n(65, "sadd");
        n(66, "iadd");
        n(67, "ssub");
        n(68, "isub");
        n(69, "smul");
        n(70, "imul");
        n(71, "sdiv");
        n(72, "idiv");
        n(73, "srem");
        n(74, "irem");
        n(75, "sneg");
        n(76, "ineg");
        n(77, "sshl");
        n(78, "ishl");
        n(79, "sshr");
        n(80, "ishr");
        n(81, "sushr");
        n(82, "iushr");
        n(83, "sand");
        n(84, "iand");
        n(85, "sor");
        n(86, "ior");
        n(87, "sxor");
        n(88, "ixor");
        n(89, "sinc");
        n(90, "iinc");
        n(91, "s2b");
        n(92, "s2i");
        n(93, "i2b");
        n(94, "i2s");
        n(95, "icmp");
        n(96, "ifeq");
        n(97, "ifne");
        n(98, "iflt");
        n(99, "ifge");
        n(100, "ifgt");
        n(101, "ifle");
        n(102, "ifnull");
        n(103, "ifnonnull");
        n(104, "if_acmpeq");
        n(105, "if_acmpne");
        n(106, "if_scmpeq");
        n(107, "if_scmpne");
        n(108, "if_scmplt");
        n(109, "if_scmpge");
        n(110, "if_scmpgt");
        n(111, "if_scmple");
        n(112, "goto");
        n(113, "jsr");
        n(114, "ret");
        n(115, "stableswitch");
        n(116, "itableswitch");
        n(117, "slookupswitch");
        n(118, "ilookupswitch");
        n(119, "areturn");
        n(120, "sreturn");
        n(121, "ireturn");
        n(122, "return");
        n(123, "getstatic_a");
        n(124, "getstatic_b");
        n(125, "getstatic_s");
        n(126, "getstatic_i");
        n(127, "putstatic_a");
        n(128, "putstatic_b");
        n(129, "putstatic_s");
        n(130, "putstatic_i");
        n(131, "getfield_a");
        n(132, "getfield_b");
        n(133, "getfield_s");
        n(134, "getfield_i");
        n(135, "putfield_a");
        n(136, "putfield_b");
        n(137, "putfield_s");
        n(138, "putfield_i");
        n(139, "invokevirtual");
        n(140, "invokespecial");
        n(141, "invokestatic");
        n(142, "invokeinterface");
        n(143, "new");
        n(144, "newarray");
        n(145, "anewarray");
        n(146, "arraylength");
        n(147, "athrow");
        n(148, "checkcast");
        n(149, "instanceof");
        n(150, "sinc_w");
        n(151, "iinc_w");
        n(152, "ifeq_w");
        n(153, "ifne_w");
        n(154, "iflt_w");
        n(155, "ifge_w");
        n(156, "ifgt_w");
        n(157, "ifle_w");
        n(158, "ifnull_w");
        n(159, "ifnonnull_w");
        n(160, "if_acmpeq_w");
        n(161, "if_acmpne_w");
        n(162, "if_scmpeq_w");
        n(163, "if_scmpne_w");
        n(164, "if_scmplt_w");
        n(165, "if_scmpge_w");
        n(166, "if_scmpgt_w");
        n(167, "if_scmple_w");
        n(168, "goto_w");
        n(169, "getfield_a_w");
        n(170, "getfield_b_w");
        n(171, "getfield_s_w");
        n(172, "getfield_i_w");
        n(173, "getfield_a_this");
        n(174, "getfield_b_this");
        n(175, "getfield_s_this");
        n(176, "getfield_i_this");
        n(177, "putfield_a_w");
        n(178, "putfield_b_w");
        n(179, "putfield_s_w");
        n(180, "putfield_i_w");
        n(181, "putfield_a_this");
        n(182, "putfield_b_this");
        n(183, "putfield_s_this");
        n(184, "putfield_i_this");
        n(185, "impdep1");
        n(186, "impdep2");
    }

    private static void n(int op, String name) {
        NAME[op] = name;
    }

    private Opcodes() {
    }

    public static String name(int op) {
        return NAME[op & 0xFF];
    }

    /** Total size in bytes of the instruction starting at {@code pc}. */
    public static int length(byte[] code, int pc) {
        int op = code[pc] & 0xFF;
        switch (op) {
            case STABLESWITCH: {
                int low = ByteReader.s2(code, pc + 3);
                int high = ByteReader.s2(code, pc + 5);
                return 7 + 2 * (high - low + 1);
            }
            case ITABLESWITCH: {
                int low = ByteReader.s4(code, pc + 3);
                int high = ByteReader.s4(code, pc + 7);
                return 11 + 2 * (high - low + 1);
            }
            case SLOOKUPSWITCH: {
                int n = ByteReader.u2(code, pc + 3);
                return 5 + 4 * n;
            }
            case ILOOKUPSWITCH: {
                int n = ByteReader.u2(code, pc + 3);
                return 5 + 6 * n;
            }
            default:
                return SIZE[op];
        }
    }

    private static final int[] SIZE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            SIZE[i] = 1;
        }
        SIZE[BSPUSH] = 2;
        SIZE[SSPUSH] = 3;
        SIZE[BIPUSH] = 2;
        SIZE[SIPUSH] = 3;
        SIZE[IIPUSH] = 5;
        SIZE[ALOAD] = 2;
        SIZE[SLOAD] = 2;
        SIZE[ILOAD] = 2;
        SIZE[ASTORE] = 2;
        SIZE[SSTORE] = 2;
        SIZE[ISTORE] = 2;
        SIZE[DUP_X] = 2;
        SIZE[SWAP_X] = 2;
        SIZE[SINC] = 3;
        SIZE[IINC] = 3;
        for (int i = IFEQ; i <= GOTO; i++) {
            SIZE[i] = 2;
        }
        SIZE[JSR] = 3;
        SIZE[RET] = 2;
        for (int i = GETSTATIC_A; i <= PUTSTATIC_I; i++) {
            SIZE[i] = 3;
        }
        for (int i = GETFIELD_A; i <= PUTFIELD_I; i++) {
            SIZE[i] = 2;
        }
        SIZE[INVOKEVIRTUAL] = 3;
        SIZE[INVOKESPECIAL] = 3;
        SIZE[INVOKESTATIC] = 3;
        SIZE[INVOKEINTERFACE] = 5;
        SIZE[NEW] = 3;
        SIZE[NEWARRAY] = 2;
        SIZE[ANEWARRAY] = 3;
        SIZE[CHECKCAST] = 4;
        SIZE[INSTANCEOF] = 4;
        SIZE[SINC_W] = 4;
        SIZE[IINC_W] = 4;
        for (int i = IFEQ_W; i <= GOTO_W; i++) {
            SIZE[i] = 3;
        }
        for (int i = GETFIELD_A_W; i <= GETFIELD_I_W; i++) {
            SIZE[i] = 3;
        }
        for (int i = GETFIELD_A_THIS; i <= GETFIELD_I_THIS; i++) {
            SIZE[i] = 2;
        }
        for (int i = PUTFIELD_A_W; i <= PUTFIELD_I_W; i++) {
            SIZE[i] = 3;
        }
        for (int i = PUTFIELD_A_THIS; i <= PUTFIELD_I_THIS; i++) {
            SIZE[i] = 2;
        }
    }
}
