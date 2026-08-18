package jcvm.rt;

/**
 * Carries a Java Card exception through the interpreter.
 *
 * {@code className} is the internal name of the exception class. For applet
 * defined exceptions {@code thrown} holds the JCObject that was created by the
 * applet; for framework exceptions it holds a {@link BuiltinObject} (or null,
 * when the VM itself raised the condition).
 */
public class JCThrow extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String NULL_POINTER = "java/lang/NullPointerException";
    public static final String ARRAY_INDEX_OUT_OF_BOUNDS = "java/lang/ArrayIndexOutOfBoundsException";
    public static final String ARRAY_STORE = "java/lang/ArrayStoreException";
    public static final String CLASS_CAST = "java/lang/ClassCastException";
    public static final String ARITHMETIC = "java/lang/ArithmeticException";
    public static final String NEGATIVE_ARRAY_SIZE = "java/lang/NegativeArraySizeException";
    public static final String SECURITY = "java/lang/SecurityException";
    public static final String ISO_EXCEPTION = "javacard/framework/ISOException";
    public static final String USER_EXCEPTION = "javacard/framework/UserException";
    public static final String APDU_EXCEPTION = "javacard/framework/APDUException";
    public static final String SYSTEM_EXCEPTION = "javacard/framework/SystemException";
    public static final String PIN_EXCEPTION = "javacard/framework/PINException";
    public static final String TRANSACTION_EXCEPTION = "javacard/framework/TransactionException";

    public final String className;
    public Object thrown;
    public int reason;
    /** Where in the applet's bytecode this passed through, innermost first. */
    public final java.util.List<String> vmTrace = new java.util.ArrayList<String>();

    public void addFrame(String where) {
        if (vmTrace.size() < 32) {
            vmTrace.add(where);
        }
    }

    /** The bytecode trace, one frame per line, or an empty string. */
    public String traceText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vmTrace.size(); i++) {
            sb.append("      at ").append(vmTrace.get(i)).append('\n');
        }
        return sb.toString();
    }

    public JCThrow(String className, String message) {
        super(className + (message == null ? "" : ": " + message));
        this.className = className;
    }

    public JCThrow(String className, int reason, Object thrown) {
        super(className + " reason=" + reason);
        this.className = className;
        this.reason = reason;
        this.thrown = thrown;
    }

    /** Builds an ISOException carrying the given status word. */
    public static JCThrow iso(int sw) {
        BuiltinObject o = new BuiltinObject(ISO_EXCEPTION);
        o.reason = sw & 0xFFFF;
        JCThrow t = new JCThrow(ISO_EXCEPTION, o.reason, o);
        return t;
    }

    public static JCThrow framework(String className, int reason) {
        BuiltinObject o = new BuiltinObject(className);
        o.reason = reason;
        return new JCThrow(className, reason, o);
    }

    public static JCThrow nullPointer() {
        return new JCThrow(NULL_POINTER, (String) null);
    }
}
