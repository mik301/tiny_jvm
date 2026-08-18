package jcvm.cap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jcvm.util.ByteReader;
import jcvm.util.Hex;

/**
 * A decoded CAP file: the components that matter to the interpreter are turned
 * into objects, the rest are kept as raw bytes.
 *
 * Offset conventions follow the JCVM spec:
 *  - class refs and method refs are offsets into the *info* part of the Class
 *    resp. Method component (i.e. after the 3 byte tag/size prefix);
 *  - static field refs are offsets into the static field image.
 */
public final class CapPackage {

    /* ---------------- constant pool tags ---------------- */
    public static final int CP_CLASSREF = 1;
    public static final int CP_INSTANCE_FIELDREF = 2;
    public static final int CP_VIRTUAL_METHODREF = 3;
    public static final int CP_SUPER_METHODREF = 4;
    public static final int CP_STATIC_FIELDREF = 5;
    public static final int CP_STATIC_METHODREF = 6;

    /* ---------------- class flags ---------------- */
    public static final int ACC_INTERFACE = 0x8;
    public static final int ACC_SHAREABLE = 0x4;
    public static final int ACC_REMOTE = 0x2;

    /* =================================================================== */

    /** A package identity: AID plus the package's own version. */
    public static final class PackageInfo {

        public byte[] aid;
        public int major;
        public int minor;
        public String name; // may be null (only present from CAP 2.2)

        public String aidHex() {
            return Hex.toHex(aid);
        }

        public String toString() {
            return (name != null ? name + " " : "") + aidHex() + " v" + major + "." + minor;
        }
    }

    /** One entry of the Applet component. */
    public static final class AppletInfo {

        public byte[] aid;
        public int installMethodOffset;

        public String toString() {
            return Hex.toHex(aid) + " install@" + installMethodOffset;
        }
    }

    /**
     * One constant pool entry, already split into its parts.
     * For internal references {@code external} is false and {@code offset} is
     * meaningful; for external references {@code packageToken}/{@code classToken}
     * identify the class in an imported package.
     */
    public static final class CpEntry {

