package jcvm.rt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jcvm.api.ApiClass;
import jcvm.cap.CapPackage;

/**
 * Walks a loaded package's constant pool and reports every API method it
 * references that the VM cannot satisfy.
 *
 * This turns "it failed at runtime after twenty minutes" into a list you can
 * read before running anything, and distinguishes the two failure modes:
 * a token that is not in the token table at all, versus a method whose name
 * resolved fine but has no native implementation.
 */
public final class LinkReport {

    public final List<String> unknownTokens = new ArrayList<String>();
    public final List<String> unimplemented = new ArrayList<String>();

    private LinkReport() {
    }

    public boolean isClean() {
        return unknownTokens.isEmpty() && unimplemented.isEmpty();
    }

    public static LinkReport of(VM vm, LoadedPackage pkg) {
        LinkReport r = new LinkReport();
        Set<String> seenMissing = new LinkedHashSet<String>();
        Set<String> seenUnknown = new LinkedHashSet<String>();

        CapPackage.CpEntry[] cp = pkg.cap.constantPool;
        for (int i = 0; i < cp.length; i++) {
            CapPackage.CpEntry e = cp[i];
            if (!e.external) {
                continue;
            }
            boolean virtual;
            if (e.tag == CapPackage.CP_VIRTUAL_METHODREF
                    || e.tag == CapPackage.CP_SUPER_METHODREF) {
                virtual = true;
            } else if (e.tag == CapPackage.CP_STATIC_METHODREF) {
                virtual = false;
            } else {
                continue;
            }

            ApiClass ac;
            try {
                ac = pkg.externalApiClass(e.packageToken, e.classToken);
            } catch (RuntimeException ex) {
                continue;
            }
            if (ac == null) {
                continue; // resolves to another loaded CAP, not to a native
            }
            String key = virtual ? ac.virtualKey(e.token) : ac.staticKey(e.token);
            if (key == null) {
                seenUnknown.add(ac.name + "  " + (virtual ? "virtual" : "static")
                        + " token " + e.token);
            } else if (vm.natives.resolve(key) == null) {
                seenMissing.add(key);
            }
        }
        r.unknownTokens.addAll(seenUnknown);
        r.unimplemented.addAll(seenMissing);
        return r;
    }

    public String describe() {
        if (isClean()) {
            return "all API references resolve and are implemented";
        }
        StringBuilder sb = new StringBuilder();
        if (!unknownTokens.isEmpty()) {
            sb.append("tokens missing from the token table (run 'loadexp <sdk export dir>'):\n");
            for (int i = 0; i < unknownTokens.size(); i++) {
                sb.append("  ").append(unknownTokens.get(i)).append('\n');
            }
        }
        if (!unimplemented.isEmpty()) {
            sb.append("API methods this VM does not implement:\n");
            for (int i = 0; i < unimplemented.size(); i++) {
                sb.append("  ").append(unimplemented.get(i)).append('\n');
            }
        }
        return sb.toString();
    }
}
