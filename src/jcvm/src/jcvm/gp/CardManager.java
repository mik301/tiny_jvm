package jcvm.gp;

import java.io.ByteArrayOutputStream;
import java.util.List;

import jcvm.jcre.AppletInstance;
import jcvm.jcre.JCRE;
import jcvm.rt.LoadedPackage;
import jcvm.util.Hex;

/**
 * The Issuer Security Domain, i.e. the card manager.
 *
 * This is the application the terminal talks to before any applet exists. It
 * owns the GlobalPlatform registry, receives the CAP over LOAD commands, hands
 * the assembled Load File Data Block to the JCRE's loader/linker, and then
 * drives the applet's install() method on INSTALL [for install].
 *
 * The command set implemented here is the plaintext subset: there is no SCP02
 * or SCP03 cryptography, so INITIALIZE UPDATE and EXTERNAL AUTHENTICATE are
 * accepted without verifying anything. Everything else - the data formats, the
 * registry, the life cycle transitions - follows GlobalPlatform.
 */
public final class CardManager {

    /* INSTALL P1 bits */
    private static final int P1_FOR_LOAD = 0x02;
    private static final int P1_FOR_INSTALL = 0x04;
    private static final int P1_FOR_MAKE_SELECTABLE = 0x08;
    private static final int P1_FOR_EXTRADITION = 0x10;
    private static final int P1_FOR_REGISTRY_UPDATE = 0x20;
    private static final int P1_FOR_PERSONALIZATION = 0x40;
    private static final int P1_MORE_COMMANDS = 0x80;

    /* status words */
    private static final int SW_OK = 0x9000;
    private static final int SW_WRONG_LENGTH = 0x6700;
    private static final int SW_CONDITIONS = 0x6985;
    private static final int SW_WRONG_DATA = 0x6A80;
    private static final int SW_NOT_ENOUGH_MEMORY = 0x6A84;
    private static final int SW_INCORRECT_P1P2 = 0x6A86;
    private static final int SW_REFERENCED_DATA_NOT_FOUND = 0x6A88;
    private static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    private static final int SW_UNKNOWN = 0x6F00;

    public static final byte[] DEFAULT_ISD_AID = Hex.parse("A000000151000000");

    private final JCRE card;
    public final GpRegistry registry = new GpRegistry();
    public final byte[] aid;

    /** Set once EXTERNAL AUTHENTICATE has been accepted. */
    public boolean authenticated;

    /** Application selected by INSTALL [for personalization], if any. */
    private byte[] personalizationTarget;
    private int storeDataBlock;
    /** Key version numbers present on the card, for DELETE [key]. */
    private final java.util.Set<Integer> keyVersions = new java.util.TreeSet<Integer>();

    /* load session state */
    private final ByteArrayOutputStream loadBuffer = new ByteArrayOutputStream();
    private byte[] pendingElfAid;
    private int expectedBlock;
    private boolean loading;

    public CardManager(JCRE card) {
        this(card, DEFAULT_ISD_AID);
    }

    public CardManager(JCRE card, byte[] aid) {
        this.card = card;
        this.aid = aid;
        registry.addIsd(aid);
        keyVersions.add(Integer.valueOf(0xFF));   // the default key set
    }

    public boolean matches(byte[] candidate) {
        if (candidate.length == 0) {
            return true;                      // implicit selection of the ISD
        }
        if (candidate.length > aid.length) {
            return false;
        }
        for (int i = 0; i < candidate.length; i++) {
            if (aid[i] != candidate[i]) {
                return false;
            }
        }
        return true;
    }

