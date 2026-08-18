package jcvm.cap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds the raw bytes of every component of one CAP file, keyed by component tag.
 *
 * Three input shapes are supported:
 *   1. a normal .cap file (a JAR/ZIP holding &lt;pkg&gt;/javacard/*.cap members),
 *   2. an "exploded" directory containing the individual *.cap component files,
 *   3. a raw Load File Data Block, i.e. the components simply concatenated
 *      (this is what a GlobalPlatform LOAD sequence delivers to the card).
 *
 * Each stored value is the FULL component including its 3 byte tag/size prefix.
 */
public final class CapFile {

    /* Component tags, JCVM spec table 6-1. */
    public static final int COMPONENT_HEADER = 1;
    public static final int COMPONENT_DIRECTORY = 2;
    public static final int COMPONENT_APPLET = 3;
    public static final int COMPONENT_IMPORT = 4;
    public static final int COMPONENT_CONSTANT_POOL = 5;
    public static final int COMPONENT_CLASS = 6;
    public static final int COMPONENT_METHOD = 7;
    public static final int COMPONENT_STATIC_FIELD = 8;
    public static final int COMPONENT_REFERENCE_LOCATION = 9;
    public static final int COMPONENT_EXPORT = 10;
    public static final int COMPONENT_DESCRIPTOR = 11;
    public static final int COMPONENT_DEBUG = 12;

    private static final String[] NAMES = {
        "?", "Header", "Directory", "Applet", "Import", "ConstantPool", "Class",
        "Method", "StaticField", "RefLocation", "Export", "Descriptor", "Debug"
    };

    private final Map<Integer, byte[]> components = new LinkedHashMap<Integer, byte[]>();
    private String source = "<memory>";

    public static String componentName(int tag) {
        return (tag >= 0 && tag < NAMES.length) ? NAMES[tag] : ("Custom-" + tag);
    }

    public String source() {
        return source;
    }

    public byte[] component(int tag) {
        return components.get(Integer.valueOf(tag));
    }

    public boolean has(int tag) {
        return components.containsKey(Integer.valueOf(tag));
    }

    /** Component body, i.e. the bytes after the 3 byte tag/size header. */
    public byte[] info(int tag) {
        byte[] full = component(tag);
        if (full == null) {
            return null;
        }
        byte[] out = new byte[full.length - 3];
        System.arraycopy(full, 3, out, 0, out.length);
        return out;
    }

    public Map<Integer, byte[]> all() {
        return components;
    }

    /* ------------------------------------------------------------------ */

    public static CapFile load(File f) throws IOException {
        CapFile cap;
        if (f.isDirectory()) {
            cap = fromDirectory(f);
        } else {
            byte[] raw = readAll(f);
            if (raw.length > 4 && (raw[0] & 0xFF) == 'P' && (raw[1] & 0xFF) == 'K') {
                cap = fromZip(f);
            } else {
                cap = fromComponentStream(raw);
            }
        }
        cap.source = f.getPath();
        cap.validate();
        return cap;
    }

    public static CapFile fromZip(File f) throws IOException {
        CapFile cap = new CapFile();
        ZipFile zf = new ZipFile(f);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                if (!n.toLowerCase().endsWith(".cap")) {
                    continue;
                }
                InputStream in = zf.getInputStream(e);
                byte[] body;
                try {
                    body = readAll(in);
                } finally {
                    in.close();
                }
                cap.addComponent(body, n);
            }
        } finally {
            zf.close();
        }
        return cap;
    }

    public static CapFile fromDirectory(File dir) throws IOException {
        CapFile cap = new CapFile();
        collect(dir, cap);
        return cap;
    }

    private static void collect(File dir, CapFile cap) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            if (k.isDirectory()) {
                collect(k, cap);
            } else if (k.getName().toLowerCase().endsWith(".cap")) {
                cap.addComponent(readAll(k), k.getName());
            }
        }
    }

    /** Parses components laid end to end, as delivered by GP LOAD. */
    public static CapFile fromComponentStream(byte[] raw) {
        CapFile cap = new CapFile();
        int p = 0;
        while (p + 3 <= raw.length) {
            int tag = raw[p] & 0xFF;
            int size = ((raw[p + 1] & 0xFF) << 8) | (raw[p + 2] & 0xFF);
            int total = size + 3;
            if (tag == 0 || p + total > raw.length) {
                break;
            }
            byte[] comp = new byte[total];
            System.arraycopy(raw, p, comp, 0, total);
            cap.components.put(Integer.valueOf(tag), comp);
            p += total;
        }
        return cap;
    }

    private void addComponent(byte[] body, String entryName) {
        if (body.length < 3) {
            return;
        }
        int tag = body[0] & 0xFF;
        int size = ((body[1] & 0xFF) << 8) | (body[2] & 0xFF);
        if (size + 3 != body.length) {
            // Tolerate trailing padding but not a truncated component.
            if (size + 3 > body.length) {
                throw new CapFormatException("component " + entryName
                        + " declares " + size + " bytes but only "
                        + (body.length - 3) + " are present");
            }
            byte[] trimmed = new byte[size + 3];
            System.arraycopy(body, 0, trimmed, 0, trimmed.length);
            body = trimmed;
        }
        components.put(Integer.valueOf(tag), body);
    }

    private void validate() {
        int[] required = {COMPONENT_HEADER, COMPONENT_METHOD, COMPONENT_CLASS,
            COMPONENT_CONSTANT_POOL};
        for (int i = 0; i < required.length; i++) {
            if (!has(required[i])) {
                throw new CapFormatException("CAP is missing the "
                        + componentName(required[i]) + " component");
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