        public int tag;
        public boolean external;
        public int offset;        // internal: class/method/static-field offset
        public int packageToken;  // external only
        public int classToken;    // external only
        public int token;         // field/method token (tags 2..6)

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("cp{tag=").append(tag);
            if (external) {
                sb.append(" ext pkg=").append(packageToken).append(" cls=").append(classToken);
            } else {
                sb.append(" int off=").append(offset);
            }
            if (tag >= CP_INSTANCE_FIELDREF) {
                sb.append(" tok=").append(token);
            }
            return sb.append('}').toString();
        }
    }

    /** An entry of a class' implemented-interface table. */
    public static final class InterfaceImpl {

        public boolean external;
        public int offset;        // internal interface offset
        public int packageToken;
        public int classToken;
        public int[] methodTokens; // interface method token -> class virtual token
    }

    /** interface_info or class_info from the Class component. */
    public static final class ClassInfo {

        public int offset;              // offset inside the Class component info
        public boolean isInterface;
        public int flags;

        /* interfaces only */
        public int[] superInterfaceRefs; // raw class_ref words

        /* classes only */
        public boolean superExternal;
        public boolean hasSuper;
        public int superOffset;
        public int superPackageToken;
        public int superClassToken;

        public int declaredInstanceSize;
        public int firstReferenceToken;
        public int referenceCount;
        public int publicMethodTableBase;
        public int packageMethodTableBase;
        public int[] publicMethodTable = new int[0];
        public int[] packageMethodTable = new int[0];
        public InterfaceImpl[] interfaces = new InterfaceImpl[0];

        public String toString() {
            return (isInterface ? "interface@" : "class@") + offset;
        }
    }

    /** One entry of the Method component's exception handler table. */
    public static final class ExceptionHandler {

        public int startOffset;
        public boolean stopBit;
        public int activeLength;
        public int handlerOffset;
        public int catchTypeIndex; // constant pool index, 0 == catch anything
    }

    /**
     * One entry of the Export component. A class' export token is its index in
     * this table, which is how another package's external class refs address it.
     */
    public static final class ClassExport {

        public int classOffset;
        public int[] staticFieldOffsets = new int[0];
        public int[] staticMethodOffsets = new int[0];
    }

    /** Static array initialiser from the Static Field component. */
    public static final class ArrayInit {

        public int type; // 2=boolean 3=byte 4=short 5=int (JCVM array types)
        public byte[] values;
    }

    /* =================================================================== */

    public final CapFile file;

    public int capMajor;
    public int capMinor;
    public int headerFlags;
    public PackageInfo thisPackage = new PackageInfo();

    public List<AppletInfo> applets = new ArrayList<AppletInfo>();
    public List<PackageInfo> imports = new ArrayList<PackageInfo>();
    public CpEntry[] constantPool = new CpEntry[0];

    public byte[] classInfoBytes = new byte[0];
    public Map<Integer, ClassInfo> classes = new HashMap<Integer, ClassInfo>();
    public List<ClassInfo> classList = new ArrayList<ClassInfo>();

    public byte[] methodInfoBytes = new byte[0];
    public List<ExceptionHandler> handlers = new ArrayList<ExceptionHandler>();

    public List<ClassExport> exports = new ArrayList<ClassExport>();

    public int staticImageSize;
    public int staticReferenceCount;
    public List<ArrayInit> arrayInits = new ArrayList<ArrayInit>();
    public int defaultValueCount;
    public byte[] nonDefaultValues = new byte[0];

    /* =================================================================== */

    private CapPackage(CapFile f) {
        this.file = f;
    }

    public static CapPackage parse(CapFile f) {
        CapPackage p = new CapPackage(f);
        p.parseHeader();
        p.parseApplet();
        p.parseImport();
        p.parseConstantPool();
        p.parseClasses();
        p.parseMethods();
        p.parseStaticField();
        p.parseExport();
        return p;
    }

    /* ---------------- Header ---------------- */

    private void parseHeader() {
        ByteReader r = new ByteReader(file.info(CapFile.COMPONENT_HEADER));
        long magic = r.u4();
        if (magic != 0xDECAFFEDL) {
            throw new CapFormatException("bad CAP magic 0x"
                    + Long.toHexString(magic) + " (expected DECAFFED)");
        }
        capMinor = r.u1();
        capMajor = r.u1();
        headerFlags = r.u1();
        thisPackage.minor = r.u1();
        thisPackage.major = r.u1();
        thisPackage.aid = r.bytes(r.u1());
        if (isAtLeast22() && r.hasMore()) {
            int nameLen = r.u1();
            if (nameLen > 0) {
                thisPackage.name = new String(r.bytes(nameLen));
            }
        }
    }

    public boolean isAtLeast22() {
        return capMajor > 2 || (capMajor == 2 && capMinor >= 2);
    }

    /* ---------------- Applet ---------------- */

    private void parseApplet() {
        byte[] info = file.info(CapFile.COMPONENT_APPLET);
        if (info == null) {
            return; // library package, nothing installable
        }
        ByteReader r = new ByteReader(info);
        int count = r.u1();
        for (int i = 0; i < count; i++) {
            AppletInfo a = new AppletInfo();
            a.aid = r.bytes(r.u1());
            a.installMethodOffset = r.u2();
            applets.add(a);
        }
    }

    /* ---------------- Import ---------------- */

    private void parseImport() {
        byte[] info = file.info(CapFile.COMPONENT_IMPORT);
        if (info == null) {
            return;
        }
        ByteReader r = new ByteReader(info);
        int count = r.u1();
        for (int i = 0; i < count; i++) {
            PackageInfo pi = new PackageInfo();
            pi.minor = r.u1();
            pi.major = r.u1();
            pi.aid = r.bytes(r.u1());
            imports.add(pi);
        }
    }

    /* ---------------- Constant Pool ---------------- */

    private void parseConstantPool() {
        ByteReader r = new ByteReader(file.info(CapFile.COMPONENT_CONSTANT_POOL));
        int count = r.u2();
        constantPool = new CpEntry[count];
        for (int i = 0; i < count; i++) {
            CpEntry e = new CpEntry();
            e.tag = r.u1();
            int b0 = r.u1();
            int b1 = r.u1();
            int b2 = r.u1();
            switch (e.tag) {
                case CP_CLASSREF: {
                    // class_ref (2 bytes) + 1 padding byte
                    decodeClassRef(e, b0, b1);
                    break;
                }
                case CP_INSTANCE_FIELDREF:
                case CP_VIRTUAL_METHODREF:
                case CP_SUPER_METHODREF: {
                    decodeClassRef(e, b0, b1);
                    e.token = b2;
                    break;
                }
                case CP_STATIC_FIELDREF:
                case CP_STATIC_METHODREF: {
                    if ((b0 & 0x80) != 0) {
                        e.external = true;
                        e.packageToken = b0 & 0x7F;
                        e.classToken = b1;
                        e.token = b2;
                    } else {
                        // b0 is padding, b1/b2 form the offset
                        e.external = false;
                        e.offset = (b1 << 8) | b2;
                    }
                    break;
                }
                default:
                    throw new CapFormatException("unknown constant pool tag "
                            + e.tag + " at index " + i);
            }
            constantPool[i] = e;
        }
    }

    /**
     * A class_ref is either an internal offset into the Class component or, when
     * the high bit of the first byte is set, a (package token, class token) pair.
     */
    private static void decodeClassRef(CpEntry e, int b0, int b1) {
        if ((b0 & 0x80) != 0) {
            e.external = true;
            e.packageToken = b0 & 0x7F;
            e.classToken = b1;
        } else {
            e.external = false;
            e.offset = (b0 << 8) | b1;
        }
    }

    /* ---------------- Class ---------------- */

    private void parseClasses() {
        classInfoBytes = file.info(CapFile.COMPONENT_CLASS);
        ByteReader r = new ByteReader(classInfoBytes);
        if (isAtLeast22()) {
            int sigPoolLen = r.u2();
            r.skip(sigPoolLen);
        }
        while (r.hasMore()) {
            int start = r.pos();
            if (r.remaining() < 2) {
                break;
            }
            ClassInfo ci = new ClassInfo();
            ci.offset = start;
            int bitfield = r.u1();
            ci.flags = (bitfield >> 4) & 0x0F;
            int interfaceCount = bitfield & 0x0F;
            ci.isInterface = (ci.flags & ACC_INTERFACE) != 0;

            if (ci.isInterface) {
                ci.superInterfaceRefs = new int[interfaceCount];
                for (int i = 0; i < interfaceCount; i++) {
                    ci.superInterfaceRefs[i] = r.u2();
                }
                if (isAtLeast22() && (ci.flags & ACC_REMOTE) != 0) {
                    int nameLen = r.u1();
                    r.skip(nameLen);
                }
            } else {
                int sup0 = r.u1();
                int sup1 = r.u1();
                if (sup0 == 0xFF && sup1 == 0xFF) {
                    ci.hasSuper = false;
                } else {
                    ci.hasSuper = true;
                    if ((sup0 & 0x80) != 0) {
                        ci.superExternal = true;
                        ci.superPackageToken = sup0 & 0x7F;
                        ci.superClassToken = sup1;
                    } else {
                        ci.superOffset = (sup0 << 8) | sup1;
                    }
                }
                ci.declaredInstanceSize = r.u1();
                ci.firstReferenceToken = r.u1();
                ci.referenceCount = r.u1();
                ci.publicMethodTableBase = r.u1();
                int publicCount = r.u1();
                ci.packageMethodTableBase = r.u1();
                int packageCount = r.u1();
                ci.publicMethodTable = new int[publicCount];
                for (int i = 0; i < publicCount; i++) {
                    ci.publicMethodTable[i] = r.u2();
                }
                ci.packageMethodTable = new int[packageCount];
                for (int i = 0; i < packageCount; i++) {
                    ci.packageMethodTable[i] = r.u2();
                }
                ci.interfaces = new InterfaceImpl[interfaceCount];
                for (int i = 0; i < interfaceCount; i++) {
                    InterfaceImpl ii = new InterfaceImpl();
                    int c0 = r.u1();
                    int c1 = r.u1();
                    if ((c0 & 0x80) != 0) {
                        ii.external = true;
                        ii.packageToken = c0 & 0x7F;
                        ii.classToken = c1;
                    } else {
                        ii.offset = (c0 << 8) | c1;
                    }
                    int n = r.u1();
                    ii.methodTokens = new int[n];
                    for (int j = 0; j < n; j++) {
                        ii.methodTokens[j] = r.u1();
                    }
                    ci.interfaces[i] = ii;
                }
                if (isAtLeast22() && (ci.flags & ACC_REMOTE) != 0) {
                    // remote_interface_info / remote_method_table: skipped,
                    // RMI is not supported by this VM.
                    throw new CapFormatException(
                            "Java Card RMI classes are not supported (class@" + start + ")");
                }
            }
            classes.put(Integer.valueOf(start), ci);
            classList.add(ci);
        }
    }

    public ClassInfo classAt(int offset) {
        ClassInfo ci = classes.get(Integer.valueOf(offset));
        if (ci == null) {
            throw new CapFormatException("no class at Class component offset " + offset);
        }
        return ci;
    }

    /* ---------------- Method ---------------- */

    private void parseMethods() {
        methodInfoBytes = file.info(CapFile.COMPONENT_METHOD);
        ByteReader r = new ByteReader(methodInfoBytes);
        int handlerCount = r.u1();
        for (int i = 0; i < handlerCount; i++) {
            ExceptionHandler h = new ExceptionHandler();
            h.startOffset = r.u2();
            int bf = r.u2();
            h.stopBit = (bf & 0x8000) != 0;
            h.activeLength = bf & 0x7FFF;
            h.handlerOffset = r.u2();
            h.catchTypeIndex = r.u2();
            handlers.add(h);
        }
    }

    /* ---------------- Static Field ---------------- */

    private void parseStaticField() {
        byte[] info = file.info(CapFile.COMPONENT_STATIC_FIELD);
        if (info == null) {
            return;
        }
        ByteReader r = new ByteReader(info);
        staticImageSize = r.u2();
        staticReferenceCount = r.u2();
        int arrayInitCount = r.u2();
        for (int i = 0; i < arrayInitCount; i++) {
            ArrayInit ai = new ArrayInit();
            ai.type = r.u1();
            int count = r.u2();
            ai.values = r.bytes(count);
            arrayInits.add(ai);
        }
        defaultValueCount = r.u2();
        int nonDefaultCount = r.u2();
        nonDefaultValues = r.bytes(nonDefaultCount);
    }

    /* ---------------- Export ---------------- */

    private void parseExport() {
        byte[] info = file.info(CapFile.COMPONENT_EXPORT);
        if (info == null) {
            return;
        }
        ByteReader r = new ByteReader(info);
        int count = r.u1();
        for (int i = 0; i < count; i++) {
            ClassExport ce = new ClassExport();
            ce.classOffset = r.u2();
            int fieldCount = r.u1();
            int methodCount = r.u1();
            ce.staticFieldOffsets = new int[fieldCount];
            for (int j = 0; j < fieldCount; j++) {
                ce.staticFieldOffsets[j] = r.u2();
            }
            ce.staticMethodOffsets = new int[methodCount];
            for (int j = 0; j < methodCount; j++) {
                ce.staticMethodOffsets[j] = r.u2();
            }
            exports.add(ce);
        }
    }

    /* =================================================================== */

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("CAP ").append(capMajor).append('.').append(capMinor)
                .append("  package ").append(thisPackage).append('\n');
        sb.append("  imports      : ").append(imports.size()).append('\n');
        for (int i = 0; i < imports.size(); i++) {
            sb.append("      [").append(i).append("] ").append(imports.get(i)).append('\n');
        }
        sb.append("  constant pool: ").append(constantPool.length).append(" entries\n");
        sb.append("  classes      : ").append(classList.size()).append('\n');
        sb.append("  method bytes : ").append(methodInfoBytes.length).append('\n');
        sb.append("  handlers     : ").append(handlers.size()).append('\n');
        sb.append("  static image : ").append(staticImageSize)
                .append(" bytes, ").append(staticReferenceCount).append(" refs\n");
        sb.append("  applets      : ").append(applets.size()).append('\n');
        for (int i = 0; i < applets.size(); i++) {
            sb.append("      [").append(i).append("] ").append(applets.get(i)).append('\n');
        }
        return sb.toString();
    }
}
