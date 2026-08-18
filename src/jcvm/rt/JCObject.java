package jcvm.rt;

/**
 * An instance of an applet-defined class.
 *
 * Instance fields are addressed by token. Primitive and reference fields live in
 * separate arrays; the Class component tells us which tokens are references, and
 * the two token ranges never overlap, so using the token directly as the index
 * into both arrays is safe.
 */
public final class JCObject {

    public final ClassRt clazz;
    public final int[] prim;
    public final Object[] refs;

    /** Set for objects that belong to an applet instance (context checks). */
    public Object owner;

    public JCObject(ClassRt clazz) {
        this.clazz = clazz;
        int n = Math.max(clazz.instanceSize(), 1);
        this.prim = new int[n];
        this.refs = new Object[n];
    }

    public int getPrim(int token) {
        checkToken(token);
        return prim[token];
    }

    public void setPrim(int token, int value) {
        checkToken(token);
        prim[token] = value;
    }

    public int getIntField(int token) {
        checkToken(token + 1);
        return (prim[token] << 16) | (prim[token + 1] & 0xFFFF);
    }

    public void setIntField(int token, int value) {
        checkToken(token + 1);
        prim[token] = value >> 16;
        prim[token + 1] = value & 0xFFFF;
    }

    public Object getRef(int token) {
        checkToken(token);
        return refs[token];
    }

    public void setRef(int token, Object v) {
        checkToken(token);
        refs[token] = v;
    }

    private void checkToken(int token) {
        if (token < 0 || token >= prim.length) {
            throw new JCThrow("java/lang/Error",
                    "field token " + token + " outside instance of " + clazz.label
                    + " (size " + prim.length + ")");
        }
    }

    public String toString() {
        return clazz.label + "@" + Integer.toHexString(System.identityHashCode(this));
    }
}
