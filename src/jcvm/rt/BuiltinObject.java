package jcvm.rt;

import java.util.HashMap;
import java.util.Map;

/**
 * An instance of an API class that the VM implements natively (APDU, AID,
 * OwnerPIN, the framework exceptions, ...). The generic {@code state} slot lets
 * a native implementation hang whatever it needs off the object.
 */
public class BuiltinObject {

    public final String className;
    /** Reason code for CardRuntimeException subclasses. */
    public int reason;
    /** Free-form native state. */
    public Object state;
    public byte[] data;

    public BuiltinObject(String className) {
        this.className = className;
    }

    public String toString() {
        return className + "@" + Integer.toHexString(System.identityHashCode(this));
    }

    /* ------------------------------------------------------------------ */
    /* Builtin class hierarchy, used for instanceof / checkcast / catch    */
    /* ------------------------------------------------------------------ */

    private static final Map<String, String> SUPER = new HashMap<String, String>();

    static {
        s("java/lang/Object", null);
        s("java/lang/Throwable", "java/lang/Object");
        s("java/lang/Exception", "java/lang/Throwable");
        s("java/lang/RuntimeException", "java/lang/Exception");
        s("java/lang/ArithmeticException", "java/lang/RuntimeException");
        s("java/lang/ArrayStoreException", "java/lang/RuntimeException");
        s("java/lang/ClassCastException", "java/lang/RuntimeException");
        s("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException");
        s("java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException");
        s("java/lang/NegativeArraySizeException", "java/lang/RuntimeException");
        s("java/lang/NullPointerException", "java/lang/RuntimeException");
        s("java/lang/SecurityException", "java/lang/RuntimeException");

        s("javacard/framework/CardException", "java/lang/Exception");
        s("javacard/framework/UserException", "javacard/framework/CardException");
        s("javacard/framework/CardRuntimeException", "java/lang/RuntimeException");
        s("javacard/framework/APDUException", "javacard/framework/CardRuntimeException");
        s("javacard/framework/ISOException", "javacard/framework/CardRuntimeException");
        s("javacard/framework/PINException", "javacard/framework/CardRuntimeException");
        s("javacard/framework/SystemException", "javacard/framework/CardRuntimeException");
        s("javacard/framework/TransactionException", "javacard/framework/CardRuntimeException");

        s("javacard/framework/AID", "java/lang/Object");
        s("javacard/framework/APDU", "java/lang/Object");
        s("javacard/framework/Applet", "java/lang/Object");
        s("javacard/framework/JCSystem", "java/lang/Object");
        s("javacard/framework/Util", "java/lang/Object");
        s("javacard/framework/OwnerPIN", "java/lang/Object");
    }

    private static void s(String cls, String sup) {
        SUPER.put(cls, sup);
    }

    public static boolean isKnown(String cls) {
        return SUPER.containsKey(cls);
    }

    public static String superOf(String cls) {
        return SUPER.get(cls);
    }

    /** True when {@code sub} is {@code sup} or inherits from it. */
    public static boolean isAssignable(String sub, String sup) {
        if (sub == null || sup == null) {
            return false;
        }
        String c = sub;
        while (c != null) {
            if (c.equals(sup)) {
                return true;
            }
            if (!SUPER.containsKey(c)) {
                return false;
            }
            c = SUPER.get(c);
        }
        return false;
    }
}
