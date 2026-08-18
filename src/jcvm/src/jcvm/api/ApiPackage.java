package jcvm.api;

import java.util.HashMap;
import java.util.Map;

/** An API package that the VM implements natively rather than loading from a CAP. */
public final class ApiPackage {

    public final String name;      // e.g. javacard.framework
    public final byte[] aid;
    public int major = 1;
    public int minor = 0;
    /** Where these tokens came from, for warning about guessed defaults. */
    public String source = "the bundled api-tokens.txt";
    public boolean fromExportFile;

    public final Map<Integer, ApiClass> byToken = new HashMap<Integer, ApiClass>();
    public final Map<String, ApiClass> byName = new HashMap<String, ApiClass>();

    public ApiPackage(String name, byte[] aid) {
        this.name = name;
        this.aid = aid;
    }

    public void add(ApiClass c) {
        byToken.put(Integer.valueOf(c.token), c);
        byName.put(c.name, c);
    }

    public ApiClass classByToken(int token) {
        return byToken.get(Integer.valueOf(token));
    }

    public String toString() {
        return name + " (" + byToken.size() + " classes)";
    }
}
