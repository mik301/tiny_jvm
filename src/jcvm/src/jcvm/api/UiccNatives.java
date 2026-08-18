package jcvm.api;

import jcvm.jcre.JCRE;
import jcvm.rt.BuiltinObject;
import jcvm.rt.JCThrow;
import jcvm.rt.VM;
import jcvm.uicc.CatRuntime;
import jcvm.uicc.UiccFileSystem;

/**
 * Natives for the ETSI TS 102 241 UICC API: uicc.access (file access) and
 * uicc.toolkit (registry, envelope and proactive handlers).
 *
 * Method keys are "internal/class/Name.method(descriptor)ret" and are bound by
 * name, so the tokens come from whatever export files you load with 'loadexp'.
 * Coverage is the commonly used subset; anything missing surfaces as
 *
 *     this VM does not implement uicc/toolkit/SomeClass.someMethod(...)
 *
 * and the 'missing' shell command lists all of them for a CAP up front.
 */
public final class UiccNatives {

    private UiccNatives() {
    }

    private static JCRE card(VM vm) {
        JCRE c = (JCRE) vm.card;
        if (c == null) {
            throw new JCThrow("java/lang/Error", "no card runtime attached");
        }
        return c;
    }

    private static UiccFileSystem fs(VM vm) {
        return card(vm).fileSystem;
    }

    private static CatRuntime cat(VM vm) {
        return card(vm).cat;
    }

    /* ================================================================== */

    public static void install(Natives n) {
        installAccess(n);
        installToolkit(n);
        installSimAliases(n);
    }

