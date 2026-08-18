# jcvm — a Java Card virtual machine

A Java Card VM and card runtime environment written in plain Java, with no
external dependencies. It loads CAP files, links them, installs applets,
handles SELECT, and interprets Java Card bytecode so that an applet can answer
command APDUs.

```
gendemo demo.cap
load demo.cap
install 01020304050607
select 01020304050607
--> 80 10 00 00 00
<-- 00 01  SW=9000
```

## Building and running

Requires a JDK (Java 8 or newer). There are no libraries to fetch.

```sh
./build.sh          # javac -d out $(find src -name '*.java')
./run.sh            # interactive shell
./run.sh demo.jcvm  # run the bundled end-to-end demo script
```

### Shell commands

| command | meaning |
|---|---|
| `gendemo [file]` | write a demo CAP that is built into the VM |
| `genwallet [file]` | write a hand-assembled Wallet applet CAP |
| `load <file.cap>` | sends INSTALL [for load] and the LOAD blocks |
| `install <moduleAid> [instAid] [c9] [ef]` | sends INSTALL [for install and make selectable] |
| `loaddirect` / `installdirect` | the same, bypassing the card manager |
| `apdu on\|off` | echo the APDUs that `load` and `install` build |
| `select <aid>` | SELECT by DF name |
| `send <hex>` — or just type the hex | send a command APDU |
| `params <hex>` | decode an install parameters field |
| `delete <aid> [cascade]` | DELETE, optionally with related objects |
| `gp` | GlobalPlatform registry: ELFs, modules, applications |
| `gpscript <cap> [module] [inst]` | print the GP load/install APDU sequence |
| `gpload <cap> [module] [inst]` | build that sequence and send it |
| `list`, `info` | registry and loaded package details |
| `reset` | card reset (clears transient memory, deselects) |
| `loadexp <dir>` | read API tokens from an SDK's `.exp` export files |
| `dumptokens [file]` | print or save the token tables in use |
| `missing` | API methods a loaded CAP needs but the VM lacks |
| `envelope <event> <hex>` | trigger toolkit applets on a CAT event |
| `cat` | toolkit registrations and the proactive command log |
| `fs [file]` | show or load the UICC file system |
| `trace on\|off` | per-instruction bytecode tracing |
| `error` | stack trace behind the last `6F00` |

`load` and `install` are not shortcuts: they build the GlobalPlatform APDUs and
send them through the card manager, so the convenient commands and a replayed
`gp --install` trace take exactly the same path. `apdu off` silences the echo;
`loaddirect` and `installdirect` bypass the ISD when you want to isolate the
loader from the GP layer.

```
jcvm> load ratchecker.cap
   --> 80 E6 02 00 0D 08 A0 00 00 52 61 74 43 68 00 00 00   # INSTALL [for load]
   <-- SW=9000
   --> 80 E8 00 00 C8 C4 82 04 1A 01 00 1F DE CA FF ED ... (196 more bytes)
   <-- SW=9000
   --> 80 E8 80 01 4C ... (72 more bytes)   # LOAD block 1 (last)
   <-- SW=9000
loaded package A000005261744368 with 1 applet module(s)
   module A000005261744368417070

jcvm> install A000005261744368417070
   --> 80 E6 0C 00 22 08 A0 00 00 52 61 74 43 68 0B ...   # INSTALL [for install]
   <-- 0B A0 00 00 52 61 74 43 68 41 70 70  SW=9000
installed A000005261744368417070  (...)
```

There is no SCP02/SCP03 cryptography: INITIALIZE UPDATE returns fixed data and
EXTERNAL AUTHENTICATE is accepted without verification, so MACed and encrypted
command traces will not replay. Everything else follows GlobalPlatform.

## GlobalPlatform command set

| command | INS | what is supported |
|---|---|---|
| SELECT | A4 | by DF name; empty AID or the ISD AID selects the card manager |
| INITIALIZE UPDATE | 50 | returns fixed data, no SCP keys |
| EXTERNAL AUTHENTICATE | 82 | accepted without verification |
| INSTALL | E6 | for load, for install, for make selectable, for extradition, for registry update, for personalization |
| LOAD | E8 | block chaining, C4 unwrapping |
| DELETE | E4 | card content and keys, with and without related objects |
| STORE DATA | E2 | block chaining, delivered to the personalization target |
| SET STATUS | F0 | card and application life cycles |
| GET STATUS | F2 | ISD, applications, load files, load files with modules |
| GET DATA | CA | a couple of common tags |

### DELETE

The tags in the data field decide what is deleted:

```
4F  card content - an application or a load file
D0  a key by identifier
D2  a key by version number
```

