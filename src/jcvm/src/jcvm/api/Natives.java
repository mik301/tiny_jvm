package jcvm.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jcvm.jcre.ApduState;
import jcvm.jcre.JCRE;
import jcvm.rt.BuiltinObject;
import jcvm.rt.JCArray;
import jcvm.rt.JCThrow;
import jcvm.rt.VM;

/**
 * Registry of natively implemented API methods.
 *
 * Keys are "internal/class/Name.method(descriptor)ret", exactly the strings the
 * token table in api-tokens.txt maps method tokens to.
 */
public final class Natives {

    private final Map<String, NativeImpl> map = new HashMap<String, NativeImpl>();

    public NativeImpl get(String key) {
        return map.get(key);
    }

    public void put(String key, NativeImpl impl) {
        map.put(key, impl);
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Set<String> keys() {
        return new TreeSet<String>(map.keySet());
    }

    public int size() {
        return map.size();
    }

    /* ------------------------------------------------------------------ */

    private static JCRE card(VM vm) {
        JCRE c = (JCRE) vm.card;
        if (c == null) {
            throw new JCThrow("java/lang/Error", "no card runtime attached to the VM");
        }
        return c;
    }

    private static ApduState apduOf(Object self) {
        BuiltinObject b = (BuiltinObject) self;
        if (!(b.state instanceof ApduState)) {
            throw new JCThrow("java/lang/Error", "not an APDU object: " + self);
        }
        return (ApduState) b.state;
    }

    /** PIN state hung off an OwnerPIN builtin object. */
    public static final class Pin {

        public int tryLimit;
        public int maxPinSize;
        public int triesLeft;
        public byte[] value = new byte[0];
        public boolean validated;
    }

    /* ================================================================== */

    public static Natives standard() {
        final Natives n = new Natives();

        /* ---------------- java.lang.Object ---------------- */
        n.put("java/lang/Object.<init>()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });

        /* ---------------- Applet ---------------- */
        n.put("javacard/framework/Applet.<init>()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });
        n.put("javacard/framework/Applet.register()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                card(vm).registerApplet(a.self, null);
                vm.retVoid();
            }
        });
        n.put("javacard/framework/Applet.register([BSB)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] src = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                byte[] aid = new byte[len];
                System.arraycopy(src, off, aid, 0, len);
                card(vm).registerApplet(a.self, aid);
                vm.retVoid();
            }
        });
        n.put("javacard/framework/Applet.selectingApplet()Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retBool(card(vm).isSelecting(a.self));
            }
        });
        n.put("javacard/framework/Applet.select()Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retBool(true);
            }
        });
        n.put("javacard/framework/Applet.deselect()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });
        n.put("javacard/framework/Applet.getShareableInterfaceObject"
                + "(Ljavacard/framework/AID;B)Ljavacard/framework/Shareable;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(null);
            }
        });
        n.put("javacard/framework/Applet.process(Ljavacard/framework/APDU;)V",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                throw new JCThrow("java/lang/Error",
                        "Applet.process is abstract - the applet must override it");
            }
        });

        /* ---------------- APDU ---------------- */
        n.put("javacard/framework/APDU.getBuffer()[B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(apduOf(a.self).bufferArray);
            }
        });
        n.put("javacard/framework/APDU.setIncomingAndReceive()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(apduOf(a.self).setIncomingAndReceive());
            }
        });
        n.put("javacard/framework/APDU.receiveBytes(S)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                ApduState s = apduOf(a.self);
                s.received = true;
                vm.retShort(s.lc);
            }
        });
        n.put("javacard/framework/APDU.setOutgoing()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(apduOf(a.self).setOutgoing());
            }
        });
        n.put("javacard/framework/APDU.setOutgoingNoChaining()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(apduOf(a.self).setOutgoing());
            }
        });
        n.put("javacard/framework/APDU.setOutgoingLength(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                apduOf(a.self).setOutgoingLength(a.sh(0));
                vm.retVoid();
            }
        });
        n.put("javacard/framework/APDU.sendBytes(SS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                apduOf(a.self).sendBytes(a.sh(0), a.sh(1));
                vm.retVoid();
            }
        });
        n.put("javacard/framework/APDU.sendBytesLong([BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                apduOf(a.self).sendBytesLong(a.bytes(0), a.sh(1), a.sh(2));
                vm.retVoid();
            }
        });
        n.put("javacard/framework/APDU.setOutgoingAndSend(SS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                apduOf(a.self).setOutgoingAndSend(a.sh(0), a.sh(1));
                vm.retVoid();
            }
        });
        n.put("javacard/framework/APDU.getIncomingLength()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(apduOf(a.self).lc);
            }
        });
        n.put("javacard/framework/APDU.getOffsetCdata()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(ApduState.OFFSET_CDATA);
            }
        });
        n.put("javacard/framework/APDU.getProtocol()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(1); // PROTOCOL_T0 style value; T=1 simulation
            }
        });
        n.put("javacard/framework/APDU.getNAD()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(0);
            }
        });
        n.put("javacard/framework/APDU.waitExtension()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });

        /* ---------------- Util ---------------- */
        n.put("javacard/framework/Util.arrayCopy([BS[BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                JCArray dst = a.array(2);
                int dstOff = a.sh(3);
                int len = a.sh(4);
                vm.transaction.recordArrayRange(dst, dstOff, len);
                copy(a.bytes(0), a.sh(1), a.bytes(2), dstOff, len);
                vm.retShort(dstOff + len);
            }
        });
        n.put("javacard/framework/Util.arrayCopyNonAtomic([BS[BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                int dstOff = a.sh(3);
                int len = a.sh(4);
                copy(a.bytes(0), a.sh(1), a.bytes(2), dstOff, len);
                vm.retShort(dstOff + len);
            }
        });
        n.put("javacard/framework/Util.arrayFillNonAtomic([BSSB)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] b = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2);
                byte v = (byte) a.sh(3);
                bounds(b.length, off, len);
                for (int i = 0; i < len; i++) {
                    b[off + i] = v;
                }
                vm.retShort(off + len);
            }
        });
        n.put("javacard/framework/Util.arrayCompare([BS[BSS)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] x = a.bytes(0);
                int xo = a.sh(1);
                byte[] y = a.bytes(2);
                int yo = a.sh(3);
                int len = a.sh(4);
                bounds(x.length, xo, len);
                bounds(y.length, yo, len);
                int r = 0;
                for (int i = 0; i < len; i++) {
                    int c = (x[xo + i] & 0xFF) - (y[yo + i] & 0xFF);
                    if (c != 0) {
                        r = c < 0 ? -1 : 1;
                        break;
                    }
                }
                vm.retShort(r);
            }
        });
        n.put("javacard/framework/Util.makeShort(BB)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(((a.sh(0) & 0xFF) << 8) | (a.sh(1) & 0xFF));
            }
        });
        n.put("javacard/framework/Util.getShort([BS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] b = a.bytes(0);
                int o = a.sh(1);
                bounds(b.length, o, 2);
                vm.retShort(((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF));
            }
        });
        n.put("javacard/framework/Util.setShort([BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                JCArray arr = a.array(0);
                byte[] b = a.bytes(0);
                int o = a.sh(1);
                int v = a.sh(2);
                bounds(b.length, o, 2);
                vm.transaction.recordArrayRange(arr, o, 2);
                b[o] = (byte) (v >> 8);
                b[o + 1] = (byte) v;
                vm.retShort(o + 2);
            }
        });

        /* ---------------- JCSystem ---------------- */
        n.put("javacard/framework/JCSystem.isTransient(Ljava/lang/Object;)B",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Object o = a.ref(0);
                vm.retShort(o instanceof JCArray ? ((JCArray) o).transientKind : 0);
            }
        });
        n.put("javacard/framework/JCSystem.makeTransientBooleanArray(SB)[Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).makeTransient(JCArray.T_BOOLEAN, a.sh(0), (byte) a.sh(1)));
            }
        });
        n.put("javacard/framework/JCSystem.makeTransientByteArray(SB)[B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).makeTransient(JCArray.T_BYTE, a.sh(0), (byte) a.sh(1)));
            }
        });
        n.put("javacard/framework/JCSystem.makeTransientShortArray(SB)[S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).makeTransient(JCArray.T_SHORT, a.sh(0), (byte) a.sh(1)));
            }
        });
        n.put("javacard/framework/JCSystem.makeTransientObjectArray(SB)"
                + "[Ljava/lang/Object;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).makeTransient(JCArray.T_REFERENCE, a.sh(0), (byte) a.sh(1)));
            }
        });
        n.put("javacard/framework/JCSystem.beginTransaction()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.transaction.begin();
                vm.retVoid();
            }
        });
        n.put("javacard/framework/JCSystem.commitTransaction()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.transaction.commit();
                vm.retVoid();
            }
        });
        n.put("javacard/framework/JCSystem.abortTransaction()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.transaction.abort();
                vm.retVoid();
            }
        });
        n.put("javacard/framework/JCSystem.getTransactionDepth()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(vm.transaction.depth());
            }
        });
        n.put("javacard/framework/JCSystem.getUnusedCommitCapacity()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(vm.transaction.maxCommitCapacity - vm.transaction.used());
            }
        });
        n.put("javacard/framework/JCSystem.getMaxCommitCapacity()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(vm.transaction.maxCommitCapacity);
            }
        });
        n.put("javacard/framework/JCSystem.getAvailableMemory(B)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(32767);
            }
        });
        n.put("javacard/framework/JCSystem.getVersion()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(0x0202);
            }
        });
        n.put("javacard/framework/JCSystem.getAID()Ljavacard/framework/AID;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).currentAidObject());
            }
        });
        n.put("javacard/framework/JCSystem.getPreviousContextAID()"
                + "Ljavacard/framework/AID;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(null);
            }
        });
        n.put("javacard/framework/JCSystem.lookupAID([BSB)Ljavacard/framework/AID;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] src = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                byte[] aid = new byte[len];
                bounds(src.length, off, len);
                System.arraycopy(src, off, aid, 0, len);
                vm.retRef(card(vm).lookupAid(aid));
            }
        });

        /* ---------------- AID ---------------- */
        n.put("javacard/framework/AID.getBytes([BS)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] aid = ((BuiltinObject) a.self).data;
                byte[] dst = a.bytes(0);
                int off = a.sh(1);
                bounds(dst.length, off, aid.length);
                System.arraycopy(aid, 0, dst, off, aid.length);
                vm.retShort(aid.length);
            }
        });
        n.put("javacard/framework/AID.equals(Ljava/lang/Object;)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Object o = a.ref(0);
                byte[] mine = ((BuiltinObject) a.self).data;
                boolean eq = (o instanceof BuiltinObject)
                        && java.util.Arrays.equals(mine, ((BuiltinObject) o).data);
                vm.retBool(eq);
            }
        });
        n.put("javacard/framework/AID.equals([BSB)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] mine = ((BuiltinObject) a.self).data;
                byte[] other = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                vm.retBool(regionEquals(mine, 0, mine.length, other, off, len));
            }
        });
        n.put("javacard/framework/AID.partialEquals([BSB)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] mine = ((BuiltinObject) a.self).data;
                byte[] other = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                vm.retBool(mine.length >= len
                        && regionEquals(mine, 0, len, other, off, len));
            }
        });
        n.put("javacard/framework/AID.RIDEquals(Ljavacard/framework/AID;)Z",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Object o = a.ref(0);
                if (!(o instanceof BuiltinObject)) {
                    vm.retBool(false);
                    return;
                }
                byte[] mine = ((BuiltinObject) a.self).data;
                byte[] other = ((BuiltinObject) o).data;
                vm.retBool(mine.length >= 5 && other.length >= 5
                        && regionEquals(mine, 0, 5, other, 0, 5));
            }
        });

        /* ---------------- OwnerPIN ---------------- */
        n.put("javacard/framework/OwnerPIN.<init>(BB)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Pin p = new Pin();
                p.tryLimit = a.sh(0) & 0xFF;
                p.maxPinSize = a.sh(1) & 0xFF;
                if (p.tryLimit < 1 || p.maxPinSize < 1) {
                    throw JCThrow.framework(JCThrow.PIN_EXCEPTION, 1); // ILLEGAL_VALUE
                }
                p.triesLeft = p.tryLimit;
                ((BuiltinObject) a.self).state = p;
                vm.retVoid();
            }
        });
        n.put("javacard/framework/OwnerPIN.getTriesRemaining()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(pin(a.self).triesLeft);
            }
        });
        n.put("javacard/framework/OwnerPIN.isValidated()Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retBool(pin(a.self).validated);
            }
        });
        n.put("javacard/framework/OwnerPIN.reset()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                pin(a.self).validated = false;
                vm.retVoid();
            }
        });
        n.put("javacard/framework/OwnerPIN.resetAndUnblock()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Pin p = pin(a.self);
                p.validated = false;
                p.triesLeft = p.tryLimit;
                vm.retVoid();
            }
        });
        n.put("javacard/framework/OwnerPIN.update([BSB)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Pin p = pin(a.self);
                byte[] src = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                if (len > p.maxPinSize) {
                    throw JCThrow.framework(JCThrow.PIN_EXCEPTION, 1);
                }
                bounds(src.length, off, len);
                p.value = new byte[len];
                System.arraycopy(src, off, p.value, 0, len);
                p.triesLeft = p.tryLimit;
                p.validated = false;
                vm.retVoid();
            }
        });
        n.put("javacard/framework/OwnerPIN.check([BSB)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                Pin p = pin(a.self);
                if (p.triesLeft <= 0) {
                    p.validated = false;
                    vm.retBool(false);
                    return;
                }
                byte[] src = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2) & 0xFF;
                p.triesLeft--;
                p.validated = false;
                boolean ok = regionEquals(p.value, 0, p.value.length, src, off, len);
                if (ok) {
                    p.validated = true;
                    p.triesLeft = p.tryLimit;
                }
                vm.retBool(ok);
            }
        });

        /* ---------------- exceptions ---------------- */
        exception(n, "javacard/framework/ISOException");
        exception(n, "javacard/framework/APDUException");
        exception(n, "javacard/framework/PINException");
        exception(n, "javacard/framework/SystemException");
        exception(n, "javacard/framework/TransactionException");
        exception(n, "javacard/framework/UserException");

        UiccNatives.install(n);
        return n;
    }

    /** Registers &lt;init&gt;(S), throwIt(S), getReason() and setReason(S). */
    private static void exception(Natives n, final String cls) {
        n.put(cls + ".<init>(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                ((BuiltinObject) a.self).reason = a.sh(0) & 0xFFFF;
                vm.retVoid();
            }
        });
        n.put(cls + ".<init>()V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });
        n.put(cls + ".throwIt(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                throw JCThrow.framework(cls, a.sh(0) & 0xFFFF);
            }
        });
        n.put(cls + ".getReason()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(((BuiltinObject) a.self).reason);
            }
        });
        n.put(cls + ".setReason(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                ((BuiltinObject) a.self).reason = a.sh(0) & 0xFFFF;
                vm.retVoid();
            }
        });
    }

    /* ------------------------------------------------------------------ */

    private static Pin pin(Object self) {
        BuiltinObject b = (BuiltinObject) self;
        if (!(b.state instanceof Pin)) {
            throw new JCThrow("java/lang/Error", "OwnerPIN was not constructed");
        }
        return (Pin) b.state;
    }

    private static void copy(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
        bounds(src.length, srcOff, len);
        bounds(dst.length, dstOff, len);
        System.arraycopy(src, srcOff, dst, dstOff, len);
    }

    private static void bounds(int size, int off, int len) {
        if (off < 0 || len < 0 || off + len > size) {
            throw new JCThrow(JCThrow.ARRAY_INDEX_OUT_OF_BOUNDS,
                    "offset " + off + " length " + len + " outside array of " + size);
        }
    }

    private static boolean regionEquals(byte[] a, int ao, int alen,
            byte[] b, int bo, int blen) {
        if (alen != blen) {
            return false;
        }
        bounds(b.length, bo, blen);
        for (int i = 0; i < alen; i++) {
            if (a[ao + i] != b[bo + i]) {
                return false;
            }
        }
        return true;
    }
}
