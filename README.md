# Luava: Pure-Java Lua 5.4 Engine (Java 21)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kienpc1234/luava.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.kienpc1234/luava)
[![License](https://img.shields.io/badge/license-MPL%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21%2B-orange.svg)](#install)

Luava is a pure-Java implementation of **Lua 5.4** for the JVM: lexer,
parser, AST, register-based bytecode compiler, register VM interpreter,
hybrid tiered JIT, sandboxing and deep Java interop. It ships as a single
self-contained jar with **no runtime dependency beyond the JDK**.

Pipeline: `Lua source → Lexer → Parser → AST → BytecodeCompiler → LuaProto → BytecodeVM.execute()`.

> **Status: `0.1.0-alpha`.** The language and API are usable and the
> conformance suite is green, but the public API may still change before a
> 1.0 release.

## Install

Published on **Maven Central** as
[`io.github.kienpc1234:luava`](https://central.sonatype.com/artifact/io.github.kienpc1234/luava).

**Requirements:** a stock **JDK 21+** (no `--enable-preview`, no JNI/FFM, no
extra flags) and Maven 3.9+ / Gradle only if you build from source.

**Maven**

```xml
<dependency>
    <groupId>io.github.kienpc1234</groupId>
    <artifactId>luava</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.kienpc1234:luava:0.1.0-alpha")
```

**Gradle (Groovy DSL)**

```groovy
implementation 'io.github.kienpc1234:luava:0.1.0-alpha'
```

The artifact has zero transitive dependencies, so there is nothing else to add.

<details>
<summary>Other ways to get it</summary>

**Download the jar** from the
[GitHub release](https://github.com/KienPC1234/luava/releases/latest) and put
it on your classpath:

```bash
javac -cp luava-0.1.0-alpha.jar MyApp.java
java  -cp luava-0.1.0-alpha.jar:. MyApp
```

**Build from source** (this repository):

```bash
mvn package          # target/luava-0.1.0-alpha.jar
mvn test             # 31/31 PUC Lua 5.4.9 suites + unit tests
```

</details>

## Quick start

```java
LuaState state = new LuaState();
state.set("port", 8080);
System.out.println(state.eval("return 'port=' .. port", "@demo").toLuaString());
// port=8080
state.eval("function add(a, b) return a + b end");
System.out.println(state.call("add", 10, 20).toLong());
// 30
```

A single `LuaState` owns its globals, JIT settings, security policy and
explicit finalizer ownership. Use one state per thread or per tenant; states
do not share mutable state.

## Embedding API

The host surface is small and fluent:

| Call | Purpose |
| :--- | :--- |
| `new LuaState()` | Fresh state with the Lua standard libraries. |
| `state.eval(src[, chunkName])` | Compile and run a script, return its value. |
| `state.compile(src, chunkName, env)` | Compile once, call the closure many times. |
| `state.call("f", args...)` | Call a global Lua function from Java. |
| `state.set(name, obj)` / `state.get(name, type)` | Snapshot values across the bridge. |
| `state.setLive(name, obj)` | Expose a Java object as **live** userdata (mutations flow both ways). |
| `state.registerFunction(name, ...)` | Expose a typed Java lambda (`Supplier`, `Consumer`, `Function`, `BiFunction`, ...). |
| `state.registerModule(obj)` | Bind an `@LuaModule`-annotated class or instance. |
| `state.registerClass(clazz)` | Expose a class under its simple name (e.g. `ArrayList`). |
| `state.resourceLoader(loader)` | Resolve `require` from a JAR/classpath/virtual store. |

```java
// Typed host functions (no manual boxing)
state.registerFunction("answer", () -> 42);
state.registerFunction("add", Long.class, Long.class, (a, b) -> a + b);
state.registerFunction("onEvent", String.class, (Consumer<String>) log::info);

// Live collections: scripts read and mutate the original Java object
List<String> names = new ArrayList<>(List.of("first"));
state.setLive("names", names);
state.eval("names.add('second'); names[1] = 'changed'");
// names == ["changed", "second"]
```

## Safe embedding (untrusted scripts)

Both guards are one-liners:

```java
LuaState sandboxed = new LuaState()
        .sandbox()                          // drop os/io/package + strict Java policy
        .instructionLimit(10_000_000);      // or .timeout(Duration.ofSeconds(1))
sandboxed.setLive("api", myService);        // set/setLive/register* are not chainable
sandboxed.eval(userScript);                 // while true do end -> Lua error, host survives
```

- `sandbox()` removes `os`, `io`, `package`, `require`, `dofile`,
  `loadfile`, `java` and `luajava`, and switches the Java interop layer to
  the strict allowlist. `deny("os","io")` / `allow("os")` give fine-grained
  control.
- `instructionLimit(n)` and `timeout(d)` abort runaway scripts with a
  catchable Lua error (`pcall` works); the default path pays nothing.
- The Java bridge is additionally filtered by a host-access policy so
  reflection, process execution, the filesystem and the network are not
  reachable through `java.*`:

```java
state.javaPolicy(JavaAccessPolicy.STRICT);   // safe allowlist
state.javaAllow("java.util.*");
state.javaDeny("com.acme.internal.*");
```

  `JavaAccessPolicy.DEFAULT` (a fresh state) filters the **Java bridge only**;
  a fresh state still exposes the Lua `os`/`io`/`package` libraries. For
  untrusted scripts always combine `sandbox()` with
  `instructionLimit`/`timeout`. `JavaAccessPolicy.UNRESTRICTED` disables
  filtering and is only safe for fully trusted scripts.

## Java interop

- **Dynamic reflection**: scripts load any policy-allowed class with
  `java.import`, instantiate with `Class.new(...)`, and read/write
  static or instance members.
- **Annotation binding**: `@LuaModule`, `@LuaMethod`, `@LuaField` (on a
  field *or* a zero-argument getter — a computed read-only field),
  `@LuaParam`, `@LuaReturn`.
- **Live collections & proxies**: `List`, `Map`, `Set` and arrays passed
  with `setLive` are exposed as live userdata the script can mutate.
- **Overload resolution** uses a precise type distance (exact match >
  widening primitive > SAM lambda > subtype > varargs).
- **Automatic SAM conversion**: a Lua function passed to a Java method
  expecting a single-method interface (`Runnable`, `Consumer<T>`,
  `Function<T,R>`, `Predicate<T>`, `Comparator<T>`, ...) is wrapped as a
  dynamic proxy, and a Java SAM object called from Lua forwards to it.
- Resolved methods are cached and invoked through `MethodHandle`s (never
  repeated `Method.invoke`), and property reads memoize their invoker.

### EmmyLua stub generation

Annotated modules can emit EmmyLua stubs for IDE completion:

```java
state.registerModule(new GameApi());
String stubs = state.generateEmmyDocs();          // all registered modules
state.exportEmmyDocs(Path.of("luava-stubs"));     // one .lua per module
```

Only annotation-bound modules are documented; a module built by hand with
`registerModule(name, table -> ...)` has an arbitrary shape and is
intentionally excluded.

## Loading Lua modules from a JAR

```java
LuaState state = new LuaState()
        .resourceLoader(LuaResourceLoader.classpath())   // scripts inside the app JAR
        .resourcePath("scripts/?.lua;scripts/?/init.lua");
state.eval("local rules = require('scripts.rules')");

// or any virtual store:
LuaState mem = new LuaState()
        .resourceLoader(path -> path.equals("rules.lua") ? "return { answer = 42 }" : null);
```

`require` consults the virtual loader after the filesystem searchers, so
`package.path` semantics and error messages are unchanged; the loader only
adds a fifth searcher that is a no-op when no loader is set.

## CLI and REPL

The jar is executable and also exposes a small front end for developing and
smoke-testing Lua 5.4:

```bash
java -jar luava-0.1.0-alpha.jar script.lua arg1 arg2
java -jar luava-0.1.0-alpha.jar -e "print(1 + 2)"
java -jar luava-0.1.0-alpha.jar            # interactive REPL (also on a TTY)
```

A script sees the standard `arg` table (`arg[0]` is the script name).
Embedding through `LuaState` remains the primary use; the
[Usage Guide](docs/USAGE.md) has runnable examples.

## Execution model

- **Register bytecode VM** — 84 opcodes (83 standard + 1 internal
  dead-slot cleanup) with 32-bit instructions. The register file follows
  PUC-Rio semantics throughout: 1-based arrays, floor division,
  `a - floor(a/b)*b` modulo, `<close>` variables and tail calls.
- **Unboxed hot path** — three parallel stacks (`long[]` raw bits, `byte[]`
  type tags, `LuaValue[]` heap refs) keep numeric and boolean values out of
  the boxed heap during arithmetic and loops.
- **Coroutines on virtual threads** — each `LuaCoroutine` owns its stacks;
  `yield`/`resume` park and unpark carrier threads.

## Hybrid tiered JIT (on by default)

The interpreter is always available and is the deopt target. Hot Lua
functions whose shape passes a static subset analysis tier up to one JVM
method per proto (hidden class, unboxed `long` flow, direct
`INVOKESTATIC` self-recursion, type guards with interpreter resume-at-pc
deopt). Coroutines, debug hooks and execution timeouts never touch JIT code.

Supported today includes: integer and float arithmetic (int + float lanes,
C99 `^`, Lua shift/floor-division rules); `for`/`while`/`repeat`;
`if`/`and`/`or`/equality; string concat; generic-for (`pairs`, `ipairs`,
`gmatch`) for the built-in iterators; closure factories; `obj:method()` and
string-receiver methods (`str:sub`, `str:gmatch`, ...); and the
`math.sqrt`, `math.floor`, `math.floor(math.sqrt(x))` and `tostring`
intrinsics (identity-guarded, so reassigning or shadowing them deopts).

Knobs:

- `state.jitEnabled(false)` (or `true`, or `null` to follow the global
  default) toggles JIT for one state; the process-wide default is
  `-Dluava.jit=false`.
- `JitCompiler.prewarm(closure)` compiles a whole closure tree up front for
  low first-request latency; tier-up otherwise compiles on a background
  thread (`-Dluava.jit.sync=true` for deterministic measurement).
- Compiled classes are bounded by an LRU cache (512), so Metaspace cannot
  leak.
- Hotness thresholds are overridable with `-Dluava.jit.hotThreshold=N` /
  `-Dluava.jit.loopThreshold=N`.

## Conformance status

- **31/31** runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` files pass on
  Luava, asserted by `OfficialSuiteEvaluationTest` so any regression fails
  the build; **227** unit tests run alongside, including a byte-for-byte
  differential conformance suite against stock PUC Lua 5.4 and a fixed
  stdlib sweep run under JIT both on and off. All green with JIT both on
  and off, and at maximum JIT coverage.
- `all.lua`, the PUC driver, runs in user-test mode (`_U`): it re-runs every
  suite through its `string.dump`/`load` wrapper, adding an independent
  round-trip pass over the whole corpus.
- Test files are checksum-identical to the upstream tarball and are never
  edited by the runner.
- Excluded by design: `heavy.lua` (deliberate heap-exhaustion stress that
  needs a large heap; it passes on Luava but is not a conformance suite) and
  `main.lua` (stand-alone CLI driver; `os.execute`-driven against the
  reference `lua` binary, not applicable to an embedded engine — the Luava
  CLI is covered by `MainTest`).
- Robustness: deep recursion (3000 tested; a clean `stack overflow` past the
  10000-frame limit), 200-coroutine churn, 100k tail calls, heavy
  table/string/`<close>`/error pressure, and 8 concurrent states on one JVM
  (`LuavaStressTest`).

## Benchmarks

Paired interleave against stock LuaJ 3.0.1 (order-flipped, median, pinned,
warm=6, iters=10, `-Dluava.jit.sync=true`), **all 10 tasks won**:

| Task | Luava ms | LuaJ ms | LuaJ/Luava |
| :--- | ---: | ---: | ---: |
| arithmetic loop | 33.7 | 285.6 | **8.47×** |
| recursive fib | 243.8 | 2538.6 | **10.41×** |
| table insert/scan | 16.8 | 47.6 | **2.83×** |
| string concat | 10.9 | 33.1 | **3.03×** |
| closures/upvalues | 32.3 | 39.0 | **1.21×** |
| coroutines | 234.7 | 4549.9 | **19.39×** |
| hash table | 72.4 | 193.9 | **2.68×** |
| OOP metatables | 533.9 | 643.3 | **1.21×** |
| string patterns | 53.0 | 75.8 | **1.43×** |
| sieve | 168.4 | 309.1 | **1.84×** |

LuaJ runs its `LuaClosure.execute` interpreter as Lua 5.2 (no `//`, bitwise
ops, `<close>` or integer subtype); Luava implements 5.4. What remains
between Luava and PUC Lua (C) is dispatch cost.

Conformance is backed by a byte-for-byte differential suite
(`Lua54ConformanceTest`): each case's expected string was copied from stock
PUC Lua 5.4 output, covering operator matrices, integer boundaries,
`string.format`, patterns, metatables and numeric-string coercion.

Cold start (unpinned, median of 9, JDK 21): `LuaState` construction ~50 ms
(faster than LuaJ's ~57–65 ms); first eval of a small realistic script
~47 ms vs LuaJ's ~13 ms, because the first parse+compile loads JVM classes
once (~30 ms; the second identical compile is 0.02 ms) and a first-run hot
loop stays interpreted until tier-up. After a few calls the JIT overtakes
LuaJ (a 100k loop converges by call ~4 at ~35 ms/call vs ~285 ms/call).
Hosts that care about first-request latency call `JitCompiler.prewarm`.

## Scope: server embedding

Luava targets **embedding on the JVM**, not a drop-in replacement for the
stand-alone `lua` binary. Features that only make sense for the C CLI are out
of scope: native C modules (`.so`/`LUA_CPATH`) — hosts register Java
functions instead; `os.execute`-driven CLI test scaffolding; `string.dump` is
pure Java and round-trips within Luava (`load(string.dump(f))`).

## Layout

```text
src/main/java/org/luava/
├── frontend/lexer|parser|ast  # tokenizer, parser, AST nodes
├── midend/optimizer           # constant folding
├── runtime/                   # LuaState, LuaValue hierarchy, Varargs
│   ├── bytecode/              # OpCode, LuaProto, BytecodeCompiler, BytecodeVM
│   ├── eval/                  # CallStack, Upvalue, GCManager, Environment
│   ├── standard/              # base, table, string, math, io, os, coroutine, debug, utf8
│   │                          # + LuaResourceLoader (classpath/JAR modules)
│   ├── concurrency/           # LuaCoroutine (virtual threads)
│   ├── jit/                   # LuaToJvmTranslator, JitCompiler, JitRuntime
│   └── interop/               # live proxies, SAM adaptation
├── binding/                   # annotations, ModuleBinder, data converter
├── cli/                       # Main (script runner, -e, REPL)
└── emmydoc/                   # EmmyLua stub generator
src/test/java/org/luava/
├── OfficialSuiteEvaluationTest.java  # PUC suite runner (with assertions)
└── bytecode|frontend|binding|benchmark|stress|security|cli
tests/lua-5.4.9-tests/         # upstream PUC-Rio suite (do not modify)
```

## Contributing and design notes

The task-oriented [**Usage Guide**](docs/USAGE.md) covers embedding,
sandboxing, Java interop, module loading, performance tuning and common
pitfalls with runnable examples. Internal contributor rules live in
[`AGENTS.md`](AGENTS.md); the register-VM design and JIT roadmap live in
[`plan.md`](plan.md). The Lua 5.4 reference manual is under `docs/`.

## License

Licensed under the **Mozilla Public License Version 2.0 (MPL 2.0)**; see
[`LICENSE`](LICENSE).

- **For users & embedders**: you may embed Luava, call its APIs and integrate
  it into proprietary software, servers, game engines or SaaS without opening
  your own code.
- **For VM/engine modifications**: direct modifications to Luava's core files
  (for example `BytecodeVM.java`, `BytecodeCompiler.java`) must remain open
  source under MPL 2.0.
