package jcvm.jcre;

import java.io.ByteArrayOutputStream;

import jcvm.rt.JCArray;
import jcvm.rt.JCThrow;

/** State of the APDU currently being processed. */
public final class ApduState {

    /* APDUException reason codes */
    public static final int ILLEGAL_USE = 1;
    public static final int BUFFER_BOUNDS = 2;
    public static final int BAD_LENGTH = 3;
    public static final int IO_ERROR = 4;
    public static final int NO_T0_GETRESPONSE = 0xAA;

    public static final int OFFSET_CDATA = 5;
    public static final int STATE_INITIAL = 0;
    public static final int STATE_PARTIAL_INCOMING = 1;
    public static final int STATE_FULL_INCOMING = 2;
    public static final int STATE_OUTGOING = 3;
    public static final int STATE_OUTGOING_LENGTH_KNOWN = 4;
    public static final int STATE_PARTIAL_OUTGOING = 5;
    public static final int STATE_FULL_OUTGOING = 6;

    public final byte[] buffer;
    public final JCArray bufferArray;

    public int lc;
    public int le;
    public int state = STATE_INITIAL;
    public boolean received;
    public int outgoingLength = -1;
    public int sent;

    private final ByteArrayOutputStream response = new ByteArrayOutputStream();

    public ApduState(byte[] buffer, JCArray bufferArray) {
        this.buffer = buffer;
        this.bufferArray = bufferArray;
    }

    /** Loads a command APDU into the buffer. */
    public void begin(byte[] command) {
        java.util.Arrays.fill(buffer, (byte) 0);
        response.reset();
        state = STATE_INITIAL;
        received = false;
        outgoingLength = -1;
        sent = 0;
        lc = 0;
        le = 0;
        int n = Math.min(command.length, buffer.length);
        System.arraycopy(command, 0, buffer, 0, n);
        if (command.length == 4) {
            lc = 0;
            le = 0;
        } else if (command.length == 5) {
            lc = 0;
            le = (command[4] & 0xFF) == 0 ? 256 : (command[4] & 0xFF);
        } else {
            lc = command[4] & 0xFF;
            int rest = command.length - 5 - lc;
            if (rest >= 1) {
                le = (command[5 + lc] & 0xFF) == 0 ? 256 : (command[5 + lc] & 0xFF);
            } else {
                le = 0;
            }
        }
    }

    public int setIncomingAndReceive() {
        if (state >= STATE_OUTGOING) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, ILLEGAL_USE);
        }
        received = true;
        state = STATE_FULL_INCOMING;
        return lc;
    }

    public int setOutgoing() {
        if (state >= STATE_OUTGOING) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, ILLEGAL_USE);
        }
        state = STATE_OUTGOING;
        return le;
    }

    public void setOutgoingLength(int len) {
        if (state != STATE_OUTGOING) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, ILLEGAL_USE);
        }
        if (len < 0 || len > 32767) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, BAD_LENGTH);
        }
        outgoingLength = len;
        state = STATE_OUTGOING_LENGTH_KNOWN;
    }

    public void sendBytes(int off, int len) {
        if (state < STATE_OUTGOING_LENGTH_KNOWN) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, ILLEGAL_USE);
        }
        if (off < 0 || len < 0 || off + len > buffer.length) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, BUFFER_BOUNDS);
        }
        response.write(buffer, off, len);
        sent += len;
        state = (outgoingLength >= 0 && sent >= outgoingLength)
                ? STATE_FULL_OUTGOING : STATE_PARTIAL_OUTGOING;
    }

    public void sendBytesLong(byte[] src, int off, int len) {
        if (state < STATE_OUTGOING_LENGTH_KNOWN) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, ILLEGAL_USE);
        }
        if (off < 0 || len < 0 || off + len > src.length) {
            throw JCThrow.framework(JCThrow.APDU_EXCEPTION, BUFFER_BOUNDS);
        }
        response.write(src, off, len);
        sent += len;
        state = (outgoingLength >= 0 && sent >= outgoingLength)
                ? STATE_FULL_OUTGOING : STATE_PARTIAL_OUTGOING;
    }

    public void setOutgoingAndSend(int off, int len) {
        setOutgoing();
        setOutgoingLength(len);
        sendBytes(off, len);
    }

    public byte[] responseData() {
        return response.toByteArray();
    }
}
