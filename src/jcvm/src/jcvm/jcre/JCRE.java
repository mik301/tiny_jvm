package jcvm.jcre;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jcvm.api.ApiClass;
import jcvm.api.ApiPackage;
import jcvm.api.ApiRegistry;
import jcvm.api.Natives;
import jcvm.cap.CapFile;
import jcvm.cap.CapPackage;
import jcvm.rt.BuiltinObject;
import jcvm.rt.ClassRt;
import jcvm.rt.JCArray;
import jcvm.rt.JCObject;
import jcvm.rt.JCThrow;
import jcvm.rt.LoadedPackage;
import jcvm.rt.MethodRt;
import jcvm.rt.PackageResolver;
import jcvm.rt.VM;
import jcvm.uicc.CatRuntime;
import jcvm.uicc.UiccFileSystem;
import jcvm.util.Hex;

/**
 * The card: it owns the loaded packages, the applet registry, the shared APDU
 * buffer and the interpreter, and it turns command APDUs into responses.
 */
public final class JCRE implements PackageResolver {

    /* status words */
    public static final int SW_NO_ERROR = 0x9000;
    public static final int SW_WRONG_LENGTH = 0x6700;
    public static final int SW_CONDITIONS_NOT_SATISFIED = 0x6985;
    public static final int SW_FILE_NOT_FOUND = 0x6A82;
    public static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    public static final int SW_CLA_NOT_SUPPORTED = 0x6E00;
    public static final int SW_UNKNOWN = 0x6F00;

    private static final String APPLET_CLASS = "javacard/framework/Applet";

    public final ApiRegistry api = new ApiRegistry();
    public final Natives natives = Natives.standard();
    public final VM vm;

    public final List<LoadedPackage> packages = new ArrayList<LoadedPackage>();
    public final List<AppletInstance> applets = new ArrayList<AppletInstance>();

    private AppletInstance selected;

    /** The Issuer Security Domain, i.e. the card manager application. */
    public final jcvm.gp.CardManager cardManager = new jcvm.gp.CardManager(this);
    /** True when the card manager rather than an applet is selected. */
    public boolean cardManagerSelected = true;

    public final byte[] apduBuffer = new byte[261];
    public final JCArray apduBufferArray;
    public final ApduState apduState;
    public final BuiltinObject apduObject;

    /* UICC / CAT support */
    public final UiccFileSystem fileSystem = new UiccFileSystem();
    public final CatRuntime cat = new CatRuntime();
    private BuiltinObject uiccViewObject;
    private BuiltinObject envelopeHandlerObject;
    private BuiltinObject proactiveHandlerObject;
    private BuiltinObject proactiveResponseObject;
    private final java.util.Map<Object, BuiltinObject> toolkitRegistries =
            new java.util.HashMap<Object, BuiltinObject>();
    /** The applet whose code is currently running, for getEntry()/getAID(). */
    private Object contextApplet;
    /** Toolkit configuration from INSTALL, applied to the registry it creates. */
    public jcvm.gp.InstallParams.ToolkitParams pendingToolkitParams;

    private final List<JCArray> transients = new ArrayList<JCArray>();

    /* install / select state shared with the natives */
    private byte[] pendingInstanceAid;
    private LoadedPackage installingPackage;
    private AppletInstance justRegistered;
    private AppletInstance selectingInstance;

    private int tokenSelect = -1;
    private int tokenProcess = -1;
    private int tokenDeselect = -1;

    public JCRE(File tokenTable) throws IOException {
        api.load(tokenTable);
        vm = new VM(api, natives);
        vm.card = this;
        apduBufferArray = JCArray.wrap(apduBuffer);
        apduState = new ApduState(apduBuffer, apduBufferArray);
        apduObject = new BuiltinObject("javacard/framework/APDU");
        apduObject.state = apduState;
        resolveAppletTokens();
    }

    /** Re-reads the Applet lifecycle tokens, e.g. after loading export files. */
    public void refreshAppletTokens() {
        resolveAppletTokens();
    }

    private void resolveAppletTokens() {
        ApiClass applet = api.classByName(APPLET_CLASS);
        if (applet == null) {
            throw new IllegalStateException("api-tokens.txt does not describe "
                    + APPLET_CLASS);
        }
        tokenSelect = applet.virtualTokenOf("select()Z");
        tokenProcess = applet.virtualTokenOf("process(Ljavacard/framework/APDU;)V");
        tokenDeselect = applet.virtualTokenOf("deselect()V");
        if (tokenProcess < 0) {
            throw new IllegalStateException("api-tokens.txt has no virtual token for "
                    + "Applet.process(Ljavacard/framework/APDU;)V");
        }
    }