P2 bit 8 requests "delete object and related objects". Deleting a load file
that still has applications installed from it fails with `6985` unless that bit
is set, in which case the applications go first, then the modules, then the load
file, and the package is unlinked. Deleting a load file that another loaded
package imports fails, naming the dependent. An executable module cannot be
deleted on its own - the error says which load file to delete instead.

```
jcvm> delete A0000001020304
jcvm> delete A00000010203 cascade
```

### INSTALL

`for extradition` moves an application to another security domain, `for
registry update` changes privileges or the associated domain, and `for
personalization` marks an application as the target of the STORE DATA blocks
that follow. Those blocks are delivered to `Applet.process` as ordinary
`80 E2` commands, without disturbing what is selected.

## Install parameters

The install parameters field of INSTALL [for install] is TLV, and both halves
are handled:

```
C9 len  application specific parameters   -> reaches Applet.install()
EF len  system specific parameters
          C7  volatile memory quota
          C8  non-volatile memory quota
          D7  volatile reserved memory
          D8  non-volatile reserved memory
          CA  UICC toolkit parameters     (ETSI TS 102 226)
          CB  UICC access parameters
          CC  UICC administrative access parameters
```

The `C9` value is what the applet sees: the card manager hands `install()` a
buffer in the GP layout `[len][instance AID][len][privileges][len][C9 value]`,
so `bArray`/`bOffset`/`bLength` point at exactly what a real card would give.

The `CA` block configures a toolkit applet before its constructor runs, because
`initMenuEntry` is called from there. It carries the priority level, timer and
menu limits, and a reserved identifier for each menu entry - so `initMenuEntry`
returns the identifier the install command asked for rather than an arbitrary
one, and refuses once `maxMenuEntries` is reached.

A field that does not begin with `C9` or `EF` is treated as a bare parameter
blob, which some tools still send.

```
jcvm> params C9 03 010203 EF 0C CA 0A 01 02 10 02 01 01 02 02 00
    C9 application parameters: 010203
    EF system parameters:
      CA toolkit: priority=1 timers=2 menuEntries=2 menuTextLen=16 softKeys=0
      menu entry position 1 identifier 1
      menu entry position 2 identifier 2
```

`gpscript <cap> <module> <instance> <C9 hex> <EF hex>` builds an INSTALL
carrying both.

## The real flow

```
terminal / gp tool
        |  APDU
        v
  CardManager (ISD)          jcvm.gp    GP command set, registry, life cycles
        |  INSTALL [for load] -> LOAD blocks -> Load File Data Block
        v
  CapFile / CapPackage       jcvm.cap   split into components, decode them
        |
  LoadedPackage.link()       jcvm.rt    resolve imports, superclasses, statics
        |  INSTALL [for install and make selectable]
        v
  JCRE.installApplet()       jcvm.jcre  calls the applet's static install()
        |                                which calls register()
        v
  VM.call()                  jcvm.rt    interprets the bytecode
        |
  applet registered, SELECTABLE
        |  SELECT by DF name
        v
  JCRE.runProcess() -> Applet.process(APDU)
```

`./run.sh gpflow.jcvm` walks the whole thing with real APDUs. `gpscript
<cap>` prints the sequence without sending it; `gpload <cap>` builds and sends
it.

## Architecture

```
jcvm.cap    CapFile      raw components from a .cap ZIP, a directory, or a
                         concatenated load file data block
            CapPackage   decodes Header, Applet, Import, ConstantPool, Class,
                         Method, StaticField and Export components

jcvm.rt     LoadedPackage  linking: imports, superclasses, static field image
            ClassRt        virtual method tables and dispatch
            MethodRt       compact / extended method headers
            Frame          16-bit word locals and operand stack
            VM             the bytecode interpreter
            Transaction    undo journal for beginTransaction/abortTransaction

jcvm.api    ApiRegistry  token tables loaded from res/api-tokens.txt
            Natives      javacard.framework implemented in Java
            Descriptor   maps a descriptor to argument words / return kind

jcvm.gp     CardManager  the Issuer Security Domain: GP command set,
                         on-card loader, installer
            GpRegistry   ELF / module / application entries and life cycles
            GpScript     builds the load and install APDU sequence

jcvm.jcre   JCRE         applet registry, install, select, APDU dispatch
            ApduState    the shared APDU buffer and its state machine

jcvm.uicc   UiccFileSystem  in-memory UICC files for uicc.access.FileView
            CatRuntime      toolkit registry, envelope/proactive handlers,
                            event triggering for uicc.toolkit applets

jcvm.tool   CapBuilder   hand-assembles a demo CAP so the VM can be exercised
                         without a Java Card SDK
```

### Value model

Locals and the operand stack are arrays of 16-bit words, as on a real card.
Each word is a pair of parallel slots — a numeric value and an object reference
— so references are never confused with numbers. An `int` occupies two
consecutive words, high word first.

