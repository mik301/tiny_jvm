<<<<<<< HEAD
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
| `load <file.cap>` | load and link a CAP (ZIP, exploded directory, or raw component stream) |
| `install <moduleAid> [instanceAid] [paramsHex]` | run the applet's `install()` |
| `select <aid>` | SELECT by DF name |
| `send <hex>` — or just type the hex | send a command APDU |
| `list`, `info` | registry and loaded package details |
| `reset` | card reset (clears transient memory, deselects) |
| `trace on\|off` | per-instruction bytecode tracing |
| `error` | stack trace behind the last `6F00` |

GlobalPlatform-style APDUs also work, so an existing APDU trace can be replayed:
`80 E6 02 00 …` (INSTALL for load), `80 E8 …` (LOAD, with block chaining),
`80 E6 0C 00 …` (INSTALL for install and make selectable), `80 F2 …` (GET STATUS).

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

jcvm.jcre   JCRE         applet registry, install, select, APDU dispatch
            ApduState    the shared APDU buffer and its state machine

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

The bundled table is the one `CapBuilder` uses, so the VM and the demo CAP
always agree. **It is a plausible layout, not a verified copy of any SDK's
export files.** If a real CAP fails with

```
no token table entry for virtual token N of javacard/framework/...
```

dump the tokens from your SDK's export files and correct the numbers. Nothing
else in the VM has to change — this is the one place API tokens are configured.

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
=======
# tiny_jvm
# tiny_jvm
# tiny_jvm
>>>>>>> 1fec895 (first commit)