    /* ------------------------------------------------------------------ */
    /* PackageResolver                                                     */
    /* ------------------------------------------------------------------ */

    public LoadedPackage findLoaded(byte[] aid) {
        for (int i = 0; i < packages.size(); i++) {
            if (java.util.Arrays.equals(packages.get(i).aid, aid)) {
                return packages.get(i);
            }
        }
        return null;
    }

    public ApiPackage findApi(byte[] aid) {
        return api.packageByAid(aid);
    }

    public int instanceSizeOf(String internalClassName) {
        return api.instanceSizeOf(internalClassName);
    }

    /* ------------------------------------------------------------------ */
    /* loading                                                             */
    /* ------------------------------------------------------------------ */

    public LoadedPackage loadCap(File f) throws IOException {
        return install(CapFile.load(f));
    }

    public LoadedPackage loadComponentStream(byte[] raw) {
        return install(CapFile.fromComponentStream(raw));
    }

    private LoadedPackage install(CapFile cf) {
        CapPackage cp = CapPackage.parse(cf);
        if (findLoaded(cp.thisPackage.aid) != null) {
            throw new IllegalStateException("package " + cp.thisPackage.aidHex()
                    + " is already loaded");
        }
        LoadedPackage lp = new LoadedPackage(cp, this);
        lp.link();
        packages.add(lp);
        return lp;
    }

    /* ------------------------------------------------------------------ */
    /* installation                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the applet's static install method, exactly as a card does after
     * INSTALL [for install and make selectable].
     */
    public AppletInstance installApplet(byte[] moduleAid, byte[] instanceAid,
            byte[] parameters) {
        LoadedPackage owner = null;
        CapPackage.AppletInfo info = null;
        for (int i = 0; i < packages.size() && info == null; i++) {
            LoadedPackage p = packages.get(i);
            for (int j = 0; j < p.cap.applets.size(); j++) {
                if (java.util.Arrays.equals(p.cap.applets.get(j).aid, moduleAid)) {
                    owner = p;
                    info = p.cap.applets.get(j);
                    break;
                }
            }
        }
        if (info == null) {
            throw new IllegalArgumentException("no applet module with AID "
                    + Hex.toHex(moduleAid) + " in any loaded package");
        }
        if (findApplet(instanceAid) != null) {
            throw new IllegalStateException("an applet with AID "
                    + Hex.toHex(instanceAid) + " is already installed");
        }

        byte[] params = parameters == null ? new byte[0] : parameters;
        byte[] buf = new byte[1 + instanceAid.length + 2 + 1 + params.length];
        int p = 0;
        buf[p++] = (byte) instanceAid.length;
        System.arraycopy(instanceAid, 0, buf, p, instanceAid.length);
        p += instanceAid.length;
        buf[p++] = 1;      // privileges length
        buf[p++] = 0;      // privileges
        buf[p++] = (byte) params.length;
        System.arraycopy(params, 0, buf, p, params.length);

        JCArray arg = JCArray.wrap(buf);
        MethodRt m = owner.method(info.installMethodOffset);
        if (m.nargs < 3) {
            throw new IllegalStateException("install method at offset "
                    + info.installMethodOffset + " does not look like "
                    + "install(byte[],short,byte)");
        }

        pendingInstanceAid = instanceAid;
        installingPackage = owner;
        justRegistered = null;
        try {
            int[] words = new int[m.nargs];
            Object[] refs = new Object[m.nargs];
            refs[0] = arg;
            words[1] = 0;
            words[2] = buf.length;
            vm.call(m, words, refs);
        } finally {
            vm.transaction.rollbackIfActive();
            pendingInstanceAid = null;
            installingPackage = null;
            contextApplet = null;
        }
        if (justRegistered == null) {
            throw new IllegalStateException("the applet did not call register()");
        }
        AppletInstance created = justRegistered;
        justRegistered = null;
        return created;
    }

    /** Called by Applet.register(). */
    public void registerApplet(Object self, byte[] aid) {
        if (!(self instanceof JCObject)) {
            throw new JCThrow("java/lang/Error", "register() on a non applet object");
        }
        byte[] use = aid != null ? aid : pendingInstanceAid;
        if (use == null) {
            throw JCThrow.framework(JCThrow.SYSTEM_EXCEPTION, 4); // ILLEGAL_AID
        }
        if (findApplet(use) != null) {
            throw JCThrow.framework(JCThrow.SYSTEM_EXCEPTION, 4);
        }
        LoadedPackage pkg = installingPackage != null
                ? installingPackage : ((JCObject) self).clazz.pkg;
        AppletInstance inst = new AppletInstance(use, (JCObject) self, pkg);
        applets.add(inst);
        justRegistered = inst;
        contextApplet = self;
    }

