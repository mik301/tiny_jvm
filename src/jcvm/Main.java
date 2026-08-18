package jcvm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import jcvm.jcre.AppletInstance;
import jcvm.jcre.JCRE;
import jcvm.rt.LoadedPackage;
import jcvm.tool.CapBuilder;
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
            } else if ("load".equals(cmd)) {
                require(t.length > 1, "load <file.cap>");
                LoadedPackage p = card.loadCap(new File(t[1]));
                out.println("loaded package " + Hex.toHex(p.aid)
                        + " with " + p.cap.applets.size() + " applet module(s)");
                for (int i = 0; i < p.cap.applets.size(); i++) {
                    out.println("   module " + Hex.toHex(p.cap.applets.get(i).aid));
                }
            } else if ("install".equals(cmd)) {
                require(t.length > 1, "install <moduleAid> [instanceAid] [paramsHex]");
                byte[] module = Hex.parse(t[1]);
                byte[] instance = t.length > 2 ? Hex.parse(t[2]) : module;
                byte[] params = t.length > 3 ? Hex.parse(t[3]) : new byte[0];
                AppletInstance a = card.installApplet(module, instance, params);
                out.println("installed " + a);
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
        out.println("  load <file.cap>                load and link a CAP file");
        out.println("  install <moduleAid> [instAid] [paramsHex]");
        out.println("  select <aid>                   SELECT by DF name");
        out.println("  send <hex>                     send a command APDU");
        out.println("  <hex>                          same as send");
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
