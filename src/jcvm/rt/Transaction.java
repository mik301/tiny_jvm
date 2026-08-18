package jcvm.rt;

import java.util.ArrayList;
import java.util.List;

/**
 * Journals persistent updates so that abortTransaction can roll them back.
 *
 * Only one level of transaction is supported, which matches the Java Card
 * specification. Transient arrays are deliberately not journalled.
 */
public final class Transaction {

    private abstract static class Entry {

        abstract void undo();
    }

    private static final class FieldPrim extends Entry {

        final JCObject o;
        final int token;
        final int old;

        FieldPrim(JCObject o, int token) {
            this.o = o;
            this.token = token;
            this.old = o.prim[token];
        }

        void undo() {
            o.prim[token] = old;
        }
    }

    private static final class FieldRef extends Entry {

        final JCObject o;
        final int token;
        final Object old;

        FieldRef(JCObject o, int token) {
            this.o = o;
            this.token = token;
            this.old = o.refs[token];
        }

        void undo() {
            o.refs[token] = old;
        }
    }

    private static final class ArrayPrim extends Entry {

        final JCArray a;
        final int index;
        final int old;

        ArrayPrim(JCArray a, int index) {
            this.a = a;
            this.index = index;
            this.old = a.getPrim(index);
        }

        void undo() {
            a.setPrim(index, old);
        }
    }

    private static final class ArrayRef extends Entry {

        final JCArray a;
        final int index;
        final Object old;

        ArrayRef(JCArray a, int index) {
            this.a = a;
            this.index = index;
            this.old = a.getRef(index);
        }

        void undo() {
            a.setRef(index, old);
        }
    }

    private static final class StaticData extends Entry {

        final LoadedPackage p;
        final int off;
        final byte[] old;

        StaticData(LoadedPackage p, int off, int len) {
            this.p = p;
            this.off = off;
            this.old = new byte[len];
            System.arraycopy(p.staticData, off, old, 0, len);
        }

        void undo() {
            System.arraycopy(old, 0, p.staticData, off, old.length);
        }
    }

    private static final class StaticRef extends Entry {

        final LoadedPackage p;
        final int off;
        final Object old;

        StaticRef(LoadedPackage p, int off) {
            this.p = p;
            this.off = off;
            this.old = p.getStaticRef(off);
        }

        void undo() {
            p.setStaticRef(off, old);
        }
    }

    private final List<Entry> journal = new ArrayList<Entry>();
    private boolean active;
    /** Capacity in bytes, mirroring JCSystem.getMaxCommitCapacity. */
    public int maxCommitCapacity = 4096;

    public boolean isActive() {
        return active;
    }

    public int depth() {
        return active ? 1 : 0;
    }

    public int used() {
        return journal.size() * 4;
    }

    public void begin() {
        if (active) {
            throw JCThrow.framework(JCThrow.TRANSACTION_EXCEPTION, 1); // IN_PROGRESS
        }
        active = true;
        journal.clear();
    }

    public void commit() {
        if (!active) {
            throw JCThrow.framework(JCThrow.TRANSACTION_EXCEPTION, 2); // NOT_IN_PROGRESS
        }
        active = false;
        journal.clear();
    }

    public void abort() {
        if (!active) {
            throw JCThrow.framework(JCThrow.TRANSACTION_EXCEPTION, 2);
        }
        rollback();
    }

    /** Used by the JCRE when an applet leaves a transaction open. */
    public void rollbackIfActive() {
        if (active) {
            rollback();
        }
    }

    private void rollback() {
        for (int i = journal.size() - 1; i >= 0; i--) {
            journal.get(i).undo();
        }
        journal.clear();
        active = false;
    }

    private void add(Entry e) {
        if (journal.size() * 4 > maxCommitCapacity) {
            throw JCThrow.framework(JCThrow.TRANSACTION_EXCEPTION, 3); // BUFFER_FULL
        }
        journal.add(e);
    }

    public void recordFieldPrim(JCObject o, int token) {
        if (active && token >= 0 && token < o.prim.length) {
            add(new FieldPrim(o, token));
        }
    }

    public void recordFieldRef(JCObject o, int token) {
        if (active && token >= 0 && token < o.refs.length) {
            add(new FieldRef(o, token));
        }
    }

    public void recordArrayPrim(JCArray a, int index) {
        if (active && a.transientKind == JCArray.NOT_A_TRANSIENT_OBJECT
                && index >= 0 && index < a.length) {
            add(new ArrayPrim(a, index));
        }
    }

    public void recordArrayRef(JCArray a, int index) {
        if (active && a.transientKind == JCArray.NOT_A_TRANSIENT_OBJECT
                && index >= 0 && index < a.length) {
            add(new ArrayRef(a, index));
        }
    }

    public void recordArrayRange(JCArray a, int off, int len) {
        if (!active || a.transientKind != JCArray.NOT_A_TRANSIENT_OBJECT) {
            return;
        }
        for (int i = 0; i < len; i++) {
            int idx = off + i;
            if (idx >= 0 && idx < a.length) {
                if (a.isPrimitive()) {
                    add(new ArrayPrim(a, idx));
                } else {
                    add(new ArrayRef(a, idx));
                }
            }
        }
    }

    public void recordStaticData(LoadedPackage p, int off, int len) {
        if (active && off >= 0 && off + len <= p.staticData.length) {
            add(new StaticData(p, off, len));
        }
    }

    public void recordStaticRef(LoadedPackage p, int off) {
        if (active) {
            add(new StaticRef(p, off));
        }
    }
}
