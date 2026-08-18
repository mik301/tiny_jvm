package jcvm.tool;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small Java Card bytecode assembler: enough to hand build the demo
 * applet that {@link CapBuilder} packages. Branches always use the wide
 * (2 byte offset) forms so no offset can overflow.
 */
public final class Asm {

    private static final class Fix {

        final int patchAt;   // index of the first offset byte
        final int base;      // pc of the branch opcode
        final String label;

        Fix(int patchAt, int base, String label) {
            this.patchAt = patchAt;
            this.base = base;
            this.label = label;
        }
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Integer> labels = new HashMap<String, Integer>();
    private final List<Fix> fixes = new ArrayList<Fix>();

    public int pc() {
        return out.size();
    }

    public Asm u1(int v) {
        out.write(v & 0xFF);
        return this;
    }

    public Asm u2(int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
        return this;
    }

    /** Emits a bare opcode. */
    public Asm op(int opcode) {
        return u1(opcode);
    }

    /** Emits an opcode with a one byte operand. */
    public Asm op1(int opcode, int operand) {
        return u1(opcode).u1(operand);
    }

    /** Emits an opcode with a two byte operand. */
    public Asm op2(int opcode, int operand) {
        return u1(opcode).u2(operand);
    }

    public Asm label(String name) {
        if (labels.put(name, Integer.valueOf(out.size())) != null) {
            throw new IllegalStateException("duplicate label " + name);
        }
        return this;
    }

    /** Emits a wide-form branch to a label. */
    public Asm branch(int wideOpcode, String label) {
        int base = out.size();
        u1(wideOpcode);
        fixes.add(new Fix(out.size(), base, label));
        u2(0);
        return this;
    }

    public byte[] toBytes() {
        byte[] code = out.toByteArray();
        for (int i = 0; i < fixes.size(); i++) {
            Fix f = fixes.get(i);
            Integer target = labels.get(f.label);
            if (target == null) {
                throw new IllegalStateException("undefined label " + f.label);
            }
            int delta = target.intValue() - f.base;
            code[f.patchAt] = (byte) (delta >> 8);
            code[f.patchAt + 1] = (byte) delta;
        }
        return code;
    }
}
