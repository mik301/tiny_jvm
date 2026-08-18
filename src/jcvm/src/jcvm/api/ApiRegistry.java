package jcvm.api;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import jcvm.util.Hex;

/**
 * Holds the token tables for the natively implemented API packages.
 *
 * The tables are data driven on purpose: token assignments come from the export
 * files of whatever Java Card SDK produced your CAP, so they must be
 * configurable. The bundled api-tokens.txt matches the CAP files produced by
 * the built-in CapBuilder tool; replace or extend it to match your SDK.
 *
 * File format (whitespace separated, '#' starts a comment):
 *
 *   PKG   &lt;package.name&gt; &lt;AID hex&gt; &lt;major&gt; &lt;minor&gt;
 *   CLASS &lt;token&gt; &lt;internal/class/Name&gt; [SIZE &lt;cells&gt;]
 *   V     &lt;token&gt; &lt;methodName(descriptor)&gt;
 *   S     &lt;token&gt; &lt;methodName(descriptor)&gt;
 */
public final class ApiRegistry {

    private final Map<String, ApiPackage> byAid = new HashMap<String, ApiPackage>();
    private final Map<String, ApiPackage> byName = new HashMap<String, ApiPackage>();
    private final Map<String, ApiClass> classByName = new HashMap<String, ApiClass>();

    public ApiPackage packageByAid(byte[] aid) {
        return byAid.get(Hex.toHex(aid).toUpperCase());
    }

    public ApiPackage packageByName(String name) {
        return byName.get(name);
    }

    public ApiClass classByName(String internalName) {
        return classByName.get(internalName);
    }

    public int instanceSizeOf(String internalName) {
        ApiClass c = classByName.get(internalName);
        return c == null ? 0 : c.instanceSize;
    }

    public int packageCount() {
        return byAid.size();
    }

    /** Registers a package parsed from an export file, replacing any earlier one. */
    public void add(ApiPackage pkg) {
        byAid.put(Hex.toHex(pkg.aid).toUpperCase(), pkg);
        byName.put(pkg.name, pkg);
        for (ApiClass c : pkg.byName.values()) {
            classByName.put(c.name, c);
        }
    }

    public java.util.Collection<ApiPackage> packages() {
        return byName.values();
    }

    /* ------------------------------------------------------------------ */

    public void load(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            load(in, f.getPath());
        } finally {
            in.close();
        }
    }

    public void load(InputStream in, String origin) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        ApiPackage pkg = null;
        ApiClass cls = null;
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                if ("PKG".equals(t[0])) {
                    pkg = new ApiPackage(t[1], Hex.parse(t[2]));
                    if (t.length > 3) {
                        pkg.major = Integer.parseInt(t[3]);
                    }
                    if (t.length > 4) {
                        pkg.minor = Integer.parseInt(t[4]);
                    }
                    byAid.put(Hex.toHex(pkg.aid).toUpperCase(), pkg);
                    byName.put(pkg.name, pkg);
                    cls = null;
                } else if ("CLASS".equals(t[0])) {
                    require(pkg != null, "CLASS before PKG");
                    cls = new ApiClass(pkg, Integer.parseInt(t[1]), t[2]);
                    if (t.length > 4 && "SIZE".equals(t[3])) {
                        cls.instanceSize = Integer.parseInt(t[4]);
                    }
                    pkg.add(cls);
                    classByName.put(cls.name, cls);
                } else if ("V".equals(t[0])) {
                    require(cls != null, "V before CLASS");
                    cls.virtualMethods.put(Integer.valueOf(Integer.parseInt(t[1])), t[2]);
                } else if ("S".equals(t[0])) {
                    require(cls != null, "S before CLASS");
                    cls.staticMethods.put(Integer.valueOf(Integer.parseInt(t[1])), t[2]);
                } else {
                    throw new IllegalArgumentException("unknown directive " + t[0]);
                }
            } catch (RuntimeException e) {
                throw new IOException(origin + ":" + lineNo + ": " + e.getMessage(), e);
            }
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException(msg);
        }
    }
}