Instance fields are addressed by token. Primitive and reference fields live in
separate arrays; the Class component's `first_reference_token` /
`reference_count` say which tokens are references.

### Method resolution

* `invokestatic` / `invokespecial` — a `CONSTANT_StaticMethodref`; internal
  references are a direct Method component offset, external ones resolve
  through the Import component to either another loaded CAP (via its Export
  component) or to a native API method.
* `invokevirtual` — the declared class gives the argument count so the receiver
  can be found on the stack, then dispatch restarts at the receiver's runtime
  class. A table entry of `0xFFFF`, or a token outside `[base, base+count)`,
  walks up to the superclass; when that superclass is a natively implemented
  API class the call becomes a native call.
* `invokeinterface` — the receiver's class maps the interface method token to
  one of its own virtual tokens through `implemented_interface_info`.

## The token table (important)

CAP files address API classes and methods by **numeric token**, not by name.
The assignment comes from the export files (`*.exp`) of whatever Java Card SDK
converted the CAP, so it is a property of your toolchain rather than of this VM.

`res/api-tokens.txt` holds that mapping in a small text format:

```
PKG   javacard.framework A0000000620101 1 3
CLASS 3 javacard/framework/Applet SIZE 0
  V 1 process(Ljavacard/framework/APDU;)V
  V 4 register()V
  S 0 <init>()V
```

The bundled table is the one `CapBuilder` uses, so the VM and the built-in demo
CAPs always agree. **It is a plausible layout, not a verified copy of any SDK's
export files**, so a converter-produced CAP will generally disagree with it.

For real CAP files, read the tokens from your SDK instead of trusting the
bundled table:

```
jcvm> loadexp /path/to/sdk/api_export_files
  javacard.framework  A0000000620101  v1.3  17 classes
  java.lang           A0000000620001  v1.0  3 classes
token tables replaced from export files
jcvm> load wallet.cap
```

`ExpReader` parses the `.exp` files and binds each token to the method name the
export file gives it, which is then looked up in `Natives`. `dumptokens
api-tokens.txt` writes what it found back out in the text format, so you can
make it the permanent default.

When a token still cannot be bound, the error lists every token the VM does
know for that class, so a mismatch is obvious:

```
no binding for virtual token 8 of javacard/framework/APDU
  known virtual tokens for javacard/framework/APDU:
    0 -> getBuffer()[B
    1 -> setIncomingAndReceive()S
    ...
```

## SIM / UICC applets

`uicc.access` and `uicc.toolkit` (ETSI TS 102 241) are implemented as natives,
so toolkit applets link and run:

```
jcvm> loadexp /path/to/uicc_api_export_files
jcvm> load ratchecker.cap
jcvm> missing                     # what this CAP needs that is not implemented
jcvm> install <moduleAid>
jcvm> cat                         # events and menu entries it registered for
jcvm> envelope 1 D1 0E 82 02 83 81 06 03 11 22 33 8B 03 41 42 43
proactive --> D0 0D 01 03 01 21 00 02 02 81 02 8D 02 04 68 69
```

`fs` shows the file system a `FileView` sees; `fs files.txt` loads a definition
(`transparent 6F07 9`, `linear 6F3C 176 10`, `data 6F07 00112233...`).

What this is not: a conformant CAT terminal. Proactive commands are recorded
and printed rather than executed, the file system is flat rather than a real
DF/ADF hierarchy with access conditions, and there are no timers or SMS-PP
security. It is enough to drive an applet's logic and see what it emits.

## Known limitations

* `javacard.security` and `javacardx.crypto` are not implemented. Applets that
  use keys, ciphers, message digests or random numbers will not link.
* No Java Card RMI, no logical channels, no applet firewall / context switching,
  no shareable interface objects across packages (`getShareableInterfaceObject`
  returns null).
* `int` **instance fields** are not supported: field tokens are used directly as
  cell indices, which assumes every instance field is one cell wide. `int`
  locals, stack values, arrays and arithmetic all work.
* One transaction level, no nesting — which matches the specification, but
  commit capacity accounting is approximate.
* T=0 GET RESPONSE chaining is not simulated; the full response is returned in
  one exchange, as with T=1.
* No bytecode verifier. Malformed CAPs will produce Java exceptions rather than
  clean verification errors.
* `sushr` is implemented as a 16-bit logical shift; a few corner cases of short
  shift distances above 15 may differ from a particular card.

## Status

Written from the Java Card VM specification. The code was authored in an
environment that had no JDK available, so it has **not been compiled or
executed** — expect to fix a small number of compile errors on first build, and
treat the first `./build.sh && ./run.sh demo.jcvm` as the real smoke test.
The demo script exercises loading, linking, install, select, field access,
array access, native API calls and exception-to-status-word mapping in one pass.

## License

MIT.