    /** FCI returned when the ISD is selected. */
    public byte[] selectResponse() {
        ByteArrayOutputStream fci = new ByteArrayOutputStream();
        fci.write(0x6F);                          // FCI template
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x84);                         // application AID
        body.write(aid.length);
        body.write(aid, 0, aid.length);
        body.write(0xA5);                         // proprietary data
        byte[] prop = new byte[]{(byte) 0x9F, 0x65, 0x01, (byte) 0xFF};
        body.write(prop.length);
        body.write(prop, 0, prop.length);
        byte[] b = body.toByteArray();
        fci.write(b.length);
        fci.write(b, 0, b.length);
        return withSw(fci.toByteArray(), SW_OK);
    }

    /* ================================================================== */

    public byte[] process(byte[] c) {
        int ins = c[1] & 0xFF;
        try {
            switch (ins) {
                case 0x50:
                    return initializeUpdate(c);
                case 0x82:
                    return externalAuthenticate(c);
                case 0xE6:
                    return install(c);
                case 0xE8:
                    return load(c);
                case 0xE4:
                    return delete(c);
                case 0xF2:
                    return getStatus(c);
                case 0xCA:
                    return getData(c);
                case 0xE2:
                    return storeData(c);
                case 0xF0:
                    return setStatus(c);
                default:
                    return sw(SW_INS_NOT_SUPPORTED);
            }
        } catch (GpException e) {
            card.lastError = e;
            card.log("-> " + e.getMessage());
            return sw(e.statusWord);
        } catch (RuntimeException e) {
            card.lastError = e;
            return sw(SW_UNKNOWN);
        }
    }

    /* ---------------- secure channel (accepted, not verified) ---------- */

    private byte[] initializeUpdate(byte[] c) {
        // key diversification data (10) + key info (2) + card challenge (8)
        // + card cryptogram (8); all fixed, since no SCP keys exist here.
        byte[] r = new byte[28];
        for (int i = 0; i < 10; i++) {
            r[i] = (byte) (0x10 + i);
        }
        r[10] = (byte) 0xFF;   // key version
        r[11] = 0x02;          // SCP02
        for (int i = 12; i < 28; i++) {
            r[i] = (byte) (0xA0 + i);
        }
        return withSw(r, SW_OK);
    }

    private byte[] externalAuthenticate(byte[] c) {
        authenticated = true;
        registry.cardLifeCycle = GpRegistry.CARD_SECURED;
        return sw(SW_OK);
    }

    /* ---------------- INSTALL ---------------- */

    private byte[] install(byte[] c) {
        int rawP1 = c[2] & 0xFF;
        // Bit 8 of P1 means "more INSTALL commands follow"; it is not a variant,
        // so 84 is still [for install] and 8C still [for install and make
        // selectable]. Mask it off before deciding what to do.
        boolean moreCommands = (rawP1 & P1_MORE_COMMANDS) != 0;
        int p1 = rawP1 & ~P1_MORE_COMMANDS;
        if (moreCommands) {
            card.log("INSTALL P1=" + String.format("%02X", Integer.valueOf(rawP1))
                    + ": more INSTALL commands follow");
        }
        byte[] data = body(c);
        Tlv in = new Tlv(data);

        if ((p1 & P1_FOR_LOAD) != 0) {
            byte[] elfAid = in.lv("load file AID");
            in.lv("security domain AID");
            in.lv("load file data block hash");
            byte[] loadParams = in.lvOrEmpty();
            in.lvOrEmpty();                       // load token

            if (registry.find(elfAid) != null) {
                throw new GpException(SW_CONDITIONS,
                        "a load file with AID " + Hex.toHex(elfAid) + " already exists");
            }
            pendingElfAid = elfAid;
            loadBuffer.reset();
            expectedBlock = 0;
            loading = true;
            card.log("INSTALL [for load] " + Hex.toHex(elfAid)
                    + (loadParams.length > 0 ? "  params " + Hex.toHex(loadParams) : ""));
            return sw(SW_OK);
        }

        if ((p1 & P1_FOR_INSTALL) != 0) {
            byte[] elfAid = in.lv("load file AID");
            byte[] moduleAid = in.lv("executable module AID");
            byte[] instanceAid = in.lvOrEmpty();
            byte[] privileges = in.lvOrEmpty();
            byte[] installParams = in.lvOrEmpty();
            in.lvOrEmpty();                       // install token

            if (instanceAid.length == 0) {
                instanceAid = moduleAid;
            }
            if (registry.find(elfAid, GpRegistry.TYPE_ELF) == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        "no load file " + Hex.toHex(elfAid) + " on the card");
            }
            if (registry.find(moduleAid, GpRegistry.TYPE_MODULE) == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        "no executable module " + Hex.toHex(moduleAid));
            }
            if (registry.find(instanceAid) != null) {
                throw new GpException(SW_CONDITIONS,
                        "AID " + Hex.toHex(instanceAid) + " is already in use");
            }

            InstallParams params = InstallParams.parse(installParams);
            byte priv = privileges.length > 0 ? privileges[0] : 0;

            card.log("INSTALL [for install] module " + Hex.toHex(moduleAid)
                    + " -> instance " + Hex.toHex(instanceAid));
            if (installParams.length > 0) {
                card.logRaw(params.describe());
            }

            // Toolkit configuration is applied before install() runs, because
            // the applet calls initMenuEntry from inside its constructor.
            card.pendingToolkitParams = params.toolkit;

            AppletInstance created;
            try {
                created = card.installApplet(moduleAid, instanceAid, params.appParams);
            } catch (RuntimeException e) {
                card.lastError = e;
                throw new GpException(SW_CONDITIONS,
                        "the applet's install() failed: " + e, e);
            } finally {
                card.pendingToolkitParams = null;
            }
            boolean selectable = (p1 & P1_FOR_MAKE_SELECTABLE) != 0;
            GpRegistry.Entry entry = registry.find(created.aid);
            if (entry == null) {
                entry = registry.addApplication(created.aid, elfAid, priv, selectable);
            } else {
                entry.privileges = priv;
                entry.elfAid = elfAid;
                entry.lifeCycle = selectable
                        ? GpRegistry.APP_SELECTABLE : GpRegistry.APP_INSTALLED;
            }
            if (!java.util.Arrays.equals(created.aid, instanceAid)) {
                card.log("  note: the applet registered as " + Hex.toHex(created.aid)
                        + " rather than the requested " + Hex.toHex(instanceAid));
            }

            // GP echoes the installed application AID
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            r.write(created.aid.length);
            r.write(created.aid, 0, created.aid.length);
            return withSw(r.toByteArray(), SW_OK);
        }

        if ((p1 & P1_FOR_EXTRADITION) != 0) {
            byte[] sdAid = in.lv("security domain AID");
            in.lvOrEmpty();                                   // reserved, empty
            byte[] appAid = in.lv("application AID");
            GpRegistry.Entry target = registry.find(appAid);
            if (target == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        "nothing with AID " + Hex.toHex(appAid) + " to extradite");
            }
            GpRegistry.Entry sd = registry.find(sdAid);
            if (sd == null || (sd.type != GpRegistry.TYPE_ISD
                    && (sd.privileges & 0x80) == 0)) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        Hex.toHex(sdAid) + " is not a security domain");
            }
            target.associatedSd = sdAid;
            card.log("INSTALL [for extradition] " + Hex.toHex(appAid)
                    + " -> " + Hex.toHex(sdAid));
            return sw(SW_OK);
        }

        if ((p1 & P1_FOR_REGISTRY_UPDATE) != 0) {
            byte[] sdAid = in.lvOrEmpty();
            in.lvOrEmpty();                                   // reserved, empty
            byte[] appAid = in.lv("application AID");
            byte[] privileges = in.lvOrEmpty();
            byte[] updateParams = in.lvOrEmpty();
            GpRegistry.Entry e = registry.find(appAid);
            if (e == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        "no registry entry for " + Hex.toHex(appAid));
            }
            if (sdAid.length > 0) {
                e.associatedSd = sdAid;
            }
            if (privileges.length > 0) {
                e.privileges = privileges[0];
            }
            if (updateParams.length > 0) {
                card.logRaw(InstallParams.parse(updateParams).describe());
            }
            card.log("INSTALL [for registry update] " + Hex.toHex(appAid));
            return sw(SW_OK);
        }

        if ((p1 & P1_FOR_PERSONALIZATION) != 0) {
            in.lvOrEmpty();                                   // empty
            in.lvOrEmpty();                                   // empty
            byte[] appAid = in.lv("application AID");
            GpRegistry.Entry e = registry.find(appAid, GpRegistry.TYPE_APPLICATION);
            if (e == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                        "no application " + Hex.toHex(appAid) + " to personalize");
            }
            personalizationTarget = e.aid;
            storeDataBlock = 0;
            card.log("INSTALL [for personalization] " + Hex.toHex(appAid)
                    + " - STORE DATA now goes to that applet");
            return sw(SW_OK);
        }

        if ((p1 & P1_FOR_MAKE_SELECTABLE) != 0) {
            byte[] appAid = in.lvOrEmpty();
            if (appAid.length == 0) {
                appAid = in.lvOrEmpty();
            }
            GpRegistry.Entry e = registry.find(appAid, GpRegistry.TYPE_APPLICATION);
            if (e == null) {
                throw new GpException(SW_REFERENCED_DATA_NOT_FOUND, "no such application");
            }
            e.lifeCycle = GpRegistry.APP_SELECTABLE;
            card.log("INSTALL [for make selectable] " + Hex.toHex(appAid));
            return sw(SW_OK);
        }

        return sw(SW_INCORRECT_P1P2);
    }

    /* ---------------- LOAD ---------------- */

    private byte[] load(byte[] c) {
        if (!loading) {
            throw new GpException(SW_CONDITIONS,
                    "LOAD without a preceding INSTALL [for load]");
        }
        int p1 = c[2] & 0xFF;
        int p2 = c[3] & 0xFF;
        byte[] data = body(c);

        if (p2 != expectedBlock) {
            throw new GpException(SW_WRONG_DATA, "expected load block "
                    + expectedBlock + " but got " + p2);
        }
        expectedBlock++;
        loadBuffer.write(data, 0, data.length);

        boolean last = (p1 & 0x80) != 0;
        if (!last) {
            return sw(SW_OK);
        }

        // The assembled data is the Load File Data Block, normally wrapped in
        // tag C4. Unwrap it if present, then it is just CAP components.
        byte[] assembled = loadBuffer.toByteArray();
        loadBuffer.reset();
        loading = false;
        byte[] components = unwrapC4(assembled);

        card.log("LOAD complete: " + components.length + " bytes of components");

        LoadedPackage pkg;
        try {
            pkg = card.loadComponentStream(components);
        } catch (RuntimeException e) {
            card.lastError = e;
            throw new GpException(SW_NOT_ENOUGH_MEMORY, "load failed: " + e);
        }

        if (pendingElfAid != null && !java.util.Arrays.equals(pendingElfAid, pkg.aid)) {
            card.log("  note: INSTALL [for load] declared " + Hex.toHex(pendingElfAid)
                    + " but the CAP's package AID is " + Hex.toHex(pkg.aid));
        }
        // The JCRE registered the load file and its modules as it linked them.
        for (int i = 0; i < pkg.cap.applets.size(); i++) {
            card.log("  module " + Hex.toHex(pkg.cap.applets.get(i).aid));
        }
        pendingElfAid = null;
        return sw(SW_OK);
    }

    private static byte[] unwrapC4(byte[] a) {
        if (a.length < 2 || (a[0] & 0xFF) != 0xC4) {
            return a;
        }
        int len = a[1] & 0xFF;
        int off = 2;
        if (len == 0x81) {
            len = a[2] & 0xFF;
            off = 3;
        } else if (len == 0x82) {
            len = ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
            off = 4;
        } else if (len > 0x82) {
            return a;
        }
        if (off + len > a.length) {
            len = a.length - off;
        }
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    /* ---------------- DELETE ---------------- */

    /**
     * DELETE covers three cases, told apart by the tags in the data field:
     *
     *   4F  delete card content - an application, or a load file
     *   D0  delete a key by identifier
     *   D2  delete a key by version number
     *
     * P2 bit 8 selects "delete object and related objects", which cascades
     * from a load file to the applications installed from it.
     */
    private byte[] delete(byte[] c) {
        int p2 = c[3] & 0xFF;
        boolean cascade = (p2 & 0x80) != 0;
        byte[] data = body(c);

        byte[] keyId = extractTag(data, 0xD0);
        byte[] keyVersion = extractTag(data, 0xD2);
        if (keyId.length > 0 || keyVersion.length > 0) {
            return deleteKey(keyId, keyVersion);
        }

        byte[] target = extractTag(data, 0x4F);
        if (target.length == 0) {
            throw new GpException(SW_WRONG_DATA,
                    "DELETE needs a 4F AID, or D0/D2 for a key");
        }

        // Ask the JCRE what really exists rather than trusting the registry
        // alone, so an entry that got out of step is still deletable.
        GpRegistry.Entry e = registry.find(target);
        AppletInstance instance = card.findApplet(target);
        LoadedPackage pkg = card.findLoaded(target);

        if (e != null && e.type == GpRegistry.TYPE_ISD) {
            throw new GpException(SW_CONDITIONS, "the ISD cannot be deleted");
        }
        if (instance != null) {
            return deleteApplication(target);
        }
        if (pkg != null || (e != null && e.type == GpRegistry.TYPE_ELF)) {
            return deleteLoadFile(target, cascade);
        }
        if (e != null && e.type == GpRegistry.TYPE_MODULE) {
            throw new GpException(SW_CONDITIONS,
                    "an executable module is deleted with its load file, not on"
                    + " its own - delete " + Hex.toHex(e.elfAid) + " instead");
        }
        if (e != null) {
            registry.remove(target);       // a stale entry, drop it
            return sw(SW_OK);
        }
        throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                "nothing on the card with AID " + Hex.toHex(target));
    }

    private byte[] deleteApplication(byte[] aid) {
        card.deleteApplet(aid);            // also clears the registry entry
        registry.remove(aid);
        card.log("DELETE application " + Hex.toHex(aid));
        return sw(SW_OK);
    }

    private byte[] deleteLoadFile(byte[] elfAid, boolean cascade) {
        // Applets are found by the package they came from, which is authoritative
        // even if the registry entry was never written.
        List<AppletInstance> apps = card.appletsOfPackage(elfAid);
        if (!apps.isEmpty() && !cascade) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < apps.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(apps.get(i).aidHex());
            }
            throw new GpException(SW_CONDITIONS,
                    "load file " + Hex.toHex(elfAid) + " still has application(s) "
                    + sb + " - use DELETE with P2=80 to remove them too");
        }

        LoadedPackage pkg = card.findLoaded(elfAid);
        if (pkg != null) {
            LoadedPackage dependent = card.packageDependingOn(pkg);
            if (dependent != null) {
                throw new GpException(SW_CONDITIONS,
                        "load file " + Hex.toHex(elfAid) + " is imported by "
                        + Hex.toHex(dependent.aid));
            }
        }

        for (int i = 0; i < apps.size(); i++) {
            byte[] appAid = apps.get(i).aid;
            card.deleteApplet(appAid);
            registry.remove(appAid);
            card.log("  deleted application " + Hex.toHex(appAid));
        }
        registry.remove(elfAid);           // also drops its modules
        card.unloadPackage(elfAid);
        card.log("DELETE load file " + Hex.toHex(elfAid)
                + (cascade ? " and related objects" : ""));
        return sw(SW_OK);
    }

    private byte[] deleteKey(byte[] keyId, byte[] keyVersion) {
        if (keyVersion.length == 0) {
            throw new GpException(SW_WRONG_DATA,
                    "DELETE [key] needs a D2 key version number");
        }
        Integer version = Integer.valueOf(keyVersion[0] & 0xFF);
        if (!keyVersions.remove(version)) {
            throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                    "no key set with version " + version);
        }
        card.log("DELETE key version " + version
                + (keyId.length > 0 ? " id " + (keyId[0] & 0xFF) : " (all identifiers)"));
        return sw(SW_OK);
    }

    /* ---------------- STORE DATA and SET STATUS ---------------- */

    /**
     * After INSTALL [for personalization] the terminal sends STORE DATA blocks
     * for the target application. A real card hands them to the applet through
     * the Application interface; here they are delivered to Applet.process, so
     * the applet sees them as ordinary 80 E2 commands.
     */
    private byte[] storeData(byte[] c) {
        if (personalizationTarget == null) {
            throw new GpException(SW_CONDITIONS,
                    "STORE DATA without INSTALL [for personalization]");
        }
        int p1 = c[2] & 0xFF;
        int p2 = c[3] & 0xFF;
        if (p2 != storeDataBlock) {
            throw new GpException(SW_WRONG_DATA,
                    "expected STORE DATA block " + storeDataBlock + " but got " + p2);
        }
        storeDataBlock++;
        boolean last = (p1 & 0x80) != 0;

        byte[] response = card.personalize(personalizationTarget, c);
        if (last) {
            card.log("STORE DATA complete for " + Hex.toHex(personalizationTarget));
            personalizationTarget = null;
            storeDataBlock = 0;
        }
        return response;
    }

    /** SET STATUS changes the card life cycle or an application's. */
    private byte[] setStatus(byte[] c) {
        int p1 = c[2] & 0xFF;
        int p2 = c[3] & 0xFF;
        byte[] data = body(c);

        if ((p1 & 0x80) != 0 && data.length == 0) {
            registry.cardLifeCycle = p2;
            card.log("SET STATUS card life cycle -> 0x"
                    + Integer.toHexString(p2).toUpperCase());
            return sw(SW_OK);
        }
        byte[] aid = extractTag(data, 0x4F);
        if (aid.length == 0) {
            aid = data;                     // GP 2.1.1 sends the bare AID
        }
        if (aid.length == 0) {
            if ((p1 & 0x80) != 0) {
                registry.cardLifeCycle = p2;
                return sw(SW_OK);
            }
            throw new GpException(SW_WRONG_DATA, "SET STATUS without an AID");
        }
        GpRegistry.Entry e = registry.find(aid);
        if (e == null) {
            throw new GpException(SW_REFERENCED_DATA_NOT_FOUND,
                    "no registry entry for " + Hex.toHex(aid));
        }
        e.lifeCycle = p2;
        card.log("SET STATUS " + Hex.toHex(aid) + " -> " + e.lifeCycleName());
        return sw(SW_OK);
    }

    /* ---------------- GET STATUS ---------------- */

    private byte[] getStatus(byte[] c) {
        int p1 = c[2] & 0xFF;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<GpRegistry.Entry> all = registry.entries();
        for (int i = 0; i < all.size(); i++) {
            GpRegistry.Entry e = all.get(i);
            boolean want;
            if ((p1 & 0x80) != 0) {
                want = e.type == GpRegistry.TYPE_ELF;
            } else if ((p1 & 0x40) != 0) {
                want = e.type == GpRegistry.TYPE_APPLICATION
                        || e.type == GpRegistry.TYPE_ISD;
            } else if ((p1 & 0x20) != 0) {
                want = e.type == GpRegistry.TYPE_ELF || e.type == GpRegistry.TYPE_MODULE;
            } else {
                want = e.type == GpRegistry.TYPE_ISD;
            }
            if (!want) {
                continue;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(0x4F);
            body.write(e.aid.length);
            body.write(e.aid, 0, e.aid.length);
            body.write(0x9F);
            body.write(0x70);
            body.write(0x01);
            body.write(e.lifeCycle);
            body.write(0xC5);
            body.write(0x01);
            body.write(e.privileges & 0xFF);
            byte[] b = body.toByteArray();
            out.write(0xE3);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        byte[] data = out.toByteArray();
        if (data.length == 0) {
            return sw(SW_REFERENCED_DATA_NOT_FOUND);
        }
        return withSw(data, SW_OK);
    }

    private byte[] getData(byte[] c) {
        int tag = ((c[2] & 0xFF) << 8) | (c[3] & 0xFF);
        if (tag == 0x9F7F) {                       // card production life cycle
            byte[] r = new byte[]{(byte) 0x9F, 0x7F, 0x2A};
            return withSw(r, SW_OK);
        }
        if (tag == 0x0066 || tag == 0x6600) {      // card data
            return withSw(new byte[]{0x66, 0x00}, SW_OK);
        }
        return sw(SW_REFERENCED_DATA_NOT_FOUND);
    }

    /* ---------------- helpers ---------------- */

    private static byte[] body(byte[] c) {
        int lc = c.length > 4 ? (c[4] & 0xFF) : 0;
        if (c.length < 5 + lc) {
            throw new GpException(SW_WRONG_LENGTH, "command shorter than its Lc");
        }
        byte[] out = new byte[lc];
        System.arraycopy(c, 5, out, 0, lc);
        return out;
    }

    /** Finds a single byte tag in a TLV sequence, returning its value. */
    private static byte[] extractTag(byte[] data, int tag) {
        int p = 0;
        while (p + 2 <= data.length) {
            int t = data[p] & 0xFF;
            int len = data[p + 1] & 0xFF;
            int vOff = p + 2;
            if (len == 0x81) {
                len = data[p + 2] & 0xFF;
                vOff = p + 3;
            } else if (len == 0x82) {
                len = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                vOff = p + 4;
            } else if (len > 0x82) {
                break;
            }
            if (vOff + len > data.length) {
                break;
            }
            if (t == tag) {
                byte[] out = new byte[len];
                System.arraycopy(data, vOff, out, 0, len);
                return out;
            }
            p = vOff + len;
        }
        return new byte[0];
    }

    private static byte[] sw(int status) {
        return new byte[]{(byte) (status >> 8), (byte) status};
    }

    private static byte[] withSw(byte[] data, int status) {
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) (status >> 8);
        out[data.length + 1] = (byte) status;
        return out;
    }

    /** A length/value walker over a GP command data field. */
    private static final class Tlv {

        private final byte[] d;
        private int p;

        Tlv(byte[] d) {
            this.d = d;
        }

        byte[] lv(String what) {
            if (p >= d.length) {
                throw new GpException(SW_WRONG_DATA, "missing " + what);
            }
            return lvOrEmpty();
        }

        /**
         * GlobalPlatform writes these fields with a single length byte, but a
         * field longer than 127 bytes is commonly sent with the BER long form
         * (81 xx, or 82 xx xx), so both are accepted. 80 is the indefinite
         * form, which has no meaning here.
         */
        byte[] lvOrEmpty() {
            if (p >= d.length) {
                return new byte[0];
            }
            int first = d[p] & 0xFF;
            int len;
            int valueAt;
            if (first == 0x81) {
                if (p + 2 > d.length) {
                    throw new GpException(SW_WRONG_DATA,
                            "truncated 81 length field");
                }
                len = d[p + 1] & 0xFF;
                valueAt = p + 2;
            } else if (first == 0x82) {
                if (p + 3 > d.length) {
                    throw new GpException(SW_WRONG_DATA,
                            "truncated 82 length field");
                }
                len = ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF);
                valueAt = p + 3;
            } else if (first == 0x80) {
                throw new GpException(SW_WRONG_DATA,
                        "indefinite length (80) is not valid in an INSTALL field");
            } else if (first > 0x82) {
                throw new GpException(SW_WRONG_DATA,
                        "unsupported length form " + String.format("%02X",
                                Integer.valueOf(first)) + " in an INSTALL field");
            } else {
                len = first;
                valueAt = p + 1;
            }
            if (valueAt + len > d.length) {
                throw new GpException(SW_WRONG_DATA,
                        "length/value field runs past the end of the command");
            }
            byte[] out = new byte[len];
            System.arraycopy(d, valueAt, out, 0, len);
            p = valueAt + len;
            return out;
        }
    }

    /** Carries a status word out of the command handlers. */
    public static final class GpException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        public final int statusWord;

        public GpException(int statusWord, String message) {
            this(statusWord, message, null);
        }

        public GpException(int statusWord, String message, Throwable cause) {
            super(message + " (SW=" + Integer.toHexString(statusWord).toUpperCase() + ")",
                    cause);
            this.statusWord = statusWord;
        }
    }
}
