package jcvm.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import jcvm.util.ByteReader;
import jcvm.util.Hex;

/**
 * Reads Java Card export files (*.exp).
 *
 * This is what makes real, converter produced CAP files work: a CAP addresses
 * API classes and methods by numeric token, and the authoritative token
 * assignment lives in the export files of the SDK that converted it. Reading
 * them removes all guesswork - the VM binds "class token 3, virtual token 4"
 * to the name the export file gives it, and that name is looked up in
 * {@link Natives}.
 *
 * Export file layout (JCVM specification, Export File Format):
 *
 * <pre>
 * export_file {
 *     u4 magic                     0x00FACADE
 *     u1 minor_version
 *     u1 major_version
 *     u2 constant_pool_count
 *     cp_info constant_pool[constant_pool_count]
 *     u1 this_package              index of a CONSTANT_Package entry
 *     u1 export_class_count
 *     class_info classes[export_class_count]
 * }
 * </pre>
 */
public final class ExpReader {

    public static final long MAGIC = 0x00FACADEL;

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_CLASSREF = 7;
    private static final int CONSTANT_PACKAGE = 13;

    private static final int ACC_STATIC = 0x0008;

    /** Constant pool of the export file being read. */
    private final Object[] pool;
    private final int[] poolTag;
    private final ByteReader r;
    private final String origin;

    public int majorVersion;
    public int minorVersion;

