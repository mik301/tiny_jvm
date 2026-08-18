package jcvm.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses a JVM style method descriptor into the information the interpreter
 * needs: how many 16 bit words the arguments occupy and what kind of value
 * comes back.
 */
public final class Descriptor {

    public static final char KIND_VOID = 'V';
    public static final char KIND_REF = 'A';

    /** One entry per declared parameter: B, S, I, Z or A. */
    public final char[] paramKinds;
    /** Word offset of each parameter within the argument area. */
    public final int[] paramWordOffset;
    /** Total argument size in words, excluding `this`. */
    public final int argWords;
    public final char returnKind;

    private static final Map<String, Descriptor> CACHE = new HashMap<String, Descriptor>();

    private Descriptor(char[] kinds, int[] offsets, int words, char ret) {
        this.paramKinds = kinds;
        this.paramWordOffset = offsets;
        this.argWords = words;
        this.returnKind = ret;
    }

    public int paramCount() {
        return paramKinds.length;
    }

    public int returnWords() {
        if (returnKind == KIND_VOID) {
            return 0;
        }
        return returnKind == 'I' ? 2 : 1;
    }

    /** Accepts either a bare descriptor or a "class.name(desc)ret" key. */
    public static synchronized Descriptor of(String s) {
        Descriptor d = CACHE.get(s);
        if (d != null) {
            return d;
        }
        int open = s.indexOf('(');
        if (open < 0) {
            throw new IllegalArgumentException("not a descriptor: " + s);
        }
        int close = s.indexOf(')', open);
        if (close < 0) {
            throw new IllegalArgumentException("unterminated descriptor: " + s);
        }
        StringBuilder kinds = new StringBuilder();
        int words = 0;
        int i = open + 1;
        java.util.ArrayList<Integer> offsets = new java.util.ArrayList<Integer>();
        while (i < close) {
            char c = s.charAt(i);
            char kind;
            if (c == '[') {
                while (i < close && s.charAt(i) == '[') {
                    i++;
                }
                if (i < close && s.charAt(i) == 'L') {
                    while (i < close && s.charAt(i) != ';') {
                        i++;
                    }
                }
                i++;
                kind = KIND_REF;
            } else if (c == 'L') {
                while (i < close && s.charAt(i) != ';') {
                    i++;
                }
                i++;
                kind = KIND_REF;
            } else {
                i++;
                kind = c;
            }
            kinds.append(kind);
            offsets.add(Integer.valueOf(words));
            words += (kind == 'I') ? 2 : 1;
        }
        char ret = (close + 1 < s.length()) ? s.charAt(close + 1) : KIND_VOID;
        if (ret == '[' || ret == 'L') {
            ret = KIND_REF;
        }
        char[] kindArr = new char[kinds.length()];
        kinds.getChars(0, kinds.length(), kindArr, 0);
        int[] offArr = new int[offsets.size()];
        for (int k = 0; k < offArr.length; k++) {
            offArr[k] = offsets.get(k).intValue();
        }
        d = new Descriptor(kindArr, offArr, words, ret);
        CACHE.put(s, d);
        return d;
    }
}
