package jcvm.cap;

public class CapFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CapFormatException(String msg) {
        super(msg);
    }

    public CapFormatException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