    /* ------------------------------------------------------------------ */
    /* selection                                                           */
    /* ------------------------------------------------------------------ */

    public AppletInstance findApplet(byte[] aid) {
        for (int i = 0; i < applets.size(); i++) {
            if (java.util.Arrays.equals(applets.get(i).aid, aid)) {
                return applets.get(i);
            }
        }
        return null;
    }

    /** Exact match first, then the longest AID that starts with the given bytes. */
    public AppletInstance findAppletByPrefix(byte[] aid) {
        AppletInstance exact = findApplet(aid);
        if (exact != null) {
            return exact;
        }
        AppletInstance best = null;
        for (int i = 0; i < applets.size(); i++) {
            AppletInstance a = applets.get(i);
            if (a.aid.length < aid.length) {
                continue;
            }
            boolean match = true;
            for (int j = 0; j < aid.length; j++) {
                if (a.aid[j] != aid[j]) {
                    match = false;
                    break;
                }
            }
            if (match && (best == null || a.aid.length < best.aid.length)) {
                best = a;
            }
        }
        return best;
    }

    public AppletInstance selectedApplet() {
        return selected;
    }

    /** True while the SELECT that is activating this applet is being processed. */
    public boolean isSelecting(Object appletObject) {
        return selectingInstance != null && selectingInstance.object == appletObject;
    }

    public BuiltinObject currentAidObject() {
        return selected != null ? selected.aidObject : null;
    }

    public BuiltinObject lookupAid(byte[] aid) {
        AppletInstance a = findApplet(aid);
        return a == null ? null : a.aidObject;
    }

