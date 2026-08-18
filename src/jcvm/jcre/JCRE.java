package jcvm.jcre;

import java.io.ByteArrayOutputStream;
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

    public final byte[] apduBuffer = new byte[261];
    public final JCArray apduBufferArray;
    public final ApduState apduState;
    public final BuiltinObject apduObject;

    private final List<JCArray> transients = new ArrayList<JCArray>();
    private final ByteArrayOutputStream loadBuffer = new ByteArrayOutputStream();

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

    /** Sends a command APDU and returns the response including the status word. */
    public byte[] transmit(byte[] command) {
        if (command == null || command.length < 4) {
            return sw(SW_WRONG_LENGTH);
        }
        int cla = command[0] & 0xFF;
        int ins = command[1] & 0xFF;
        int p1 = command[2] & 0xFF;

        if ((cla & 0xF0) == 0x80) {
            if (ins == 0xE6) {
                return cardManagerInstall(command);
            }
            if (ins == 0xE8) {
                return cardManagerLoad(command);
            }
            if (ins == 0xF2) {
                return getStatus();
            }
        }
        if ((cla & 0x80) == 0 && ins == 0xA4 && p1 == 0x04) {
            return selectByName(command);
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
        AppletInstance target = findAppletByPrefix(aid);
        if (target == null) {
            return sw(SW_FILE_NOT_FOUND);
        }
        deselectCurrent();
        // Applet.select() may veto the selection.
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
        return runProcess(command, target);
    }

    /** Runs Applet.process; {@code selectingTarget} is non null for a SELECT. */
    private byte[] runProcess(byte[] command, AppletInstance selectingTarget) {
        apduState.begin(command);
        selectingInstance = selectingTarget;
        int status = SW_NO_ERROR;
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

    /* ------------------------------------------------------------------ */
    /* minimal card manager                                                */
    /* ------------------------------------------------------------------ */

    private byte[] cardManagerLoad(byte[] command) {
        int p1 = command[2] & 0xFF;
        int lc = command.length > 4 ? (command[4] & 0xFF) : 0;
        if (command.length < 5 + lc) {
            return sw(SW_WRONG_LENGTH);
        }
        loadBuffer.write(command, 5, lc);
        if ((p1 & 0x80) != 0) {
            byte[] raw = loadBuffer.toByteArray();
            loadBuffer.reset();
            try {
                loadComponentStream(raw);
            } catch (RuntimeException e) {
                lastError = e;
                return sw(SW_UNKNOWN);
            }
        }
        return sw(SW_NO_ERROR);
    }

    private byte[] cardManagerInstall(byte[] command) {
        int p1 = command[2] & 0xFF;
        int lc = command.length > 4 ? (command[4] & 0xFF) : 0;
        if (command.length < 5 + lc) {
            return sw(SW_WRONG_LENGTH);
        }
        if ((p1 & 0x02) != 0) {          // INSTALL [for load]
            loadBuffer.reset();
            return sw(SW_NO_ERROR);
        }
        if ((p1 & 0x04) == 0) {          // not INSTALL [for install]
            return sw(SW_INS_NOT_SUPPORTED);
        }
        int p = 5;
        int end = 5 + lc;
        try {
            byte[] loadFileAid = readLv(command, p, end);
            p += 1 + loadFileAid.length;
            byte[] moduleAid = readLv(command, p, end);
            p += 1 + moduleAid.length;
            byte[] instanceAid = readLv(command, p, end);
            p += 1 + instanceAid.length;
            byte[] privileges = readLv(command, p, end);
            p += 1 + privileges.length;
            byte[] params = readLv(command, p, end);
            if (instanceAid.length == 0) {
                instanceAid = moduleAid;
            }
            installApplet(moduleAid, instanceAid, params);
            return sw(SW_NO_ERROR);
        } catch (RuntimeException e) {
            lastError = e;
            return sw(SW_UNKNOWN);
        }
    }

    private static byte[] readLv(byte[] a, int off, int end) {
        if (off >= end) {
            return new byte[0];
        }
        int len = a[off] & 0xFF;
        if (off + 1 + len > end) {
            throw new IllegalArgumentException("truncated length/value field");
        }
        byte[] out = new byte[len];
        System.arraycopy(a, off + 1, out, 0, len);
        return out;
    }

    private byte[] getStatus() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < applets.size(); i++) {
            byte[] aid = applets.get(i).aid;
            bos.write(aid.length);
            bos.write(aid, 0, aid.length);
            bos.write(0x07); // life cycle: SELECTABLE
            bos.write(0x00); // privileges
        }
        byte[] data = bos.toByteArray();
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) 0x90;
        out[data.length + 1] = 0x00;
        return out;
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
        loadBuffer.reset();
    }
}