    /**
     * The older GSM SIM Toolkit API (sim.access / sim.toolkit) is the same
     * shape as the UICC API with different class names, so every uicc.* native
     * is registered again under its sim.* equivalent.
     */
    private static void installSimAliases(Natives n) {
        java.util.Set<String> keys = n.keys();
        for (String key : keys) {
            if (!key.startsWith("uicc/")) {
                continue;
            }
            String alias = key
                    .replace("uicc/access/FileView", "sim/access/SIMView")
                    .replace("uicc/access/UICCSystem", "sim/access/SIMSystem")
                    .replace("uicc/access/UICCException", "sim/access/SIMViewException")
                    .replace("uicc/toolkit/", "sim/toolkit/");
            if (!alias.equals(key) && !n.has(alias)) {
                n.put(alias, n.get(key));
            }
        }
        // SIMSystem exposes the view under a different name and with no argument.
        n.put("sim/access/SIMSystem.getTheSIMView()Lsim/access/SIMView;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).uiccView());
            }
        });
        // sim.access.SIMView uses seek() where uicc.access uses searchRecord().
        n.put("sim/access/SIMView.seek(B[BSS)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                try {
                    fs(vm).searchRecord(a.bytes(1), a.sh(2), a.sh(3));
                    vm.retBool(true);
                } catch (JCThrow t) {
                    vm.retBool(false);
                }
            }
        });
        exception(n, "sim/access/SIMViewException");
        exception(n, "sim/toolkit/ToolkitException");
    }

    /* ---------------- uicc.access ---------------- */

    private static void installAccess(Natives n) {
        n.put("uicc/access/UICCSystem.getTheUICCView(B)Luicc/access/FileView;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).uiccView());
            }
        });
        n.put("uicc/access/UICCSystem.getTheFileView(B)Luicc/access/FileView;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).uiccView());
            }
        });

        n.put("uicc/access/FileView.select(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).select((short) a.sh(0));
                vm.retVoid();
            }
        });
        n.put("uicc/access/FileView.select(S[BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).select((short) a.sh(0));
                vm.retShort(0);
            }
        });
        n.put("uicc/access/FileView.readBinary(S[BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).readBinary(a.sh(0), a.bytes(1), a.sh(2), a.sh(3));
                vm.retVoid();
            }
        });
        n.put("uicc/access/FileView.updateBinary(S[BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).updateBinary(a.sh(0), a.bytes(1), a.sh(2), a.sh(3));
                vm.retVoid();
            }
        });
        n.put("uicc/access/FileView.readRecord(SBS[BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).readRecord(a.sh(0), (byte) a.sh(1), a.sh(2),
                        a.bytes(3), a.sh(4), a.sh(5));
                vm.retVoid();
            }
        });
        n.put("uicc/access/FileView.updateRecord(SBS[BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).updateRecord(a.sh(0), (byte) a.sh(1), a.sh(2),
                        a.bytes(3), a.sh(4), a.sh(5));
                vm.retVoid();
            }
        });
        n.put("uicc/access/FileView.searchRecord(BSB[BSS[BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).searchRecord(a.bytes(3), a.sh(4), a.sh(5)));
            }
        });
        n.put("uicc/access/FileView.getFileSize()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).fileSize());
            }
        });
        n.put("uicc/access/FileView.getRecordLength()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).recordLength());
            }
        });
        n.put("uicc/access/FileView.getRecordNumber()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).currentRecordNumber());
            }
        });
        n.put("uicc/access/FileView.getMaxRecordSize()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).recordLength());
            }
        });
        n.put("uicc/access/FileView.getNumberOfRecords()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(fs(vm).recordCount());
            }
        });
        n.put("uicc/access/FileView.status([BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(0);
            }
        });
        n.put("uicc/access/FileView.init(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                fs(vm).reset();
                vm.retVoid();
            }
        });

        exception(n, "uicc/access/UICCException");
    }

    /* ---------------- uicc.toolkit ---------------- */

    private static void installToolkit(Natives n) {
        n.put("uicc/toolkit/ToolkitRegistrySystem.getEntry()"
                + "Luicc/toolkit/ToolkitRegistry;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).toolkitRegistryForCurrentApplet());
            }
        });
        n.put("uicc/toolkit/ToolkitRegistrySystem.getMyEntry()"
                + "Luicc/toolkit/ToolkitRegistry;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).toolkitRegistryForCurrentApplet());
            }
        });

        // TS 102 241 types events as short; the (B)V form is registered too
        // because sim.toolkit uses byte.
        n.put("uicc/toolkit/ToolkitRegistry.setEvent(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).events[eventIndex(a.sh(0))] = true;
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.clearEvent(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).events[eventIndex(a.sh(0))] = false;
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.isEventSet(S)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retBool(registry(vm, a).isEventSet(eventIndex(a.sh(0))));
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.setEventList([SSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                jcvm.rt.JCArray list = a.array(0);
                int off = a.sh(1);
                int len = a.sh(2);
                CatRuntime.Registry r = registry(vm, a);
                for (int i = 0; i < len; i++) {
                    r.events[eventIndex(list.getPrim(off + i))] = true;
                }
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.requestPollInterval(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.allocateTimer()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                CatRuntime.Registry r = registry(vm, a);
                vm.retShort(r.allocateTimer());
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.releaseTimer(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).releaseTimer(a.sh(0) & 0xFF);
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.enableMenuEntry(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.setEvent(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).events[eventIndex(a.sh(0))] = true;
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.clearEvent(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).events[eventIndex(a.sh(0))] = false;
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.isEventSet(B)Z", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retBool(registry(vm, a).isEventSet(eventIndex(a.sh(0))));
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.setEventList([BSS)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                byte[] list = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2);
                CatRuntime.Registry r = registry(vm, a);
                for (int i = 0; i < len; i++) {
                    r.events[eventIndex(list[off + i])] = true;
                }
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.initMenuEntry([BSSBZBS)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                CatRuntime.Registry r = registry(vm, a);
                byte[] text = a.bytes(0);
                int off = a.sh(1);
                int len = a.sh(2);
                int index = r.menuEntries.size();
                if (index >= r.maxMenuEntries) {
                    throw CatRuntime.toolkit(CatRuntime.REGISTRY_ERROR);
                }
                if (len > r.maxMenuTextLength) {
                    throw CatRuntime.toolkit(CatRuntime.BAD_INPUT_PARAMETER);
                }
                int reserved = r.reservedIdentifier(index);
                byte id = (byte) (reserved != 0 ? reserved : index + 1);
                r.menuEntries.put(Byte.valueOf(id), new String(text, off, len));
                r.events[CatRuntime.EVENT_MENU_SELECTION] = true;
                vm.retShort(id);
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.changeMenuEntry(B[BSSBZBS)V",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                CatRuntime.Registry r = registry(vm, a);
                r.menuEntries.put(Byte.valueOf((byte) a.sh(0)),
                        new String(a.bytes(1), a.sh(2), a.sh(3)));
                vm.retVoid();
            }
        });
        n.put("uicc/toolkit/ToolkitRegistry.disableMenuEntry(B)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                registry(vm, a).menuEntries.remove(Byte.valueOf((byte) a.sh(0)));
                vm.retVoid();
            }
        });

        /* ---- envelope handler ---- */
        n.put("uicc/toolkit/EnvelopeHandlerSystem.getTheHandler()"
                + "Luicc/toolkit/EnvelopeHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).envelopeHandler());
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.getEnvelopeTag()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).envelopeTag);
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.getLength()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).envelopeLength());
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.findTLV(BB)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).findTlv(a.sh(0) & 0xFF, a.sh(1) & 0xFF));
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.getValueLength()S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).tlvValueLength());
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.getValueByte(S)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).tlvValueByte(a.sh(0)));
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.copyValue(S[BSS)S", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).copyTlvValue(a.sh(0), a.bytes(1), a.sh(2), a.sh(3)));
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.compareValue(S[BSS)B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).compareTlvValue(a.sh(0), a.bytes(1),
                        a.sh(2), a.sh(3)));
            }
        });
        n.put("uicc/toolkit/EnvelopeHandler.getItemIdentifier()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                CatRuntime c = cat(vm);
                c.findTlv(0x10, 1);          // item identifier TLV
                vm.retShort(c.tlvValueByte(0));
            }
        });

        /* ---- proactive handler ---- */
        n.put("uicc/toolkit/ProactiveHandlerSystem.getTheHandler()"
                + "Luicc/toolkit/ProactiveHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).proactiveHandler());
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.init(BBB)Luicc/toolkit/ProactiveHandler;",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                cat(vm).proactiveInit(a.sh(0), a.sh(1), a.sh(2));
                vm.retRef(a.self);
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.appendTLV(B[BSS)"
                + "Luicc/toolkit/ProactiveHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                cat(vm).appendTlv(a.sh(0) & 0xFF, a.bytes(1), a.sh(2), a.sh(3));
                vm.retRef(a.self);
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.appendTLV(BB)"
                + "Luicc/toolkit/ProactiveHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                cat(vm).appendTlvByte(a.sh(0) & 0xFF, a.sh(1) & 0xFF);
                vm.retRef(a.self);
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.appendTLV(BB[BSS)"
                + "Luicc/toolkit/ProactiveHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                cat(vm).appendTlvBytes(a.sh(0) & 0xFF, a.sh(1) & 0xFF,
                        a.bytes(2), a.sh(3), a.sh(4));
                vm.retRef(a.self);
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.send()B", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).proactiveSend());
            }
        });
        n.put("uicc/toolkit/ProactiveHandler.initDisplayText(BB[BSS)V",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                CatRuntime c = cat(vm);
                c.proactiveInit(0x21, a.sh(0) & 0xFF, 0x02);
                c.appendTlvBytes(0x8D, a.sh(1) & 0xFF, a.bytes(2), a.sh(3), a.sh(4));
                vm.retVoid();
            }
        });

        /* ---- proactive response handler ---- */
        n.put("uicc/toolkit/ProactiveResponseHandlerSystem.getTheHandler()"
                + "Luicc/toolkit/ProactiveResponseHandler;", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retRef(card(vm).proactiveResponseHandler());
            }
        });
        n.put("uicc/toolkit/ProactiveResponseHandler.getGeneralResult()B",
                new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                vm.retShort(cat(vm).generalResult);
            }
        });

        exception(n, "uicc/toolkit/ToolkitException");
    }

    /* ------------------------------------------------------------------ */

    private static CatRuntime.Registry registry(VM vm, NativeArgs a) {
        BuiltinObject b = (BuiltinObject) a.self;
        if (!(b.state instanceof CatRuntime.Registry)) {
            throw CatRuntime.toolkit(CatRuntime.REGISTRY_ERROR);
        }
        return (CatRuntime.Registry) b.state;
    }

    private static int eventIndex(int event) {
        int e = event & 0xFF;
        if (e >= 128) {
            throw CatRuntime.toolkit(CatRuntime.BAD_INPUT_PARAMETER);
        }
        return e;
    }

    private static void exception(Natives n, final String cls) {
        n.put(cls + ".throwIt(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                throw JCThrow.framework(cls, a.sh(0) & 0xFFFF);
            }
        });
        n.put(cls + ".<init>(S)V", new NativeImpl() {
            public void invoke(VM vm, NativeArgs a) {
                ((BuiltinObject) a.self).reason = a.sh(0) & 0xFFFF;
                vm.retVoid();
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
}
