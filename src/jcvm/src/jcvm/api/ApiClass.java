package jcvm.api;

import java.util.HashMap;
import java.util.Map;

/**
 * A natively implemented API class. Method tokens map to a fully qualified
 * "class.name(descriptor)" key, which is what {@link Natives} is keyed by.
 */
public final class ApiClass {

    public final ApiPackage pkg;
    public final int token;
    public final String name;   // internal form, e.g. javacard/framework/Applet
    public int instanceSize;    // 16 bit cells contributed to subclasses

    public final Map<Integer, String> virtualMethods = new HashMap<Integer, String>();
    public final Map<Integer, String> staticMethods = new HashMap<Integer, String>();

    public ApiClass(ApiPackage pkg, int token, String name) {
        this.pkg = pkg;
        this.token = token;
        this.name = name;
    }

    public String virtualKey(int token) {
        String sig = virtualMethods.get(Integer.valueOf(token));
        return sig == null ? null : name + "." + sig;
    }

    public String staticKey(int token) {
        String sig = staticMethods.get(Integer.valueOf(token));
        return sig == null ? null : name + "." + sig;
    }

    /** Token of the virtual method with this "name(descriptor)ret", or -1. */
    public int virtualTokenOf(String signature) {
        for (Map.Entry<Integer, String> e : virtualMethods.entrySet()) {
            if (e.getValue().equals(signature)) {
                return e.getKey().intValue();
            }
        }
        return -1;
    }

    public int staticTokenOf(String signature) {
        for (Map.Entry<Integer, String> e : staticMethods.entrySet()) {
            if (e.getValue().equals(signature)) {
                return e.getKey().intValue();
            }
        }
        return -1;
    }

    public String toString() {
        return name + "#" + token;
    }
}
