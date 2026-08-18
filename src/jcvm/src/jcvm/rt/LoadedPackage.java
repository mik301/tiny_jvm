package jcvm.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jcvm.api.ApiClass;
import jcvm.api.ApiPackage;
import jcvm.cap.CapPackage;
import jcvm.util.Hex;

/** A CAP that has been parsed, linked and given a static field image. */
public final class LoadedPackage {

    public final CapPackage cap;
    public final byte[] code;
    public final byte[] aid;

    private final PackageResolver resolver;
    private final Map<Integer, ClassRt> classes = new HashMap<Integer, ClassRt>();
    private final Map<Integer, MethodRt> methodCache = new HashMap<Integer, MethodRt>();

    /** One entry per Import component entry: a LoadedPackage or an ApiPackage. */
    public Object[] imports;

    public byte[] staticData = new byte[0];
    public Object[] staticRefs = new Object[0];

    public LoadedPackage(CapPackage cap, PackageResolver resolver) {
        this.cap = cap;
        this.code = cap.methodInfoBytes;
        this.aid = cap.thisPackage.aid;
        this.resolver = resolver;
    }

    public String name() {
        return cap.thisPackage.name != null ? cap.thisPackage.name : Hex.toHex(aid);
    }

    /* ------------------------------------------------------------------ */
    /* linking                                                            */
    /* ------------------------------------------------------------------ */

    public void link() {
        linkImports();
        buildClasses();
        buildStaticImage();
    }

    /**
     * Re-resolves the Import component against the current token tables.
     *
     * The imports array holds the ApiPackage objects captured when this package
     * was linked, so loading export files afterwards would otherwise leave an
     * already-loaded CAP resolving against the old tables.
     */
    public void relinkImports() {
        linkImports();
        for (ClassRt c : classes.values()) {
            CapPackage.ClassInfo ci = c.info;
            if (ci.isInterface || !ci.hasSuper || !ci.superExternal) {
                continue;
            }
            Object owner = importAt(ci.superPackageToken);
            if (owner instanceof ApiPackage) {
                ApiClass ac = ((ApiPackage) owner).classByToken(ci.superClassToken);
                if (ac != null) {
                    c.externalSuperName = ac.name;
                }
            }
        }
    }

