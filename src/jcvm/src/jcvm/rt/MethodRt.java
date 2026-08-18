package jcvm.rt;

import jcvm.util.ByteReader;

/** A method located in the Method component of a loaded package. */
public final class MethodRt {

    public static final int ACC_EXTENDED = 0x8;
    public static final int ACC_ABSTRACT = 0x4;

    public final LoadedPackage pkg;
    public final int offset;      // offset of method_header_info in the method component info
    public final int flags;
    public final int maxStack;
    public final int nargs;       // in 16 bit words, includes `this`
    public final int maxLocals;
    public final int codeStart;

    public MethodRt(LoadedPackage pkg, int offset) {
        this.pkg = pkg;
        this.offset = offset;
        byte[] c = pkg.code;
        if (offset < 0 || offset >= c.length) {
            throw new JCThrow("java/lang/Error", "method offset " + offset + " out of range");
        }
        int b0 = ByteReader.u1(c, offset);
        this.flags = (b0 >> 4) & 0x0F;
        if ((flags & ACC_EXTENDED) != 0) {
            this.maxStack = ByteReader.u1(c, offset + 1);
            this.nargs = ByteReader.u1(c, offset + 2);
            this.maxLocals = ByteReader.u1(c, offset + 3);
            this.codeStart = offset + 4;
        } else {
            this.maxStack = b0 & 0x0F;
            int b1 = ByteReader.u1(c, offset + 1);
            this.nargs = (b1 >> 4) & 0x0F;
            this.maxLocals = b1 & 0x0F;
            this.codeStart = offset + 2;
        }
    }

    public boolean isAbstract() {
        return (flags & ACC_ABSTRACT) != 0;
    }

    public String toString() {
        return pkg.name() + "!method@" + offset
                + "(args=" + nargs + ",locals=" + maxLocals + ",stack=" + maxStack + ")";
    }
}