    private void deselectCurrent() {
        if (selected == null) {
            return;
        }
        try {
            if (tokenDeselect < 0) {
                return;
            }
            ClassRt.VirtualTarget t = selected.object.clazz.lookupVirtual(tokenDeselect);
            if (!t.isNative()) {
                vm.callVirtual(selected.object, tokenDeselect, null, null);
            }
        } catch (RuntimeException ignored) {
            // deselect must not prevent the next selection
        } finally {
            vm.transaction.rollbackIfActive();
            clearTransients(JCArray.CLEAR_ON_DESELECT);
            selected = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* APDU exchange                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Sends a command APDU and returns the response including the status word.
     *
     * Routing mirrors a real card: SELECT by DF name picks either the card
     * manager or an installed applet, and everything else goes to whatever is
     * currently selected.
     */
    public byte[] transmit(byte[] command) {
        if (command == null || command.length < 4) {
            return sw(SW_WRONG_LENGTH);
        }
        int cla = command[0] & 0xFF;
        int ins = command[1] & 0xFF;
        int p1 = command[2] & 0xFF;

        if ((cla & 0x80) == 0 && ins == 0xA4 && p1 == 0x04) {
            return selectByName(command);
        }
        if (cardManagerSelected) {
            return cardManager.process(command);
        }
        if (selected == null) {
            return sw(SW_CONDITIONS_NOT_SATISFIED);
        }
        return runProcess(command, null);
    }

    private byte[] selectByName(byte[] command) {
        int lc = command.length > 4 ? (command[4] & 0xFF) : 0;
        if (command.length < 5 + lc) {
            return sw(SW_WRONG_LENGTH);
        }
        byte[] aid = new byte[lc];
        System.arraycopy(command, 5, aid, 0, lc);

        // An empty AID, or the ISD's own AID, selects the card manager.
        if (lc == 0 || cardManager.matches(aid)) {
            deselectCurrent();
            cardManagerSelected = true;
            log("SELECT card manager");
            return cardManager.selectResponse();
        }

        AppletInstance target = findAppletByPrefix(aid);
        if (target == null) {
            return sw(SW_FILE_NOT_FOUND);
        }
        deselectCurrent();
        cardManagerSelected = false;

        try {
            ClassRt.VirtualTarget t = tokenSelect < 0 ? null
                    : target.object.clazz.lookupVirtual(tokenSelect);
            if (t != null && !t.isNative()) {
                selectingInstance = target;
                vm.callVirtual(target.object, tokenSelect, null, null);
                boolean ok = vm.resultKind() != VM.RET_WORD || vm.resultWord() != 0;
                if (!ok) {
                    selectingInstance = null;
                    return sw(SW_CONDITIONS_NOT_SATISFIED);
                }
            }
        } catch (JCThrow t) {
            selectingInstance = null;
            return sw(statusWordFor(t));
        } finally {
            vm.transaction.rollbackIfActive();
        }
        selected = target;
        log("SELECT applet " + target.aidHex());
        return runProcess(command, target);
    }

    /** Runs Applet.process; {@code selectingTarget} is non null for a SELECT. */
    private byte[] runProcess(byte[] command, AppletInstance selectingTarget) {
        apduState.begin(command);
        selectingInstance = selectingTarget;
        int status = SW_NO_ERROR;
        contextApplet = selected.object;
        try {
            vm.callVirtual(selected.object, tokenProcess, new int[]{0},
                    new Object[]{apduObject});
        } catch (JCThrow t) {
            status = statusWordFor(t);
        } catch (RuntimeException e) {
            status = SW_UNKNOWN;
            lastError = e;
        } finally {
            selectingInstance = null;
            contextApplet = null;
            vm.transaction.rollbackIfActive();
        }
        byte[] data = apduState.responseData();
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) (status >> 8);
        out[data.length + 1] = (byte) status;
        return out;
    }

    /** Last unexpected VM error, useful for debugging a 6F00. */
    public RuntimeException lastError;

    private int statusWordFor(JCThrow t) {
        if (JCThrow.ISO_EXCEPTION.equals(t.className)) {
            return t.reason & 0xFFFF;
        }
        lastError = t;
        return SW_UNKNOWN;
    }

    private static byte[] sw(int status) {
        return new byte[]{(byte) (status >> 8), (byte) status};
    }

    /**
     * Delivers a STORE DATA command to an application without changing what is
     * selected, as personalization does on a real card.
     */
    public byte[] personalize(byte[] appAid, byte[] command) {
        AppletInstance target = findApplet(appAid);
        if (target == null) {
            return sw(SW_FILE_NOT_FOUND);
        }
        AppletInstance previous = selected;
        boolean previousCm = cardManagerSelected;
        selected = target;
        cardManagerSelected = false;
        try {
            return runProcess(command, null);
        } finally {
            selected = previous;
            cardManagerSelected = previousCm;
        }
    }

    /** True when another loaded package imports this one. */
    public LoadedPackage packageDependingOn(LoadedPackage target) {
        for (int i = 0; i < packages.size(); i++) {
            LoadedPackage p = packages.get(i);
            if (p == target || p.imports == null) {
                continue;
            }
            for (int j = 0; j < p.imports.length; j++) {
                if (p.imports[j] == target) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Removes a loaded package, used by GP DELETE of a load file. */
    public boolean unloadPackage(byte[] aid) {
        LoadedPackage p = findLoaded(aid);
        if (p == null) {
            return false;
        }
        packages.remove(p);
        return true;
    }

    /** Removes an installed applet, used by GP DELETE. */
    public boolean deleteApplet(byte[] aid) {
        AppletInstance a = findApplet(aid);
        if (a == null) {
            return false;
        }
        if (a == selected) {
            deselectCurrent();
        }
        applets.remove(a);
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* trace log                                                           */
    /* ------------------------------------------------------------------ */

    /** Where the card manager narrates what it is doing. */
    public java.io.PrintStream log = System.out;
    public boolean verbose = true;

    public void log(String message) {
        if (verbose && log != null) {
            log.println("   [gp] " + message);
        }
    }

    /** Prints an already formatted, possibly multi line block. */
    public void logRaw(String block) {
        if (verbose && log != null && block != null && block.length() > 0) {
            log.print(block);
        }
    }

    /* ------------------------------------------------------------------ */
    /* UICC / CAT                                                          */
    /* ------------------------------------------------------------------ */

    public BuiltinObject uiccView() {
        if (uiccViewObject == null) {
            uiccViewObject = new BuiltinObject("uicc/access/FileView");
        }
        return uiccViewObject;
    }

    public BuiltinObject envelopeHandler() {
        if (envelopeHandlerObject == null) {
            envelopeHandlerObject = new BuiltinObject("uicc/toolkit/EnvelopeHandler");
        }
        return envelopeHandlerObject;
    }

    public BuiltinObject proactiveHandler() {
        if (proactiveHandlerObject == null) {
            proactiveHandlerObject = new BuiltinObject("uicc/toolkit/ProactiveHandler");
        }
        return proactiveHandlerObject;
    }

    public BuiltinObject proactiveResponseHandler() {
        if (proactiveResponseObject == null) {
            proactiveResponseObject =
                    new BuiltinObject("uicc/toolkit/ProactiveResponseHandler");
        }
        return proactiveResponseObject;
    }

    /** The applet object whose bytecode is currently executing. */
    public Object currentApplet() {
        if (contextApplet != null) {
            return contextApplet;
        }
        return selected != null ? selected.object : null;
    }

    /** ToolkitRegistrySystem.getEntry() for whoever is running. */
    public BuiltinObject toolkitRegistryForCurrentApplet() {
        Object applet = currentApplet();
        if (applet == null) {
            throw JCThrow.framework("uicc/toolkit/ToolkitException", 5); // REGISTRY_ERROR
        }
        BuiltinObject reg = toolkitRegistries.get(applet);
        if (reg == null) {
            reg = new BuiltinObject("uicc/toolkit/ToolkitRegistry");
            CatRuntime.Registry entry = cat.registryFor(applet);
            entry.configure(pendingToolkitParams);
            reg.state = entry;
            toolkitRegistries.put(applet, reg);
        }
        return reg;
    }

    /** Method token of ToolkitInterface.processToolkit, or -1. */
    private static final String[] TOOLKIT_INTERFACES = {
        "uicc/toolkit/ToolkitInterface", "sim/toolkit/ToolkitInterface"
    };

    private String toolkitInterfaceName = TOOLKIT_INTERFACES[0];

    private int toolkitProcessToken() {
        for (int i = 0; i < TOOLKIT_INTERFACES.length; i++) {
            ApiClass ac = api.classByName(TOOLKIT_INTERFACES[i]);
            if (ac != null) {
                int t = ac.virtualTokenOf("processToolkit(B)V");
                if (t >= 0) {
                    toolkitInterfaceName = TOOLKIT_INTERFACES[i];
                    return t;
                }
            }
        }
        return -1;
    }

    /**
     * Delivers an event to every applet registered for it, as the CAT runtime
     * does. Returns the number of applets triggered.
     */
    public int triggerEvent(byte event, byte[] envelopeData) {
        int ifaceToken = toolkitProcessToken();
        if (ifaceToken < 0) {
            throw new IllegalStateException("no token for "
                    + "ToolkitInterface.processToolkit(B)V - "
                    + "run 'loadexp' on your UICC export files first");
        }
        cat.setEnvelope(envelopeData == null ? new byte[0] : envelopeData);
        cat.currentEvent = event;
        cat.clearIssued();

        int triggered = 0;
        List<CatRuntime.Registry> regs = cat.registries();
        for (int i = 0; i < regs.size(); i++) {
            CatRuntime.Registry r = regs.get(i);
            if (!r.isEventSet(event & 0xFF)) {
                continue;
            }
            if (!(r.applet instanceof JCObject)) {
                continue;
            }
            JCObject obj = (JCObject) r.applet;
            int vtoken = obj.clazz.interfaceToken(null,
                    toolkitInterfaceName, ifaceToken);
            if (vtoken < 0) {
                continue;
            }
            contextApplet = obj;
            try {
                vm.callVirtual(obj, vtoken, new int[]{event}, new Object[]{null});
                triggered++;
            } catch (JCThrow t) {
                lastError = t;
            } catch (RuntimeException e) {
                lastError = e;
            } finally {
                contextApplet = null;
                vm.transaction.rollbackIfActive();
            }
        }
        return triggered;
    }

    /* ------------------------------------------------------------------ */
    /* transient memory and reset                                          */
    /* ------------------------------------------------------------------ */

    public JCArray makeTransient(int type, int length, byte event) {
        if (length < 0) {
            throw JCThrow.framework(JCThrow.SYSTEM_EXCEPTION, 1); // ILLEGAL_VALUE
        }
        if (event != JCArray.CLEAR_ON_RESET && event != JCArray.CLEAR_ON_DESELECT) {
            throw JCThrow.framework(JCThrow.SYSTEM_EXCEPTION, 1);
        }
        JCArray a = new JCArray(type, length);
        a.transientKind = event;
        transients.add(a);
        return a;
    }

    private void clearTransients(byte kind) {
        for (int i = 0; i < transients.size(); i++) {
            JCArray a = transients.get(i);
            if (a.transientKind == kind) {
                a.clear();
            }
        }
    }

    /** Card reset: deselects and clears CLEAR_ON_RESET and CLEAR_ON_DESELECT data. */
    public void reset() {
        deselectCurrent();
        clearTransients(JCArray.CLEAR_ON_RESET);
        clearTransients(JCArray.CLEAR_ON_DESELECT);
        vm.transaction.rollbackIfActive();
        fileSystem.reset();
        cardManagerSelected = true;
    }
}