    private void linkImports() {
        int n = cap.imports.size();
        imports = new Object[n];
        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            byte[] a = cap.imports.get(i).aid;
            Object target = resolver.findLoaded(a);
            if (target == null) {
                target = resolver.findApi(a);
            }
            if (target == null) {
                missing.add(Hex.toHex(a));
            }
            imports[i] = target;
        }
        if (!missing.isEmpty()) {
            throw new LinkException("cannot resolve imported package(s) " + missing
                    + " - load the library CAP first, or add the package to api-tokens.txt");
        }
    }

    private void buildClasses() {
        for (int i = 0; i < cap.classList.size(); i++) {
            CapPackage.ClassInfo ci = cap.classList.get(i);
            ClassRt c = new ClassRt(this, ci);
            classes.put(Integer.valueOf(ci.offset), c);
        }
        // second pass: superclasses
        for (ClassRt c : classes.values()) {
            CapPackage.ClassInfo ci = c.info;
            if (ci.isInterface || !ci.hasSuper) {
                continue;
            }
            if (ci.superExternal) {
                Object owner = importAt(ci.superPackageToken);
                if (owner instanceof ApiPackage) {
                    ApiClass ac = ((ApiPackage) owner).classByToken(ci.superClassToken);
                    if (ac == null) {
                        throw new LinkException("unknown API class token "
                                + ci.superClassToken + " in package "
                                + ((ApiPackage) owner).name
                                + " (superclass of " + c.label + ")");
                    }
                    c.externalSuperName = ac.name;
                } else {
                    LoadedPackage lp = (LoadedPackage) owner;
                    c.superClass = lp.classByExportToken(ci.superClassToken);
                }
            } else {
                c.superClass = classAt(ci.superOffset);
            }
        }
    }

    private void buildStaticImage() {
        staticData = new byte[cap.staticImageSize];
        staticRefs = new Object[cap.staticReferenceCount];
        // Category 1: reference fields initialised with an array.
        for (int i = 0; i < cap.arrayInits.size() && i < staticRefs.length; i++) {
            CapPackage.ArrayInit ai = cap.arrayInits.get(i);
            staticRefs[i] = makeInitialisedArray(ai);
        }
        // Category 4: primitive fields with non-default values live at the tail.
        byte[] nd = cap.nonDefaultValues;
        if (nd.length > 0) {
            int at = staticData.length - nd.length;
            if (at < 0) {
                throw new LinkException("static field image too small for its initialisers");
            }
            System.arraycopy(nd, 0, staticData, at, nd.length);
        }
    }

    private static JCArray makeInitialisedArray(CapPackage.ArrayInit ai) {
        int type;
        int elemSize;
        switch (ai.type) {
            case 2: type = JCArray.T_BOOLEAN; elemSize = 1; break;
            case 3: type = JCArray.T_BYTE; elemSize = 1; break;
            case 4: type = JCArray.T_SHORT; elemSize = 2; break;
            case 5: type = JCArray.T_INT; elemSize = 4; break;
            default:
                throw new LinkException("unsupported static array init type " + ai.type);
        }
        int count = ai.values.length / elemSize;
        JCArray arr = new JCArray(type, count);
        for (int i = 0; i < count; i++) {
            int v;
            if (elemSize == 1) {
                v = ai.values[i];
            } else if (elemSize == 2) {
                v = (short) (((ai.values[i * 2] & 0xFF) << 8) | (ai.values[i * 2 + 1] & 0xFF));
            } else {
                v = ((ai.values[i * 4] & 0xFF) << 24) | ((ai.values[i * 4 + 1] & 0xFF) << 16)
                        | ((ai.values[i * 4 + 2] & 0xFF) << 8) | (ai.values[i * 4 + 3] & 0xFF);
            }
            arr.setPrim(i, v);
        }
        return arr;
    }

    /* ------------------------------------------------------------------ */
    /* lookups                                                            */
    /* ------------------------------------------------------------------ */

    public Object importAt(int packageToken) {
        if (imports == null || packageToken >= imports.length) {
            throw new LinkException("import token " + packageToken
                    + " out of range in " + name());
        }
        return imports[packageToken];
    }

    public ClassRt classAt(int offset) {
        ClassRt c = classes.get(Integer.valueOf(offset));
        if (c == null) {
            throw new LinkException("no class at offset " + offset + " in " + name());
        }
        return c;
    }

    public java.util.Collection<ClassRt> allClasses() {
        return classes.values();
    }

    public ClassRt classByExportToken(int token) {
        if (token >= cap.exports.size()) {
            throw new LinkException("export token " + token + " out of range in " + name()
                    + " (does the CAP have an Export component?)");
        }
        return classAt(cap.exports.get(token).classOffset);
    }

    public MethodRt method(int offset) {
        Integer key = Integer.valueOf(offset);
        MethodRt m = methodCache.get(key);
        if (m == null) {
            m = new MethodRt(this, offset);
            methodCache.put(key, m);
        }
        return m;
    }

    /** Name of a natively implemented class referenced through the imports. */
    public String externalClassName(int packageToken, int classToken) {
        Object owner = importAt(packageToken);
        if (owner instanceof ApiPackage) {
            ApiClass ac = ((ApiPackage) owner).classByToken(classToken);
            return ac == null ? null : ac.name;
        }
        return null;
    }

    /** Class in another loaded CAP referenced through the imports, or null. */
    public ClassRt externalLoadedClass(int packageToken, int classToken) {
        Object owner = importAt(packageToken);
        if (owner instanceof LoadedPackage) {
            return ((LoadedPackage) owner).classByExportToken(classToken);
        }
        return null;
    }

    public ApiClass externalApiClass(int packageToken, int classToken) {
        Object owner = importAt(packageToken);
        if (owner instanceof ApiPackage) {
            return ((ApiPackage) owner).classByToken(classToken);
        }
        return null;
    }

    public int vmInstanceSizeOf(String internalClassName) {
        return resolver.instanceSizeOf(internalClassName);
    }

    /* ------------------------------------------------------------------ */
    /* static field access (offsets are byte offsets into the image)      */
    /* ------------------------------------------------------------------ */

    public int getStaticByte(int off) {
        return staticData[off];
    }

    public void setStaticByte(int off, int v) {
        staticData[off] = (byte) v;
    }

    public int getStaticShort(int off) {
        return (short) (((staticData[off] & 0xFF) << 8) | (staticData[off + 1] & 0xFF));
    }

    public void setStaticShort(int off, int v) {
        staticData[off] = (byte) (v >> 8);
        staticData[off + 1] = (byte) v;
    }

    public int getStaticInt(int off) {
        return ((staticData[off] & 0xFF) << 24) | ((staticData[off + 1] & 0xFF) << 16)
                | ((staticData[off + 2] & 0xFF) << 8) | (staticData[off + 3] & 0xFF);
    }

    public void setStaticInt(int off, int v) {
        staticData[off] = (byte) (v >> 24);
        staticData[off + 1] = (byte) (v >> 16);
        staticData[off + 2] = (byte) (v >> 8);
        staticData[off + 3] = (byte) v;
    }

    public Object getStaticRef(int off) {
        return staticRefs[off / 2];
    }

    public void setStaticRef(int off, Object v) {
        staticRefs[off / 2] = v;
    }

    public String toString() {
        return "package " + name();
    }
}
