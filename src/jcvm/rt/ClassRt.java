package jcvm.rt;

import jcvm.cap.CapPackage;

/** A class (or interface) defined by a loaded package. */
public final class ClassRt {

    /** Result of a virtual method lookup. */
    public static final class VirtualTarget {

        /** Set when the method is implemented by a loaded package. */
        public MethodRt method;
        /** Set when the method is inherited from a natively implemented class. */
        public String externalClass;
        public int token;

        public boolean isNative() {
            return method == null;
        }

        public String toString() {
            return method != null ? method.toString() : (externalClass + "#" + token);
        }
    }

    public final LoadedPackage pkg;
    public final CapPackage.ClassInfo info;
    public final int offset;

    /** Superclass inside this package or another loaded package. */
    public ClassRt superClass;
    /** Superclass implemented natively, e.g. javacard/framework/Applet. */
    public String externalSuperName;

    public int totalInstanceSize = -1;
    public String label;

    public ClassRt(LoadedPackage pkg, CapPackage.ClassInfo info) {
        this.pkg = pkg;
        this.info = info;
        this.offset = info.offset;
        this.label = pkg.name() + "!class@" + info.offset;
    }

    public boolean isInterface() {
        return info.isInterface;
    }

    /** Number of 16 bit cells an instance of this class needs. */
    public int instanceSize() {
        if (totalInstanceSize < 0) {
            int sup = 0;
            if (superClass != null) {
                sup = superClass.instanceSize();
            } else if (externalSuperName != null) {
                sup = pkg.vmInstanceSizeOf(externalSuperName);
            }
            totalInstanceSize = sup + info.declaredInstanceSize;
        }
        return totalInstanceSize;
    }

    /** True when a field token addresses a reference cell in this class. */
    public boolean isReferenceToken(int token) {
        ClassRt c = this;
        while (c != null) {
            if (c.info.referenceCount > 0
                    && token >= c.info.firstReferenceToken
                    && token < c.info.firstReferenceToken + c.info.referenceCount) {
                return true;
            }
            c = c.superClass;
        }
        return false;
    }

    /**
     * Virtual dispatch. The token is interpreted in the public method token
     * space unless its high bit is set, which selects the package-private space.
     */
    public VirtualTarget lookupVirtual(int rawToken) {
        boolean packageScope = (rawToken & 0x80) != 0;
        int token = packageScope ? (rawToken & 0x7F) : rawToken;
        ClassRt c = this;
        while (c != null) {
            int base = packageScope ? c.info.packageMethodTableBase : c.info.publicMethodTableBase;
            int[] table = packageScope ? c.info.packageMethodTable : c.info.publicMethodTable;
            int idx = token - base;
            if (idx >= 0 && idx < table.length) {
                int off = table[idx];
                if (off != 0xFFFF) {
                    VirtualTarget t = new VirtualTarget();
                    t.method = c.pkg.method(off);
                    t.token = rawToken;
                    return t;
                }
            }
            if (c.superClass != null) {
                c = c.superClass;
            } else if (c.externalSuperName != null) {
                VirtualTarget t = new VirtualTarget();
                t.externalClass = c.externalSuperName;
                t.token = rawToken;
                return t;
            } else {
                c = null;
            }
        }
        throw new JCThrow("java/lang/Error",
                "no implementation for virtual token " + rawToken + " in " + label);
    }

    /** Maps an interface method token to this class' virtual token. */
    public int interfaceToken(ClassRt iface, String externalIfaceName, int ifaceMethodToken) {
        ClassRt c = this;
        while (c != null) {
            CapPackage.InterfaceImpl[] impls = c.info.interfaces;
            for (int i = 0; i < impls.length; i++) {
                CapPackage.InterfaceImpl im = impls[i];
                boolean match;
                if (im.external) {
                    String n = c.pkg.externalClassName(im.packageToken, im.classToken);
                    match = externalIfaceName != null && externalIfaceName.equals(n);
                } else {
                    match = iface != null && iface.pkg == c.pkg && iface.offset == im.offset;
                }
                if (match) {
                    if (ifaceMethodToken < im.methodTokens.length) {
                        return im.methodTokens[ifaceMethodToken];
                    }
                }
            }
            c = c.superClass;
        }
        return -1;
    }

    /** instanceof / checkcast against another loaded class. */
    public boolean isSubclassOf(ClassRt other) {
        ClassRt c = this;
        while (c != null) {
            if (c == other) {
                return true;
            }
            if (c.implementsInterface(other)) {
                return true;
            }
            c = c.superClass;
        }
        return false;
    }

    private boolean implementsInterface(ClassRt iface) {
        if (!iface.isInterface()) {
            return false;
        }
        CapPackage.InterfaceImpl[] impls = info.interfaces;
        for (int i = 0; i < impls.length; i++) {
            if (!impls[i].external && impls[i].offset == iface.offset && iface.pkg == pkg) {
                return true;
            }
        }
        return false;
    }

    /** instanceof / checkcast against a natively implemented class. */
    public boolean isSubclassOf(String externalName) {
        ClassRt c = this;
        while (c != null) {
            if (c.externalSuperName != null) {
                return BuiltinObject.isAssignable(c.externalSuperName, externalName);
            }
            c = c.superClass;
        }
        return "java/lang/Object".equals(externalName);
    }

    public String toString() {
        return label;
    }
}
