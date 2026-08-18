package jcvm.api;

import jcvm.rt.JCArray;
import jcvm.rt.JCThrow;

/** Arguments handed to a natively implemented API method. */
public final class NativeArgs {

    /** Receiver for virtual methods, null for statics. */
    public final Object self;
    public final Descriptor desc;
    private final int[] v;
    private final Object[] r;

    public NativeArgs(Object self, Descriptor desc, int[] v, Object[] r) {
        this.self = self;
        this.desc = desc;
        this.v = v;
        this.r = r;
    }

    private int off(int param) {
        return desc.paramWordOffset[param];
    }

    /** byte, short or boolean parameter, sign extended. */
    public int sh(int param) {
        return (short) v[off(param)];
    }

    public boolean bool(int param) {
        return v[off(param)] != 0;
    }

    public int in(int param) {
        int o = off(param);
        return (v[o] << 16) | (v[o + 1] & 0xFFFF);
    }

    public Object ref(int param) {
        return r[off(param)];
    }

    public JCArray array(int param) {
        Object o = ref(param);
        if (o == null) {
            throw JCThrow.nullPointer();
        }
        if (!(o instanceof JCArray)) {
            throw new JCThrow(JCThrow.CLASS_CAST, "expected an array, got " + o);
        }
        return (JCArray) o;
    }

    public byte[] bytes(int param) {
        JCArray a = array(param);
        if (a.bytes == null) {
            throw new JCThrow(JCThrow.CLASS_CAST, "expected a byte[], got " + a);
        }
        return a.bytes;
    }
}
