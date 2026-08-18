package jcvm.rt;

import java.io.PrintStream;
import java.util.List;

import jcvm.api.ApiClass;
import jcvm.api.ApiPackage;
import jcvm.api.ApiRegistry;
import jcvm.api.Descriptor;
import jcvm.api.NativeArgs;
import jcvm.api.NativeImpl;
import jcvm.api.Natives;
import jcvm.cap.CapPackage;

/** The Java Card bytecode interpreter. */
public final class VM {

    public static final int RET_VOID = 0;
    public static final int RET_WORD = 1;
    public static final int RET_REF = 2;
    public static final int RET_INT = 3;

    /** Return address pushed by jsr and consumed by ret. */
    public static final class ReturnAddress {

        public final int pc;

        public ReturnAddress(int pc) {
            this.pc = pc;
        }
    }

    public final ApiRegistry api;
    public final Natives natives;
    /** Card runtime environment; natives reach the card state through this. */
    public Object card;

    public boolean trace;
    public PrintStream out = System.out;
    public int maxDepth = 192;

    private int depth;
    private int retKind = RET_VOID;
    private int retWord;
    private int retIntValue;
    private Object retObject;

    /** Journal used by JCSystem.beginTransaction / abortTransaction. */
    public final Transaction transaction = new Transaction();

    public VM(ApiRegistry api, Natives natives) {
        this.api = api;
        this.natives = natives;
    }

    /* ------------------------------------------------------------------ */
    /* return channel                                                      */
    /* ------------------------------------------------------------------ */

    public void retVoid() {
        retKind = RET_VOID;
    }

    public void retShort(int v) {
        retKind = RET_WORD;
        retWord = (short) v;
    }

    public void retBool(boolean b) {
        retShort(b ? 1 : 0);
    }

    public void retInt(int v) {
        retKind = RET_INT;
        retIntValue = v;
    }

    public void retRef(Object o) {
        retKind = RET_REF;
        retObject = o;
    }

    public int resultKind() {
        return retKind;
    }

    public int resultWord() {
        return retWord;
    }

    public int resultInt() {
        return retIntValue;
    }

    public Object resultRef() {
        return retObject;
    }

    /* ------------------------------------------------------------------ */
    /* public entry points                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Calls a method of a loaded package. {@code words} and {@code refs} hold
     * the argument area (including `this` for instance methods) and must be
     * exactly {@code m.nargs} words long.
     */
    public void call(MethodRt m, int[] words, Object[] refs) {
        Frame f = new Frame(m);
        for (int i = 0; i < m.nargs; i++) {
            f.lv[i] = words != null && i < words.length ? words[i] : 0;
            f.lr[i] = refs != null && i < refs.length ? refs[i] : null;
        }
        retKind = RET_VOID;
        execute(f);
    }

    /** Calls a virtual method on an applet-defined object by API token. */
    public void callVirtual(Object receiver, int token, int[] words, Object[] refs) {
        if (receiver == null) {
            throw JCThrow.nullPointer();
        }
        if (!(receiver instanceof JCObject)) {
            throw new JCThrow("java/lang/Error", "receiver is not an applet object: " + receiver);
        }
        ClassRt.VirtualTarget t = ((JCObject) receiver).clazz.lookupVirtual(token);
        if (t.isNative()) {
            throw new JCThrow("java/lang/Error",
                    "virtual token " + token + " resolves to native " + t.externalClass);
        }
        int n = t.method.nargs;
        int[] w = new int[n];
        Object[] r = new Object[n];
        r[0] = receiver;
        for (int i = 1; i < n; i++) {
            if (words != null && i - 1 < words.length) {
                w[i] = words[i - 1];
            }
            if (refs != null && i - 1 < refs.length) {
                r[i] = refs[i - 1];
            }
        }
        retKind = RET_VOID;
        call(t.method, w, r);
    }

    /* ------------------------------------------------------------------ */
    /* execution                                                           */
    /* ------------------------------------------------------------------ */

    private void execute(Frame f) {
        depth++;
        if (depth > maxDepth) {
            depth--;
            throw new JCThrow("java/lang/Error", "call depth exceeded " + maxDepth);
        }
        try {
            while (true) {
                try {
                    run(f);
                    return;
                } catch (JCThrow t) {
                    int handler = findHandler(f, t);
                    if (handler < 0) {
                        throw t;
                    }
                    for (int i = 0; i < f.sr.length; i++) {
                        f.sr[i] = null;
                    }
                    f.sp = 0;
                    f.pushRef(t.thrown != null ? t.thrown : new BuiltinObject(t.className));
                    f.pc = handler;
                }
            }
        } finally {
            depth--;
        }
    }