    private ExpReader(byte[] data, String origin) {
        this.r = new ByteReader(data);
        this.origin = origin;
        long magic = r.u4();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(origin + ": not an export file"
                    + " (magic 0x" + Long.toHexString(magic) + ", expected 00FACADE)");
        }
        minorVersion = r.u1();
        majorVersion = r.u1();
        int count = r.u2();
        pool = new Object[count];
        poolTag = new int[count];
        for (int i = 0; i < count; i++) {
            readConstant(i);
        }
        this.afterPool = r.pos();
    }

    private int afterPool;

    private void readConstant(int index) {
        int tag = r.u1();
        poolTag[index] = tag;
        switch (tag) {
            case CONSTANT_UTF8: {
                int len = r.u2();
                pool[index] = new String(r.bytes(len));
                break;
            }
            case CONSTANT_INTEGER:
                pool[index] = Long.valueOf(r.u4());
                break;
            case CONSTANT_CLASSREF:
                pool[index] = Integer.valueOf(r.u2()); // -> Utf8 index
                break;
            case CONSTANT_PACKAGE: {
                r.u1();                       // flags
                int nameIndex = r.u2();
                int minor = r.u1();
                int major = r.u1();
                byte[] aid = r.bytes(r.u1());
                pool[index] = new Object[]{Integer.valueOf(nameIndex), aid,
                    Integer.valueOf(major), Integer.valueOf(minor)};
                break;
            }
            default:
                throw new IllegalArgumentException(origin + ": unknown constant pool tag "
                        + tag + " at index " + index + " (offset " + r.pos() + ")");
        }
    }

    private String utf8(int index) {
        if (index < 0 || index >= pool.length) {
            throw new IllegalArgumentException(origin + ": constant pool index "
                    + index + " out of range");
        }
        if (poolTag[index] == CONSTANT_UTF8) {
            return (String) pool[index];
        }
        if (poolTag[index] == CONSTANT_CLASSREF) {
            return utf8(((Integer) pool[index]).intValue());
        }
        throw new IllegalArgumentException(origin + ": constant pool entry " + index
                + " is tag " + poolTag[index] + ", expected a name");
    }

    /* ------------------------------------------------------------------ */

    /** Parses one export file into an ApiPackage. */
    public static ApiPackage read(File f) throws IOException {
        return read(readAll(f), f.getName());
    }

    public static ApiPackage read(byte[] data, String origin) {
        ExpReader e = new ExpReader(data, origin);
        ApiPackage p = e.parse();
        lastLayout = e.layout;
        lastVersion = e.majorVersion + "." + e.minorVersion;
        return p;
    }

    /** Header layout and format version of the most recently read file. */
    public static String lastLayout = "?";
    public static String lastVersion = "?";

    /**
     * The header between the constant pool and the class table varies with the
     * export file version: this_package is one or two bytes and some versions
     * carry a referenced package list. Rather than guess, try each layout and
     * keep the one that parses the file exactly to its end.
     */
    private ApiPackage parse() {
        String[] names = {
            "this_package u2, no referenced packages",
            "this_package u1, no referenced packages",
            "this_package u2, referenced package list",
            "this_package u1, referenced package list"
        };
        boolean[] wide = {true, false, true, false};
        boolean[] refs = {false, false, true, true};

        StringBuilder failures = new StringBuilder();
        for (int v = 0; v < names.length; v++) {
            r.seek(afterPool);
            try {
                ApiPackage p = tryParse(wide[v], refs[v]);
                layout = names[v];
                return p;
            } catch (RuntimeException e) {
                failures.append("\n    ").append(names[v]).append(": ")
                        .append(e);
            }
        }
        throw new IllegalArgumentException(origin
                + ": could not parse the class table (export file format "
                + majorVersion + "." + minorVersion + ")."
                + " Tried:" + failures
                + "\n  bytes after the constant pool: " + peekAfterPool(24));
    }

    /** Which header layout matched, for reporting. */
    public String layout = "?";

    private String peekAfterPool(int n) {
        int end = Math.min(afterPool + n, r.data().length);
        byte[] slice = new byte[end - afterPool];
        System.arraycopy(r.data(), afterPool, slice, 0, slice.length);
        return Hex.toSpaced(slice);
    }

    private ApiPackage tryParse(boolean wideThisPackage, boolean hasReferencedPackages) {
        int thisPackage = wideThisPackage ? r.u2() : r.u1();
        if (thisPackage < 0 || thisPackage >= pool.length
                || poolTag[thisPackage] != CONSTANT_PACKAGE) {
            throw new IllegalArgumentException("this_package=" + thisPackage
                    + " is not a CONSTANT_Package entry");
        }
        if (hasReferencedPackages) {
            int refCount = r.u1();
            for (int i = 0; i < refCount; i++) {
                r.u2();
            }
        }

        Object[] pkg = (Object[]) pool[thisPackage];
        String pkgName = utf8(((Integer) pkg[0]).intValue()).replace('/', '.');
        byte[] aid = (byte[]) pkg[1];

        ApiPackage out = new ApiPackage(pkgName, aid);
        out.major = ((Integer) pkg[2]).intValue();
        out.minor = ((Integer) pkg[3]).intValue();

        int classCount = r.u1();
        if (classCount > 255 || classCount < 0) {
            throw new IllegalArgumentException("implausible class count " + classCount);
        }
        for (int i = 0; i < classCount; i++) {
            readClass(out);
        }
        if (r.remaining() != 0) {
            throw new IllegalArgumentException(r.remaining()
                    + " trailing bytes after " + classCount + " classes");
        }
        return out;
    }

    private void readClass(ApiPackage pkg) {
        int token = r.u1();
        r.u2();                                   // access_flags
        String name = utf8(r.u2());
        int superCount = r.u2();
        if (superCount > 4096) {
            throw new IllegalArgumentException("implausible super count " + superCount);
        }
        for (int i = 0; i < superCount; i++) {
            r.u2();
        }
        int ifaceCount = r.u1();
        for (int i = 0; i < ifaceCount; i++) {
            r.u2();
        }

        ApiClass cls = new ApiClass(pkg, token, name);

        int fieldCount = r.u2();
        if (fieldCount > 4096) {
            throw new IllegalArgumentException("implausible field count " + fieldCount);
        }
        for (int i = 0; i < fieldCount; i++) {
            r.u1();                               // field token
            r.u2();                               // access_flags
            r.u2();                               // name index
            r.u2();                               // descriptor index
            int attrCount = r.u2();
            for (int j = 0; j < attrCount; j++) {
                r.u2();                           // attribute name index
                long len = r.u4();
                r.skip((int) len);
            }
        }

        int methodCount = r.u2();
        if (methodCount > 4096) {
            throw new IllegalArgumentException("implausible method count " + methodCount);
        }
        for (int i = 0; i < methodCount; i++) {
            int mToken = r.u1();
            int flags = r.u2();
            String mName = utf8(r.u2());
            String descriptor = utf8(r.u2());
            String signature = mName + descriptor;
            boolean isStatic = (flags & ACC_STATIC) != 0 || "<init>".equals(mName);
            if (isStatic) {
                cls.staticMethods.put(Integer.valueOf(mToken), signature);
            } else {
                cls.virtualMethods.put(Integer.valueOf(mToken), signature);
            }
        }

        pkg.add(cls);
    }

    /* ------------------------------------------------------------------ */

    /**
     * Loads every *.exp under a directory into the registry. Point this at the
     * SDK's api_export_files directory.
     */
    public static List<ApiPackage> loadDirectory(File dir, ApiRegistry registry)
            throws IOException {
        List<File> found = new ArrayList<File>();
        collect(dir, found);
        List<ApiPackage> loaded = new ArrayList<ApiPackage>();
        for (int i = 0; i < found.size(); i++) {
            ApiPackage p = read(found.get(i));
            registry.add(p);
            loaded.add(p);
        }
        return loaded;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isDirectory()) {
                collect(kids[i], out);
            } else if (kids[i].getName().toLowerCase().endsWith(".exp")) {
                out.add(kids[i]);
            }
        }
    }

    /* ------------------------------------------------------------------ */

    /** Renders a package in the api-tokens.txt format. */
    public static String toTokenTable(ApiPackage p) {
        StringBuilder sb = new StringBuilder();
        sb.append("PKG ").append(p.name).append(' ').append(Hex.toHex(p.aid))
                .append(' ').append(p.major).append(' ').append(p.minor).append('\n');
        List<Integer> tokens = new ArrayList<Integer>(p.byToken.keySet());
        java.util.Collections.sort(tokens);
        for (int i = 0; i < tokens.size(); i++) {
            ApiClass c = p.byToken.get(tokens.get(i));
            sb.append("CLASS ").append(c.token).append(' ').append(c.name)
                    .append(" SIZE ").append(c.instanceSize).append('\n');
            appendMethods(sb, "  V ", c.virtualMethods);
            appendMethods(sb, "  S ", c.staticMethods);
        }
        return sb.toString();
    }

    private static void appendMethods(StringBuilder sb, String prefix,
            java.util.Map<Integer, String> methods) {
        List<Integer> tokens = new ArrayList<Integer>(methods.keySet());
        java.util.Collections.sort(tokens);
        for (int i = 0; i < tokens.size(); i++) {
            sb.append(prefix).append(tokens.get(i)).append(' ')
                    .append(methods.get(tokens.get(i))).append('\n');
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
