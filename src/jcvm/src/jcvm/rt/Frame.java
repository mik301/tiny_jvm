package jcvm.rt;

/**
 * One activation record.
 *
 * Locals and the operand stack are arrays of 16 bit words, exactly as on a real
 * card. Each word is represented by a pair of parallel slots: {@code v} holds
 * the numeric value, {@code r} holds the object when the word is a reference.
 * An {@code int} occupies two consecutive words, high word first.
 */
public final class Frame {

    public final MethodRt method;
    public final LoadedPackage pkg;
    public final byte[] code;

    public int pc;

    public final int[] lv;
    public final Object[] lr;

    public final int[] sv;
    public final Object[] sr;
    public int sp;

    public Frame(MethodRt m) {
        this.method = m;
        this.pkg = m.pkg;
        this.code = m.pkg.code;
        this.pc = m.codeStart;
        // In a CAP file max_locals counts the declared locals only, the
        // arguments sit below them, so the frame needs nargs + max_locals.
        int locals = m.nargs + m.maxLocals + 2;
        this.lv = new int[locals];
        this.lr = new Object[locals];
        int stack = m.maxStack + 4;
        this.sv = new int[stack];
        this.sr = new Object[stack];
        this.sp = 0;
    }

    /* ------------------------- stack ------------------------- */

    public void pushShort(int value) {
        sv[sp] = (short) value;
        sr[sp] = null;
        sp++;
    }

    public void pushRaw(int value) {
        sv[sp] = value;
        sr[sp] = null;
        sp++;
    }

    public void pushRef(Object o) {
        sv[sp] = 0;
        sr[sp] = o;
        sp++;
    }

    public void pushInt(int value) {
        pushRaw(value >> 16);
        pushRaw(value & 0xFFFF);
    }

    public int popShort() {
        sp--;
        return (short) sv[sp];
    }

    public int popRaw() {
        sp--;
        return sv[sp];
    }

    public Object popRef() {
        sp--;
        Object o = sr[sp];
        sr[sp] = null;
        return o;
    }

    public int popInt() {
        int low = popRaw();
        int high = popRaw();
        return (high << 16) | (low & 0xFFFF);
    }

    public int peekRaw(int depth) {
        return sv[sp - 1 - depth];
    }

    public Object peekRef(int depth) {
        return sr[sp - 1 - depth];
    }

    /* ------------------------- locals ------------------------- */

    public void setLocalShort(int i, int value) {
        lv[i] = (short) value;
        lr[i] = null;
    }

    public void setLocalRef(int i, Object o) {
        lv[i] = 0;
        lr[i] = o;
    }

    public void setLocalInt(int i, int value) {
        lv[i] = value >> 16;
        lr[i] = null;
        lv[i + 1] = value & 0xFFFF;
        lr[i + 1] = null;
    }

    public int getLocalShort(int i) {
        return (short) lv[i];
    }

    public Object getLocalRef(int i) {
        return lr[i];
    }

    public int getLocalInt(int i) {
        return (lv[i] << 16) | (lv[i + 1] & 0xFFFF);
    }

    public String toString() {
        return method + " pc=" + pc + " sp=" + sp;
    }
}