    private void run(Frame f) {
        final byte[] code = f.code;
        final LoadedPackage pkg = f.pkg;

        while (true) {
            int pc = f.pc;
            int op = code[pc] & 0xFF;
            int next = pc + Opcodes.length(code, pc);

            if (trace) {
                out.println("    " + pkg.name() + " @" + pc + "  " + Opcodes.name(op)
                        + "   sp=" + f.sp);
            }

            switch (op) {

                case Opcodes.NOP:
                    break;

                case Opcodes.ACONST_NULL:
                    f.pushRef(null);
                    break;

                case Opcodes.SCONST_M1:
                    f.pushShort(-1);
                    break;
                case 3: case 4: case 5: case 6: case 7: case 8:
                    f.pushShort(op - Opcodes.SCONST_0);
                    break;

                case Opcodes.ICONST_M1:
                    f.pushInt(-1);
                    break;
                case 10: case 11: case 12: case 13: case 14: case 15:
                    f.pushInt(op - Opcodes.ICONST_0);
                    break;

                case Opcodes.BSPUSH:
                    f.pushShort(code[pc + 1]);
                    break;
                case Opcodes.SSPUSH:
                    f.pushShort(s2(code, pc + 1));
                    break;
                case Opcodes.BIPUSH:
                    f.pushInt(code[pc + 1]);
                    break;
                case Opcodes.SIPUSH:
                    f.pushInt(s2(code, pc + 1));
                    break;
                case Opcodes.IIPUSH:
                    f.pushInt(s4(code, pc + 1));
                    break;

                case Opcodes.ALOAD:
                    f.pushRef(f.getLocalRef(u1(code, pc + 1)));
                    break;
                case Opcodes.SLOAD:
                    f.pushShort(f.getLocalShort(u1(code, pc + 1)));
                    break;
                case Opcodes.ILOAD:
                    f.pushInt(f.getLocalInt(u1(code, pc + 1)));
                    break;
                case 24: case 25: case 26: case 27:
                    f.pushRef(f.getLocalRef(op - Opcodes.ALOAD_0));
                    break;
                case 28: case 29: case 30: case 31:
                    f.pushShort(f.getLocalShort(op - Opcodes.SLOAD_0));
                    break;
                case 32: case 33: case 34: case 35:
                    f.pushInt(f.getLocalInt(op - Opcodes.ILOAD_0));
                    break;

                case Opcodes.ASTORE:
                    f.setLocalRef(u1(code, pc + 1), f.popRef());
                    break;
                case Opcodes.SSTORE:
                    f.setLocalShort(u1(code, pc + 1), f.popShort());
                    break;
                case Opcodes.ISTORE:
                    f.setLocalInt(u1(code, pc + 1), f.popInt());
                    break;
                case 43: case 44: case 45: case 46:
                    f.setLocalRef(op - Opcodes.ASTORE_0, f.popRef());
                    break;
                case 47: case 48: case 49: case 50:
                    f.setLocalShort(op - Opcodes.SSTORE_0, f.popShort());
                    break;
                case 51: case 52: case 53: case 54:
                    f.setLocalInt(op - Opcodes.ISTORE_0, f.popInt());
                    break;

                case Opcodes.AALOAD: {
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    f.pushRef(a.getRef(i));
                    break;
                }
                case Opcodes.BALOAD: {
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    f.pushShort(a.getPrim(i));
                    break;
                }
                case Opcodes.SALOAD: {
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    f.pushShort(a.getPrim(i));
                    break;
                }
                case Opcodes.IALOAD: {
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    f.pushInt(a.getPrim(i));
                    break;
                }

                case Opcodes.AASTORE: {
                    Object v = f.popRef();
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    transaction.recordArrayRef(a, i);
                    a.setRef(i, v);
                    break;
                }
                case Opcodes.BASTORE: {
                    int v = f.popShort();
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    transaction.recordArrayPrim(a, i);
                    a.setPrim(i, v);
                    break;
                }
                case Opcodes.SASTORE: {
                    int v = f.popShort();
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    transaction.recordArrayPrim(a, i);
                    a.setPrim(i, v);
                    break;
                }
                case Opcodes.IASTORE: {
                    int v = f.popInt();
                    int i = f.popShort();
                    JCArray a = arrayOf(f.popRef());
                    transaction.recordArrayPrim(a, i);
                    a.setPrim(i, v);
                    break;
                }

                case Opcodes.POP:
                    f.popRaw();
                    f.sr[f.sp] = null;
                    break;
                case Opcodes.POP2:
                    f.popRaw();
                    f.sr[f.sp] = null;
                    f.popRaw();
                    f.sr[f.sp] = null;
                    break;
                case Opcodes.DUP:
                    f.sv[f.sp] = f.sv[f.sp - 1];
                    f.sr[f.sp] = f.sr[f.sp - 1];
                    f.sp++;
                    break;
                case Opcodes.DUP2:
                    f.sv[f.sp] = f.sv[f.sp - 2];
                    f.sr[f.sp] = f.sr[f.sp - 2];
                    f.sv[f.sp + 1] = f.sv[f.sp - 1];
                    f.sr[f.sp + 1] = f.sr[f.sp - 1];
                    f.sp += 2;
                    break;
                case Opcodes.DUP_X:
                    dupX(f, u1(code, pc + 1));
                    break;
                case Opcodes.SWAP_X:
                    swapX(f, u1(code, pc + 1));
                    break;

                case Opcodes.SADD: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a + b);
                    break;
                }
                case Opcodes.SSUB: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a - b);
                    break;
                }
                case Opcodes.SMUL: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a * b);
                    break;
                }
                case Opcodes.SDIV: {
                    int b = f.popShort();
                    int a = f.popShort();
                    if (b == 0) {
                        throw new JCThrow(JCThrow.ARITHMETIC, "/ by zero");
                    }
                    f.pushShort(a / b);
                    break;
                }
                case Opcodes.SREM: {
                    int b = f.popShort();
                    int a = f.popShort();
                    if (b == 0) {
                        throw new JCThrow(JCThrow.ARITHMETIC, "% by zero");
                    }
                    f.pushShort(a % b);
                    break;
                }
                case Opcodes.SNEG:
                    f.pushShort(-f.popShort());
                    break;
                case Opcodes.SSHL: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a << (b & 0x1F));
                    break;
                }
                case Opcodes.SSHR: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a >> (b & 0x1F));
                    break;
                }
                case Opcodes.SUSHR: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort((a & 0xFFFF) >>> (b & 0x1F));
                    break;
                }
                case Opcodes.SAND: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a & b);
                    break;
                }
                case Opcodes.SOR: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a | b);
                    break;
                }
                case Opcodes.SXOR: {
                    int b = f.popShort();
                    int a = f.popShort();
                    f.pushShort(a ^ b);
                    break;
                }

                case Opcodes.IADD: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a + b);
                    break;
                }
                case Opcodes.ISUB: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a - b);
                    break;
                }
                case Opcodes.IMUL: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a * b);
                    break;
                }
                case Opcodes.IDIV: {
                    int b = f.popInt();
                    int a = f.popInt();
                    if (b == 0) {
                        throw new JCThrow(JCThrow.ARITHMETIC, "/ by zero");
                    }
                    f.pushInt(a / b);
                    break;
                }
                case Opcodes.IREM: {
                    int b = f.popInt();
                    int a = f.popInt();
                    if (b == 0) {
                        throw new JCThrow(JCThrow.ARITHMETIC, "% by zero");
                    }
                    f.pushInt(a % b);
                    break;
                }
                case Opcodes.INEG:
                    f.pushInt(-f.popInt());
                    break;
                case Opcodes.ISHL: {
                    int b = f.popShort();
                    int a = f.popInt();
                    f.pushInt(a << (b & 0x1F));
                    break;
                }
                case Opcodes.ISHR: {
                    int b = f.popShort();
                    int a = f.popInt();
                    f.pushInt(a >> (b & 0x1F));
                    break;
                }
                case Opcodes.IUSHR: {
                    int b = f.popShort();
                    int a = f.popInt();
                    f.pushInt(a >>> (b & 0x1F));
                    break;
                }
                case Opcodes.IAND: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a & b);
                    break;
                }
                case Opcodes.IOR: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a | b);
                    break;
                }
                case Opcodes.IXOR: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushInt(a ^ b);
                    break;
                }

                case Opcodes.SINC: {
                    int idx = u1(code, pc + 1);
                    f.setLocalShort(idx, f.getLocalShort(idx) + code[pc + 2]);
                    break;
                }
                case Opcodes.IINC: {
                    int idx = u1(code, pc + 1);
                    f.setLocalInt(idx, f.getLocalInt(idx) + code[pc + 2]);
                    break;
                }
                case Opcodes.SINC_W: {
                    int idx = u1(code, pc + 1);
                    f.setLocalShort(idx, f.getLocalShort(idx) + s2(code, pc + 2));
                    break;
                }
                case Opcodes.IINC_W: {
                    int idx = u1(code, pc + 1);
                    f.setLocalInt(idx, f.getLocalInt(idx) + s2(code, pc + 2));
                    break;
                }

                case Opcodes.S2B:
                    f.pushShort((byte) f.popShort());
                    break;
                case Opcodes.S2I:
                    f.pushInt(f.popShort());
                    break;
                case Opcodes.I2B:
                    f.pushShort((byte) f.popInt());
                    break;
                case Opcodes.I2S:
                    f.pushShort((short) f.popInt());
                    break;

                case Opcodes.ICMP: {
                    int b = f.popInt();
                    int a = f.popInt();
                    f.pushShort(a > b ? 1 : (a == b ? 0 : -1));
                    break;
                }

                /* ---- conditional branches, 1 byte offset ---- */
                case Opcodes.IFEQ:
                    next = branch(f, code, pc, next, f.popShort() == 0, 1);
                    break;
                case Opcodes.IFNE:
                    next = branch(f, code, pc, next, f.popShort() != 0, 1);
                    break;
                case Opcodes.IFLT:
                    next = branch(f, code, pc, next, f.popShort() < 0, 1);
                    break;
                case Opcodes.IFGE:
                    next = branch(f, code, pc, next, f.popShort() >= 0, 1);
                    break;
                case Opcodes.IFGT:
                    next = branch(f, code, pc, next, f.popShort() > 0, 1);
                    break;
                case Opcodes.IFLE:
                    next = branch(f, code, pc, next, f.popShort() <= 0, 1);
                    break;
                case Opcodes.IFNULL:
                    next = branch(f, code, pc, next, f.popRef() == null, 1);
                    break;
                case Opcodes.IFNONNULL:
                    next = branch(f, code, pc, next, f.popRef() != null, 1);
                    break;
                case Opcodes.IF_ACMPEQ: {
                    Object b = f.popRef();
                    Object a = f.popRef();
                    next = branch(f, code, pc, next, a == b, 1);
                    break;
                }
                case Opcodes.IF_ACMPNE: {
                    Object b = f.popRef();
                    Object a = f.popRef();
                    next = branch(f, code, pc, next, a != b, 1);
                    break;
                }
                case Opcodes.IF_SCMPEQ: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a == b, 1);
                    break;
                }
                case Opcodes.IF_SCMPNE: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a != b, 1);
                    break;
                }
                case Opcodes.IF_SCMPLT: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a < b, 1);
                    break;
                }
                case Opcodes.IF_SCMPGE: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a >= b, 1);
                    break;
                }
                case Opcodes.IF_SCMPGT: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a > b, 1);
                    break;
                }
                case Opcodes.IF_SCMPLE: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a <= b, 1);
                    break;
                }
                case Opcodes.GOTO:
                    next = pc + code[pc + 1];
                    break;

                /* ---- conditional branches, 2 byte offset ---- */
                case Opcodes.IFEQ_W:
                    next = branch(f, code, pc, next, f.popShort() == 0, 2);
                    break;
                case Opcodes.IFNE_W:
                    next = branch(f, code, pc, next, f.popShort() != 0, 2);
                    break;
                case Opcodes.IFLT_W:
                    next = branch(f, code, pc, next, f.popShort() < 0, 2);
                    break;
                case Opcodes.IFGE_W:
                    next = branch(f, code, pc, next, f.popShort() >= 0, 2);
                    break;
                case Opcodes.IFGT_W:
                    next = branch(f, code, pc, next, f.popShort() > 0, 2);
                    break;
                case Opcodes.IFLE_W:
                    next = branch(f, code, pc, next, f.popShort() <= 0, 2);
                    break;
                case Opcodes.IFNULL_W:
                    next = branch(f, code, pc, next, f.popRef() == null, 2);
                    break;
                case Opcodes.IFNONNULL_W:
                    next = branch(f, code, pc, next, f.popRef() != null, 2);
                    break;
                case Opcodes.IF_ACMPEQ_W: {
                    Object b = f.popRef();
                    Object a = f.popRef();
                    next = branch(f, code, pc, next, a == b, 2);
                    break;
                }
                case Opcodes.IF_ACMPNE_W: {
                    Object b = f.popRef();
                    Object a = f.popRef();
                    next = branch(f, code, pc, next, a != b, 2);
                    break;
                }
                case Opcodes.IF_SCMPEQ_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a == b, 2);
                    break;
                }
                case Opcodes.IF_SCMPNE_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a != b, 2);
                    break;
                }
                case Opcodes.IF_SCMPLT_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a < b, 2);
                    break;
                }
                case Opcodes.IF_SCMPGE_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a >= b, 2);
                    break;
                }
                case Opcodes.IF_SCMPGT_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a > b, 2);
                    break;
                }
                case Opcodes.IF_SCMPLE_W: {
                    int b = f.popShort();
                    int a = f.popShort();
                    next = branch(f, code, pc, next, a <= b, 2);
                    break;
                }
                case Opcodes.GOTO_W:
                    next = pc + s2(code, pc + 1);
                    break;

                case Opcodes.JSR:
                    f.pushRef(new ReturnAddress(next));
                    next = pc + s2(code, pc + 1);
                    break;
                case Opcodes.RET: {
                    Object ra = f.getLocalRef(u1(code, pc + 1));
                    if (!(ra instanceof ReturnAddress)) {
                        throw new JCThrow("java/lang/Error", "ret on a non return address");
                    }
                    next = ((ReturnAddress) ra).pc;
                    break;
                }

                /* ---- switches ---- */
                case Opcodes.STABLESWITCH: {
                    int key = f.popShort();
                    int def = s2(code, pc + 1);
                    int low = s2(code, pc + 3);
                    int high = s2(code, pc + 5);
                    if (key < low || key > high) {
                        next = pc + def;
                    } else {
                        next = pc + s2(code, pc + 7 + 2 * (key - low));
                    }
                    break;
                }
                case Opcodes.ITABLESWITCH: {
                    int key = f.popInt();
                    int def = s2(code, pc + 1);
                    int low = s4(code, pc + 3);
                    int high = s4(code, pc + 7);
                    if (key < low || key > high) {
                        next = pc + def;
                    } else {
                        next = pc + s2(code, pc + 11 + 2 * (key - low));
                    }
                    break;
                }
                case Opcodes.SLOOKUPSWITCH: {
                    int key = f.popShort();
                    int def = s2(code, pc + 1);
                    int n = u2(code, pc + 3);
                    next = pc + def;
                    for (int i = 0; i < n; i++) {
                        int at = pc + 5 + 4 * i;
                        if (s2(code, at) == key) {
                            next = pc + s2(code, at + 2);
                            break;
                        }
                    }
                    break;
                }
                case Opcodes.ILOOKUPSWITCH: {
                    int key = f.popInt();
                    int def = s2(code, pc + 1);
                    int n = u2(code, pc + 3);
                    next = pc + def;
                    for (int i = 0; i < n; i++) {
                        int at = pc + 5 + 6 * i;
                        if (s4(code, at) == key) {
                            next = pc + s2(code, at + 4);
                            break;
                        }
                    }
                    break;
                }

                /* ---- returns ---- */
                case Opcodes.RETURN:
                    retKind = RET_VOID;
                    return;
                case Opcodes.SRETURN:
                    retKind = RET_WORD;
                    retWord = f.popShort();
                    return;
                case Opcodes.IRETURN:
                    retKind = RET_INT;
                    retIntValue = f.popInt();
                    return;
                case Opcodes.ARETURN:
                    retKind = RET_REF;
                    retObject = f.popRef();
                    return;

                /* ---- static fields ---- */
                case Opcodes.GETSTATIC_A: case Opcodes.GETSTATIC_B:
                case Opcodes.GETSTATIC_S: case Opcodes.GETSTATIC_I:
                    getStatic(f, pkg, u2(code, pc + 1), op);
                    break;
                case Opcodes.PUTSTATIC_A: case Opcodes.PUTSTATIC_B:
                case Opcodes.PUTSTATIC_S: case Opcodes.PUTSTATIC_I:
                    putStatic(f, pkg, u2(code, pc + 1), op);
                    break;

                /* ---- instance fields ---- */
                case Opcodes.GETFIELD_A: case Opcodes.GETFIELD_B:
                case Opcodes.GETFIELD_S: case Opcodes.GETFIELD_I:
                    getField(f, pkg, u1(code, pc + 1), op - Opcodes.GETFIELD_A, f.popRef());
                    break;
                case Opcodes.GETFIELD_A_W: case Opcodes.GETFIELD_B_W:
                case Opcodes.GETFIELD_S_W: case Opcodes.GETFIELD_I_W:
                    getField(f, pkg, u2(code, pc + 1), op - Opcodes.GETFIELD_A_W, f.popRef());
                    break;
                case Opcodes.GETFIELD_A_THIS: case Opcodes.GETFIELD_B_THIS:
                case Opcodes.GETFIELD_S_THIS: case Opcodes.GETFIELD_I_THIS:
                    getField(f, pkg, u1(code, pc + 1), op - Opcodes.GETFIELD_A_THIS,
                            f.getLocalRef(0));
                    break;

                case Opcodes.PUTFIELD_A: case Opcodes.PUTFIELD_B:
                case Opcodes.PUTFIELD_S: case Opcodes.PUTFIELD_I:
                    putField(f, pkg, u1(code, pc + 1), op - Opcodes.PUTFIELD_A, false);
                    break;
                case Opcodes.PUTFIELD_A_W: case Opcodes.PUTFIELD_B_W:
                case Opcodes.PUTFIELD_S_W: case Opcodes.PUTFIELD_I_W:
                    putField(f, pkg, u2(code, pc + 1), op - Opcodes.PUTFIELD_A_W, false);
                    break;
                case Opcodes.PUTFIELD_A_THIS: case Opcodes.PUTFIELD_B_THIS:
                case Opcodes.PUTFIELD_S_THIS: case Opcodes.PUTFIELD_I_THIS:
                    putField(f, pkg, u1(code, pc + 1), op - Opcodes.PUTFIELD_A_THIS, true);
                    break;

                /* ---- invocation ---- */
                case Opcodes.INVOKEVIRTUAL:
                    invokeVirtual(f, pkg, u2(code, pc + 1));
                    break;
                case Opcodes.INVOKESPECIAL:
                    invokeSpecial(f, pkg, u2(code, pc + 1));
                    break;
                case Opcodes.INVOKESTATIC:
                    invokeStatic(f, pkg, u2(code, pc + 1));
                    break;
                case Opcodes.INVOKEINTERFACE:
                    invokeInterface(f, pkg, u1(code, pc + 1), u2(code, pc + 2),
                            u1(code, pc + 4));
                    break;

                /* ---- object and array creation ---- */
                case Opcodes.NEW: {
                    Object c = resolveClass(pkg, cp(pkg, u2(code, pc + 1)));
                    if (c instanceof ClassRt) {
                        f.pushRef(new JCObject((ClassRt) c));
                    } else {
                        f.pushRef(new BuiltinObject((String) c));
                    }
                    break;
                }
                case Opcodes.NEWARRAY: {
                    int count = f.popShort();
                    checkArraySize(count);
                    f.pushRef(new JCArray(u1(code, pc + 1), count));
                    break;
                }
                case Opcodes.ANEWARRAY: {
                    int count = f.popShort();
                    checkArraySize(count);
                    JCArray a = new JCArray(JCArray.T_REFERENCE, count);
                    a.elementClass = resolveClass(pkg, cp(pkg, u2(code, pc + 1)));
                    f.pushRef(a);
                    break;
                }
                case Opcodes.ARRAYLENGTH:
                    f.pushShort(arrayOf(f.popRef()).length);
                    break;

                case Opcodes.ATHROW: {
                    Object o = f.popRef();
                    if (o == null) {
                        throw JCThrow.nullPointer();
                    }
                    throw toThrow(o);
                }

                case Opcodes.CHECKCAST: {
                    Object o = f.peekRef(0);
                    if (o != null && !isInstance(o, pkg, u1(code, pc + 1), u2(code, pc + 2))) {
                        throw new JCThrow(JCThrow.CLASS_CAST, String.valueOf(o));
                    }
                    break;
                }
                case Opcodes.INSTANCEOF: {
                    Object o = f.popRef();
                    boolean r = o != null
                            && isInstance(o, pkg, u1(code, pc + 1), u2(code, pc + 2));
                    f.pushShort(r ? 1 : 0);
                    break;
                }

                case Opcodes.IMPDEP1:
                case Opcodes.IMPDEP2:
                    throw new JCThrow("java/lang/Error", "reserved opcode " + op + " at " + pc);

                default:
                    throw new JCThrow("java/lang/Error",
                            "unimplemented opcode " + op + " (" + Opcodes.name(op)
                            + ") at " + pc + " in " + pkg.name());
            }

            f.pc = next;
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static int u1(byte[] c, int off) {
        return c[off] & 0xFF;
    }

    private static int u2(byte[] c, int off) {
        return ((c[off] & 0xFF) << 8) | (c[off + 1] & 0xFF);
    }

    private static int s2(byte[] c, int off) {
        return (short) u2(c, off);
    }

    private static int s4(byte[] c, int off) {
        return ((c[off] & 0xFF) << 24) | ((c[off + 1] & 0xFF) << 16)
                | ((c[off + 2] & 0xFF) << 8) | (c[off + 3] & 0xFF);
    }

    private static int branch(Frame f, byte[] code, int pc, int next, boolean taken, int width) {
        if (!taken) {
            return next;
        }
        return pc + (width == 1 ? code[pc + 1] : s2(code, pc + 1));
    }

    private static void checkArraySize(int count) {
        if (count < 0) {
            throw new JCThrow(JCThrow.NEGATIVE_ARRAY_SIZE, String.valueOf(count));
        }
    }

    private static JCArray arrayOf(Object o) {
        if (o == null) {
            throw JCThrow.nullPointer();
        }
        if (!(o instanceof JCArray)) {
            throw new JCThrow(JCThrow.CLASS_CAST, "not an array: " + o);
        }
        return (JCArray) o;
    }

    private static void dupX(Frame f, int mn) {
        int m = (mn >> 4) & 0x0F;
        int n = mn & 0x0F;
        int[] tv = new int[m];
        Object[] tr = new Object[m];
        System.arraycopy(f.sv, f.sp - m, tv, 0, m);
        System.arraycopy(f.sr, f.sp - m, tr, 0, m);
        int at = (n == 0) ? f.sp : f.sp - n;
        System.arraycopy(f.sv, at, f.sv, at + m, f.sp - at);
        System.arraycopy(f.sr, at, f.sr, at + m, f.sp - at);
        System.arraycopy(tv, 0, f.sv, at, m);
        System.arraycopy(tr, 0, f.sr, at, m);
        f.sp += m;
    }

    private static void swapX(Frame f, int mn) {
        int m = (mn >> 4) & 0x0F;
        int n = mn & 0x0F;
        int base = f.sp - m - n;
        int[] top = new int[m];
        Object[] topR = new Object[m];
        int[] low = new int[n];
        Object[] lowR = new Object[n];
        System.arraycopy(f.sv, base + n, top, 0, m);
        System.arraycopy(f.sr, base + n, topR, 0, m);
        System.arraycopy(f.sv, base, low, 0, n);
        System.arraycopy(f.sr, base, lowR, 0, n);
        System.arraycopy(top, 0, f.sv, base, m);
        System.arraycopy(topR, 0, f.sr, base, m);
        System.arraycopy(low, 0, f.sv, base + m, n);
        System.arraycopy(lowR, 0, f.sr, base + m, n);
    }

    private static CapPackage.CpEntry cp(LoadedPackage pkg, int index) {
        CapPackage.CpEntry[] a = pkg.cap.constantPool;
        if (index < 0 || index >= a.length) {
            throw new JCThrow("java/lang/Error",
                    "constant pool index " + index + " out of range in " + pkg.name());
        }
        return a[index];
    }

    /** Returns a ClassRt for loaded classes or a String for native API classes. */
    private static Object resolveClass(LoadedPackage pkg, CapPackage.CpEntry e) {
        if (!e.external) {
            return pkg.classAt(e.offset);
        }
        ClassRt c = pkg.externalLoadedClass(e.packageToken, e.classToken);
        if (c != null) {
            return c;
        }
        String n = pkg.externalClassName(e.packageToken, e.classToken);
        if (n == null) {
            throw new LinkException("cannot resolve external class token "
                    + e.classToken + " of import " + e.packageToken + " in " + pkg.name());
        }
        return n;
    }

    /* ---------------- fields ---------------- */

    private void getStatic(Frame f, LoadedPackage pkg, int cpIndex, int op) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        LoadedPackage target = pkg;
        int off = e.offset;
        if (e.external) {
            Object owner = pkg.importAt(e.packageToken);
            if (!(owner instanceof LoadedPackage)) {
                throw new LinkException("static fields of API package classes are not"
                        + " addressable at runtime (they are compile time constants)");
            }
            target = (LoadedPackage) owner;
            off = target.cap.exports.get(e.classToken).staticFieldOffsets[e.token];
        }
        switch (op) {
            case Opcodes.GETSTATIC_A:
                f.pushRef(target.getStaticRef(off));
                break;
            case Opcodes.GETSTATIC_B:
                f.pushShort(target.getStaticByte(off));
                break;
            case Opcodes.GETSTATIC_S:
                f.pushShort(target.getStaticShort(off));
                break;
            default:
                f.pushInt(target.getStaticInt(off));
                break;
        }
    }

    private void putStatic(Frame f, LoadedPackage pkg, int cpIndex, int op) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        LoadedPackage target = pkg;
        int off = e.offset;
        if (e.external) {
            Object owner = pkg.importAt(e.packageToken);
            if (!(owner instanceof LoadedPackage)) {
                throw new LinkException("cannot write a static field of an API package");
            }
            target = (LoadedPackage) owner;
            off = target.cap.exports.get(e.classToken).staticFieldOffsets[e.token];
        }
        switch (op) {
            case Opcodes.PUTSTATIC_A:
                transaction.recordStaticRef(target, off);
                target.setStaticRef(off, f.popRef());
                break;
            case Opcodes.PUTSTATIC_B:
                transaction.recordStaticData(target, off, 1);
                target.setStaticByte(off, f.popShort());
                break;
            case Opcodes.PUTSTATIC_S:
                transaction.recordStaticData(target, off, 2);
                target.setStaticShort(off, f.popShort());
                break;
            default:
                transaction.recordStaticData(target, off, 4);
                target.setStaticInt(off, f.popInt());
                break;
        }
    }

    /** kind: 0=a 1=b 2=s 3=i */
    private void getField(Frame f, LoadedPackage pkg, int cpIndex, int kind, Object obj) {
        if (obj == null) {
            throw JCThrow.nullPointer();
        }
        int token = cp(pkg, cpIndex).token;
        if (!(obj instanceof JCObject)) {
            throw new JCThrow("java/lang/Error",
                    "field access on a non applet object: " + obj);
        }
        JCObject o = (JCObject) obj;
        switch (kind) {
            case 0:
                f.pushRef(o.getRef(token));
                break;
            case 1:
                f.pushShort((byte) o.getPrim(token));
                break;
            case 2:
                f.pushShort(o.getPrim(token));
                break;
            default:
                f.pushInt(o.getIntField(token));
                break;
        }
    }

    private void putField(Frame f, LoadedPackage pkg, int cpIndex, int kind, boolean thisForm) {
        int token = cp(pkg, cpIndex).token;
        Object obj;
        if (kind == 3) {
            int value = f.popInt();
            obj = thisForm ? f.getLocalRef(0) : f.popRef();
            JCObject o = asJCObject(obj);
            transaction.recordFieldPrim(o, token);
            transaction.recordFieldPrim(o, token + 1);
            o.setIntField(token, value);
            return;
        }
        if (kind == 0) {
            Object value = f.popRef();
            obj = thisForm ? f.getLocalRef(0) : f.popRef();
            JCObject o = asJCObject(obj);
            transaction.recordFieldRef(o, token);
            o.setRef(token, value);
            return;
        }
        int value = f.popShort();
        obj = thisForm ? f.getLocalRef(0) : f.popRef();
        JCObject o = asJCObject(obj);
        transaction.recordFieldPrim(o, token);
        o.setPrim(token, kind == 1 ? (byte) value : (short) value);
    }

    private static JCObject asJCObject(Object obj) {
        if (obj == null) {
            throw JCThrow.nullPointer();
        }
        if (!(obj instanceof JCObject)) {
            throw new JCThrow("java/lang/Error", "field access on a non applet object: " + obj);
        }
        return (JCObject) obj;
    }

    /* ---------------- invocation ---------------- */

    private void invokeVirtual(Frame f, LoadedPackage pkg, int cpIndex) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        int token = e.token;
        Object declared = resolveClass(pkg, e);

        int nargs;
        if (declared instanceof ClassRt) {
            ClassRt.VirtualTarget t = ((ClassRt) declared).lookupVirtual(token);
            nargs = t.isNative()
                    ? Descriptor.of(apiVirtualKey(t.externalClass, token)).argWords + 1
                    : t.method.nargs;
        } else {
            nargs = Descriptor.of(apiVirtualKey((String) declared, token)).argWords + 1;
        }

        Object receiver = f.peekRef(nargs - 1);
        if (receiver == null) {
            throw JCThrow.nullPointer();
        }
        if (receiver instanceof JCObject) {
            ClassRt.VirtualTarget t = ((JCObject) receiver).clazz.lookupVirtual(token);
            if (t.isNative()) {
                invokeNative(f, apiVirtualKey(t.externalClass, token), true);
            } else {
                invokeLoaded(f, t.method);
            }
        } else if (receiver instanceof BuiltinObject) {
            String cn = ((BuiltinObject) receiver).className;
            String key = apiVirtualKeyOrNull(cn, token);
            if (key == null && declared instanceof String) {
                key = apiVirtualKey((String) declared, token);
            }
            if (key == null) {
                throw new LinkException("no API binding for virtual token " + token
                        + " on " + cn);
            }
            invokeNative(f, key, true);
        } else {
            throw new JCThrow("java/lang/Error",
                    "cannot invoke a virtual method on " + receiver);
        }
    }

    private void invokeSpecial(Frame f, LoadedPackage pkg, int cpIndex) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        if (e.tag == CapPackage.CP_SUPER_METHODREF) {
            // The class ref names the current class; resolution starts one level up.
            Object declared = resolveClass(pkg, e);
            if (!(declared instanceof ClassRt)) {
                invokeNative(f, apiVirtualKey((String) declared, e.token), true);
                return;
            }
            ClassRt here = (ClassRt) declared;
            if (here.superClass != null) {
                ClassRt.VirtualTarget t = here.superClass.lookupVirtual(e.token);
                if (t.isNative()) {
                    invokeNative(f, apiVirtualKey(t.externalClass, e.token), true);
                } else {
                    invokeLoaded(f, t.method);
                }
            } else if (here.externalSuperName != null) {
                invokeNative(f, apiVirtualKey(here.externalSuperName, e.token), true);
            } else {
                throw new LinkException(here.label + " has no superclass for a super call");
            }
            return;
        }

        // CONSTANT_StaticMethodref: constructors and private instance methods
        if (!e.external) {
            invokeLoaded(f, pkg.method(e.offset));
            return;
        }
        ApiClass ac = pkg.externalApiClass(e.packageToken, e.classToken);
        if (ac != null) {
            String key = ac.staticKey(e.token);
            if (key == null) {
                throw new LinkException("no binding for static token " + e.token
                        + " of " + ac.name + describeKnownTokens(ac.name, false));
            }
            // constructors and private instance methods both consume `this`
            invokeNative(f, key, true);
            return;
        }
        LoadedPackage lp = (LoadedPackage) pkg.importAt(e.packageToken);
        int off = lp.cap.exports.get(e.classToken).staticMethodOffsets[e.token];
        invokeLoaded(f, lp.method(off));
    }

    private void invokeStatic(Frame f, LoadedPackage pkg, int cpIndex) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        if (!e.external) {
            invokeLoaded(f, pkg.method(e.offset));
            return;
        }
        ApiClass ac = pkg.externalApiClass(e.packageToken, e.classToken);
        if (ac != null) {
            String key = ac.staticKey(e.token);
            if (key == null) {
                throw new LinkException("no binding for static token " + e.token
                        + " of " + ac.name + describeKnownTokens(ac.name, false));
            }
            invokeNative(f, key, false);
            return;
        }
        LoadedPackage lp = (LoadedPackage) pkg.importAt(e.packageToken);
        int off = lp.cap.exports.get(e.classToken).staticMethodOffsets[e.token];
        invokeLoaded(f, lp.method(off));
    }

    private void invokeInterface(Frame f, LoadedPackage pkg, int nargs, int cpIndex,
            int methodToken) {
        CapPackage.CpEntry e = cp(pkg, cpIndex);
        Object iface = resolveClass(pkg, e);
        Object receiver = f.peekRef(nargs - 1);
        if (receiver == null) {
            throw JCThrow.nullPointer();
        }
        if (receiver instanceof BuiltinObject) {
            // FileView, ToolkitRegistry and the handlers are interfaces whose
            // implementation lives in the VM, so dispatch straight to a native.
            String cn = ((BuiltinObject) receiver).className;
            String key = apiVirtualKeyOrNull(cn, methodToken);
            if (key == null && iface instanceof String) {
                key = apiVirtualKeyOrNull((String) iface, methodToken);
            }
            if (key == null) {
                throw new LinkException("no binding for interface token " + methodToken
                        + " on " + cn + describeKnownTokens(cn, true));
            }
            invokeNative(f, key, true);
            return;
        }
        if (!(receiver instanceof JCObject)) {
            throw new JCThrow("java/lang/Error",
                    "interface call on a non applet object: " + receiver);
        }
        ClassRt rc = ((JCObject) receiver).clazz;
        int vtoken = rc.interfaceToken(iface instanceof ClassRt ? (ClassRt) iface : null,
                iface instanceof String ? (String) iface : null, methodToken);
        if (vtoken < 0) {
            throw new JCThrow("java/lang/Error", rc.label
                    + " does not implement interface method " + methodToken);
        }
        ClassRt.VirtualTarget t = rc.lookupVirtual(vtoken);
        if (t.isNative()) {
            invokeNative(f, apiVirtualKey(t.externalClass, vtoken), true);
        } else {
            invokeLoaded(f, t.method);
        }
    }

    private void invokeLoaded(Frame caller, MethodRt m) {
        if (m.isAbstract()) {
            throw new JCThrow("java/lang/Error", "abstract method invoked: " + m);
        }
        int nargs = m.nargs;
        Frame callee = new Frame(m);
        int base = caller.sp - nargs;
        if (base < 0) {
            throw new JCThrow("java/lang/Error", "stack underflow calling " + m);
        }
        for (int i = 0; i < nargs; i++) {
            callee.lv[i] = caller.sv[base + i];
            callee.lr[i] = caller.sr[base + i];
            caller.sr[base + i] = null;
        }
        caller.sp = base;
        retKind = RET_VOID;
        execute(callee);
        pushResult(caller);
    }

    private void invokeNative(Frame caller, String key, boolean consumesSelf) {
        NativeImpl impl = natives.get(key);
        if (impl == null) {
            throw new LinkException("this VM does not implement " + key
                    + "\n  (the token resolved fine - the method itself is missing)");
        }
        Descriptor d = Descriptor.of(key);
        int n = d.argWords;
        int total = n + (consumesSelf ? 1 : 0);
        int base = caller.sp - total;
        if (base < 0) {
            throw new JCThrow("java/lang/Error", "stack underflow calling " + key);
        }
        Object self = consumesSelf ? caller.sr[base] : null;
        int shift = consumesSelf ? 1 : 0;
        int[] v = new int[n + 1];
        Object[] r = new Object[n + 1];
        for (int i = 0; i < n; i++) {
            v[i] = caller.sv[base + shift + i];
            r[i] = caller.sr[base + shift + i];
        }
        for (int i = base; i < caller.sp; i++) {
            caller.sr[i] = null;
        }
        caller.sp = base;
        if (consumesSelf && self == null) {
            throw JCThrow.nullPointer();
        }
        if (trace) {
            out.println("    -> native " + key);
        }
        retKind = RET_VOID;
        impl.invoke(this, new NativeArgs(self, d, v, r));
        switch (d.returnKind) {
            case 'V':
                break;
            case Descriptor.KIND_REF:
                caller.pushRef(retObject);
                break;
            case 'I':
                caller.pushInt(retIntValue);
                break;
            default:
                caller.pushShort(retWord);
                break;
        }
    }

    private void pushResult(Frame caller) {
        switch (retKind) {
            case RET_WORD:
                caller.pushShort(retWord);
                break;
            case RET_REF:
                caller.pushRef(retObject);
                break;
            case RET_INT:
                caller.pushInt(retIntValue);
                break;
            default:
                break;
        }
    }

    /* ---------------- API binding ---------------- */

    public String apiVirtualKey(String className, int token) {
        String key = apiVirtualKeyOrNull(className, token);
        if (key == null) {
            throw new LinkException("no binding for virtual token " + token + " of "
                    + className + describeKnownTokens(className, true)
                    + "\n  the CAP was built against different export files;"
                    + " use 'loadexp <sdk>/api_export_files' or fix api-tokens.txt");
        }
        return key;
    }

    /** Lists the tokens we do know, so a mismatch is obvious at a glance. */
    public String describeKnownTokens(String className, boolean virtual) {
        ApiClass ac = api.classByName(className);
        if (ac == null) {
            return "\n  (no token table entry for the class at all)";
        }
        java.util.Map<Integer, String> m = virtual ? ac.virtualMethods : ac.staticMethods;
        java.util.List<Integer> t = new java.util.ArrayList<Integer>(m.keySet());
        java.util.Collections.sort(t);
        StringBuilder sb = new StringBuilder("\n  known "
                + (virtual ? "virtual" : "static") + " tokens for " + className + ":");
        for (int i = 0; i < t.size(); i++) {
            sb.append("\n    ").append(t.get(i)).append(" -> ").append(m.get(t.get(i)));
        }
        return sb.toString();
    }

    public String apiVirtualKeyOrNull(String className, int token) {
        String c = className;
        while (c != null) {
            ApiClass ac = api.classByName(c);
            if (ac != null) {
                String k = ac.virtualKey(token);
                if (k != null) {
                    return k;
                }
            }
            c = BuiltinObject.superOf(c);
        }
        return null;
    }

    /* ---------------- type tests and exceptions ---------------- */

    private boolean isInstance(Object o, LoadedPackage pkg, int atype, int cpIndex) {
        if (o instanceof JCArray) {
            JCArray a = (JCArray) o;
            if (atype >= JCArray.T_BOOLEAN && atype <= JCArray.T_INT) {
                return a.type == atype;
            }
            return a.type == JCArray.T_REFERENCE;
        }
        if (atype >= JCArray.T_BOOLEAN && atype <= JCArray.T_INT) {
            return false;
        }
        Object target = resolveClass(pkg, cp(pkg, cpIndex));
        if (o instanceof JCObject) {
            ClassRt c = ((JCObject) o).clazz;
            return (target instanceof ClassRt)
                    ? c.isSubclassOf((ClassRt) target)
                    : c.isSubclassOf((String) target);
        }
        if (o instanceof BuiltinObject) {
            return (target instanceof String)
                    && BuiltinObject.isAssignable(((BuiltinObject) o).className, (String) target);
        }
        return false;
    }

    private static JCThrow toThrow(Object o) {
        if (o instanceof BuiltinObject) {
            BuiltinObject b = (BuiltinObject) o;
            return new JCThrow(b.className, b.reason, b);
        }
        if (o instanceof JCObject) {
            JCObject j = (JCObject) o;
            JCThrow t = new JCThrow(j.clazz.label, 0, j);
            return t;
        }
        throw new JCThrow("java/lang/Error", "athrow on " + o);
    }

    private int findHandler(Frame f, JCThrow t) {
        List<CapPackage.ExceptionHandler> hs = f.pkg.cap.handlers;
        int pc = f.pc;
        for (int i = 0; i < hs.size(); i++) {
            CapPackage.ExceptionHandler h = hs.get(i);
            if (pc < h.startOffset || pc >= h.startOffset + h.activeLength) {
                continue;
            }
            if (h.handlerOffset < f.method.codeStart) {
                continue; // handler belongs to a different method
            }
            if (h.catchTypeIndex == 0 || catchMatches(f.pkg, h.catchTypeIndex, t)) {
                return h.handlerOffset;
            }
        }
        return -1;
    }

    private boolean catchMatches(LoadedPackage pkg, int cpIndex, JCThrow t) {
        Object target;
        try {
            target = resolveClass(pkg, cp(pkg, cpIndex));
        } catch (RuntimeException e) {
            return false;
        }
        Object thrown = t.thrown;
        if (thrown instanceof JCObject) {
            ClassRt c = ((JCObject) thrown).clazz;
            return (target instanceof ClassRt)
                    ? c.isSubclassOf((ClassRt) target)
                    : c.isSubclassOf((String) target);
        }
        String name = (thrown instanceof BuiltinObject)
                ? ((BuiltinObject) thrown).className : t.className;
        return (target instanceof String)
                && BuiltinObject.isAssignable(name, (String) target);
    }

    /* ---------------- misc ---------------- */

    public ApiPackage apiPackage(String name) {
        return api.packageByName(name);
    }
}
