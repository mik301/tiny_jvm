package jcvm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import jcvm.api.ApiPackage;
import jcvm.api.ExpReader;
import jcvm.gp.GpRegistry;
import jcvm.gp.GpScript;
import jcvm.gp.InstallParams;
import jcvm.jcre.AppletInstance;
import jcvm.jcre.JCRE;
import jcvm.rt.LinkReport;
import jcvm.rt.LoadedPackage;
import jcvm.tool.CapBuilder;
import jcvm.tool.WalletCapBuilder;
import jcvm.util.Hex;

/** Command line front end: a card reader you type APDUs into. */
public final class Main {

    private final JCRE card;
    private final java.io.PrintStream out = System.out;

    public Main(File tokens) throws IOException {
        this.card = new JCRE(tokens);
    }

    public static void main(String[] args) throws Exception {
        File tokens = new File("res/api-tokens.txt");
        File script = null;
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("-t".equals(args[i]) && i + 1 < args.length) {
                tokens = new File(args[++i]);
            } else if ("-h".equals(args[i]) || "--help".equals(args[i])) {
                usage();
                return;
            } else {
                rest.add(args[i]);
            }
        }
        if (!rest.isEmpty()) {
            script = new File(rest.get(0));
        }
        if (!tokens.isFile()) {
            System.err.println("token table not found: " + tokens.getPath()
                    + "  (use -t <file>)");
            System.exit(2);
        }
        Main m = new Main(tokens);
        m.banner();
        if (script != null) {
            m.runScript(script);
        } else {
            m.repl();
        }
    }

    private static void usage() {
        System.out.println("usage: java -cp out jcvm.Main [-t api-tokens.txt] [script]");
    }

    private void banner() {
        out.println("jcvm - Java Card virtual machine");
        out.println("type 'help' for commands, 'quit' to leave");
    }

    /* ------------------------------------------------------------------ */

    private void repl() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        out.print("jcvm> ");
        out.flush();
        while ((line = in.readLine()) != null) {
            if (!execute(line)) {
                return;
            }
            out.print("jcvm> ");
            out.flush();
        }
    }

    private void runScript(File f) throws IOException {
        BufferedReader in = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                out.println("jcvm> " + line);
                if (!execute(line)) {
                    return;
                }
            }
        } finally {
            in.close();
        }
    }

    /** Returns false when the shell should exit. */
    private boolean execute(String raw) {
        String line = raw;
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash);
        }
        line = line.trim();
        if (line.length() == 0) {
            return true;
        }
        String[] t = line.split("\\s+");
        String cmd = t[0].toLowerCase();
        try {
            if ("quit".equals(cmd) || "exit".equals(cmd)) {
                return false;
            } else if ("help".equals(cmd)) {
                help();
            } else if ("gendemo".equals(cmd)) {
                File f = new File(t.length > 1 ? t[1] : "demo.cap");
                CapBuilder.writeCap(f);
                out.println("wrote " + f.getPath()
                        + "   module AID " + Hex.toHex(CapBuilder.APPLET_AID));
            } else if ("genwallet".equals(cmd)) {
                File f = new File(t.length > 1 ? t[1] : "wallet.cap");
                WalletCapBuilder.writeCap(f);
                out.println("wrote " + f.getPath()
                        + "   module AID " + Hex.toHex(WalletCapBuilder.APPLET_AID));
            } else if ("loadexp".equals(cmd)) {
                require(t.length > 1, "loadexp <dir with .exp files>");
                java.util.List<ApiPackage> p = ExpReader.loadDirectory(
                        new File(t[1]), card.api);
                if (p.isEmpty()) {
                    out.println("no .exp files found under " + t[1]);
                } else {
                    for (int i = 0; i < p.size(); i++) {
                        ApiPackage a = p.get(i);
                        out.println("  " + a.name + "  " + Hex.toHex(a.aid)
                                + "  v" + a.major + "." + a.minor
                                + "  " + a.byToken.size() + " classes");
                    }
                    card.refreshAppletTokens();
                    out.println("export file format " + ExpReader.lastVersion
                            + ", layout: " + ExpReader.lastLayout);
                    out.println("token tables replaced from export files");
                }
            } else if ("dumpexp".equals(cmd)) {
                require(t.length > 1, "dumpexp <file.exp>");
                ApiPackage a = ExpReader.read(new File(t[1]));
                out.println("format " + ExpReader.lastVersion
                        + ", layout: " + ExpReader.lastLayout);
                out.print(ExpReader.toTokenTable(a));
            } else if ("dumptokens".equals(cmd)) {
                StringBuilder sb = new StringBuilder();
                for (ApiPackage a : card.api.packages()) {
                    sb.append(ExpReader.toTokenTable(a)).append('\n');
                }
                if (t.length > 1) {
                    java.io.FileWriter w = new java.io.FileWriter(t[1]);
                    w.write(sb.toString());
                    w.close();
                    out.println("wrote " + t[1]);
                } else {
                    out.print(sb);
                }
            } else if ("load".equals(cmd)) {
                require(t.length > 1, "load <file.cap>");
                gpLoad(new File(t[1]));
            } else if ("loaddirect".equals(cmd)) {
                require(t.length > 1, "loaddirect <file.cap>");
                LoadedPackage p = card.loadCap(new File(t[1]));
                out.println("loaded package " + Hex.toHex(p.aid)
                        + " with " + p.cap.applets.size() + " applet module(s)");
                for (int i = 0; i < p.cap.applets.size(); i++) {
                    out.println("   module " + Hex.toHex(p.cap.applets.get(i).aid));
                }
            } else if ("install".equals(cmd)) {
                require(t.length > 1,
                        "install <moduleAid> [instanceAid] [c9Hex] [efHex]");
                gpInstall(Hex.parse(t[1]),
                        t.length > 2 ? Hex.parse(t[2]) : null,
                        t.length > 3 ? Hex.parse(t[3]) : null,
                        t.length > 4 ? Hex.parse(t[4]) : null);
            } else if ("installdirect".equals(cmd)) {
                require(t.length > 1,
                        "installdirect <moduleAid> [instanceAid] [paramsHex]");
                byte[] module = Hex.parse(t[1]);
                byte[] instance = t.length > 2 ? Hex.parse(t[2]) : module;
                byte[] params = t.length > 3 ? Hex.parse(t[3]) : new byte[0];
                AppletInstance a = card.installApplet(module, instance, params);
                out.println("installed " + a);
            } else if ("delete".equals(cmd)) {
                require(t.length > 1, "delete <aid> [cascade]");
                byte[] aid = Hex.parse(t[1]);
                boolean cascade = t.length > 2
                        && ("cascade".equalsIgnoreCase(t[2])
                            || "related".equalsIgnoreCase(t[2]));
                selectCardManager();
                java.io.ByteArrayOutputStream d = new java.io.ByteArrayOutputStream();
                d.write(0x4F);
                d.write(aid.length);
                d.write(aid, 0, aid.length);
                byte[] value = d.toByteArray();
                byte[] command = new byte[5 + value.length];
                command[0] = (byte) 0x80;
                command[1] = (byte) 0xE4;
                command[2] = 0x00;
                command[3] = (byte) (cascade ? 0x80 : 0x00);
                command[4] = (byte) value.length;
                System.arraycopy(value, 0, command, 5, value.length);
                int sw = send(command, "DELETE"
                        + (cascade ? " [and related objects]" : ""));
                if (sw == 0x9000) {
                    out.println("deleted " + Hex.toHex(aid));
                } else {
                    out.println("delete failed, SW="
                            + String.format("%04X", Integer.valueOf(sw))
                            + "  (type 'error' for details)");
                }
            } else if ("apdu".equals(cmd)) {
                showApdu = t.length < 2 || "on".equalsIgnoreCase(t[1]);
                out.println("apdu echo " + (showApdu ? "on" : "off"));
            } else if ("select".equals(cmd)) {
                require(t.length > 1, "select <aid>");
                byte[] aid = Hex.parse(t[1]);
                byte[] cmdApdu = new byte[5 + aid.length];
                cmdApdu[0] = 0x00;
                cmdApdu[1] = (byte) 0xA4;
                cmdApdu[2] = 0x04;
                cmdApdu[3] = 0x00;
                cmdApdu[4] = (byte) aid.length;
                System.arraycopy(aid, 0, cmdApdu, 5, aid.length);
                exchange(cmdApdu);
            } else if ("send".equals(cmd)) {
                require(t.length > 1, "send <hex apdu>");
                exchange(Hex.parse(join(t, 1)));
            } else if ("list".equals(cmd)) {
                list();
            } else if ("info".equals(cmd)) {
                info();
            } else if ("reset".equals(cmd)) {
                card.reset();
                out.println("card reset");
            } else if ("trace".equals(cmd)) {
                card.vm.trace = t.length < 2 || "on".equalsIgnoreCase(t[1]);
                out.println("trace " + (card.vm.trace ? "on" : "off"));
            } else if ("error".equals(cmd)) {
                if (card.lastError == null) {
                    out.println("no error recorded");
                } else {
                    card.lastError.printStackTrace(out);
                }
            } else {
                // anything that looks like hex is treated as an APDU
                exchange(Hex.parse(line));
            }
        } catch (Exception e) {
            out.println("error: " + e);
        }
        return true;
    }

    private static String join(String[] t, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < t.length; i++) {
            sb.append(t[i]);
        }
        return sb.toString();
    }

    /** Whether the APDUs built by load/install are echoed. */
    private boolean showApdu = true;

    /** Makes sure the card manager is the selected application. */
    private void selectCardManager() {
        if (!card.cardManagerSelected) {
            send(GpScript.selectCommand(new byte[0]), "SELECT card manager");
        }
    }

    /** Sends one APDU, echoing it, and returns the status word. */
    private int send(byte[] command, String what) {
        byte[] response = card.transmit(command);
        int n = response.length;
        int sw = ((response[n - 2] & 0xFF) << 8) | (response[n - 1] & 0xFF);
        if (showApdu) {
            out.println("   --> " + abbreviate(command)
                    + (what == null ? "" : "   # " + what));
            String data = n > 2
                    ? Hex.toSpaced(java.util.Arrays.copyOf(response, n - 2)) + "  " : "";
            out.println("   <-- " + data + "SW="
                    + String.format("%04X", Integer.valueOf(sw)));
        }
        return sw;
    }

    /** Long LOAD blocks are shown truncated so the flow stays readable. */
    private static String abbreviate(byte[] a) {
        if (a.length <= 20) {
            return Hex.toSpaced(a);
        }
        byte[] head = java.util.Arrays.copyOf(a, 20);
        return Hex.toSpaced(head) + " ... (" + (a.length - 20) + " more bytes)";
    }

    /** load: INSTALL [for load] then the LOAD blocks, as a terminal would. */
    private void gpLoad(File capFile) throws Exception {
        selectCardManager();
        java.util.List<byte[]> seq = GpScript.buildLoadSequence(capFile, 200);
        for (int i = 0; i < seq.size(); i++) {
            byte[] c = seq.get(i);
            String what = (c[1] & 0xFF) == 0xE6 ? "INSTALL [for load]"
                    : "LOAD block " + (c[3] & 0xFF)
                    + ((c[2] & 0x80) != 0 ? " (last)" : "");
            int sw = send(c, what);
            if (sw != 0x9000) {
                out.println("load failed at " + what + ", SW="
                        + String.format("%04X", Integer.valueOf(sw))
                        + "  (type 'error' for details)");
                return;
            }
        }
        if (card.packages.isEmpty()) {
            return;
        }
        LoadedPackage p = card.packages.get(card.packages.size() - 1);
        out.println("loaded package " + Hex.toHex(p.aid)
                + " with " + p.cap.applets.size() + " applet module(s)");
        for (int i = 0; i < p.cap.applets.size(); i++) {
            out.println("   module " + Hex.toHex(p.cap.applets.get(i).aid));
        }
    }

    /** install: one INSTALL [for install and make selectable]. */
    private void gpInstall(byte[] moduleAid, byte[] instanceAid,
            byte[] appParams, byte[] systemParams) {
        selectCardManager();
        GpRegistry.Entry module = card.cardManager.registry.find(moduleAid,
                GpRegistry.TYPE_MODULE);
        if (module == null) {
            out.println("no executable module " + Hex.toHex(moduleAid)
                    + " on the card - load its CAP first ('gp' lists what is there)");
            return;
        }
        byte[] instance = (instanceAid == null || instanceAid.length == 0)
                ? moduleAid : instanceAid;
        byte[] command = GpScript.buildInstallCommand(module.elfAid, moduleAid,
                instance, appParams, systemParams);
        int sw = send(command, "INSTALL [for install and make selectable]");
        if (sw != 0x9000) {
            out.println("install failed, SW=" + String.format("%04X", Integer.valueOf(sw))
                    + "  (type 'error' for details)");
            return;
        }
        AppletInstance a = card.findApplet(instance);
        out.println("installed " + (a != null ? a.toString() : Hex.toHex(instance)));
    }

    private void exchange(byte[] command) {
        out.println("--> " + Hex.toSpaced(command));
        byte[] response = card.transmit(command);
        int n = response.length;
        String sw = Hex.toHex(response, n - 2, 2);
        String data = n > 2 ? Hex.toSpaced(java.util.Arrays.copyOf(response, n - 2)) : "";
        out.println("<-- " + (data.length() > 0 ? data + "  " : "") + "SW=" + sw);
    }

    private void list() {
        if (card.applets.isEmpty()) {
            out.println("no applets installed");
            return;
        }
        for (int i = 0; i < card.applets.size(); i++) {
            AppletInstance a = card.applets.get(i);
            boolean sel = a == card.selectedApplet();
            out.println((sel ? " * " : "   ") + a);
        }
    }

    private void info() {
        out.println("packages loaded : " + card.packages.size());
        for (int i = 0; i < card.packages.size(); i++) {
            out.print(card.packages.get(i).cap.describe());
        }
        out.println("applets         : " + card.applets.size());
        out.println("native methods  : " + card.natives.size());
        out.println("api packages    : " + card.api.packageCount());
    }

    private void help() {
        out.println("  gendemo [file]                 write a demo CAP built into this VM");
        out.println("  loadexp <dir>                  read API tokens from SDK .exp files");
        out.println("  dumpexp <file.exp>             parse one export file and show it");
        out.println("  dumptokens [file]              print/save the tokens in use");
        out.println("  genwallet [file]               write the Wallet demo CAP");
        out.println("  load <file.cap>                INSTALL [for load] + LOAD blocks");
        out.println("  loaddirect <file.cap>          load without going through the ISD");
        out.println("  installdirect <moduleAid> ...  install without an INSTALL APDU");
        out.println("  delete <aid> [cascade]         DELETE, optionally with related objects");
        out.println("  apdu on|off                    echo the APDUs load/install build");
        out.println("  install <moduleAid> [instAid] [c9Hex] [efHex]");
        out.println("  select <aid>                   SELECT by DF name");
        out.println("  send <hex>                     send a command APDU");
        out.println("  <hex>                          same as send");
        out.println("  missing                        API methods a loaded CAP needs but lacks");
        out.println("  envelope <event> <hex>         trigger toolkit applets on an event");
        out.println("  cat                            toolkit registrations and proactive log");
        out.println("  fs [file]                      show or load the UICC file system");
        out.println("  params <hex>                   decode an install parameters field");
        out.println("  gp                             GlobalPlatform registry");
        out.println("  gpscript <cap> [module] [inst] [c9hex] [efhex]");
        out.println("                                 print the GP load/install APDUs");
        out.println("  gpload <cap> [module] [inst]   run that sequence against the card");
        out.println("  list                           installed applets");
        out.println("  info                           loaded packages and VM state");
        out.println("  reset                          card reset");
        out.println("  trace on|off                   bytecode tracing");
        out.println("  error                          stack trace of the last 6F00");
        out.println("  quit");
    }

    private static void require(boolean ok, String usage) {
        if (!ok) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }
}
