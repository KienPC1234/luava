# Luava: Pure-Java Lua 5.4 Engine (Java 21)

Luava is a pure-Java implementation of Lua 5.4 for the JVM: lexer, parser, AST,
register bytecode compiler, register VM interpreter, tiered JIT, sandboxing and
Java interop. One jar, no runtime dependency beyond the JDK.

    source -> Lexer -> Parser -> AST -> BytecodeCompiler -> LuaProto -> BytecodeVM.execute()

Status: 0.1.0-alpha. The API may change before 1.0.

## Install

Published on Maven Central as
[`io.github.kienpc1234:luava`](https://central.sonatype.com/artifact/io.github.kienpc1234/luava).
Requires JDK 21+ (no `--enable-preview`, no JNI/FFM), and Maven 3.9+ or Gradle
only to build from source.

Maven:

```xml
<dependency>
    <groupId>io.github.kienpc1234</groupId>
    <artifactId>luava</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.kienpc1234:luava:0.1.0-alpha")
```

```groovy
implementation 'io.github.kienpc1234:luava:0.1.0-alpha'
```

The artifact has zero transitive dependencies.

### Other ways to get it

Download the jar from the
[GitHub release](https://github.com/KienPC1234/luava/releases/latest):

```bash
javac -cp luava-0.1.0-alpha.jar MyApp.java
java  -cp luava-0.1.0-alpha.jar:. MyApp
```

Build from source:

```bash
mvn package          # target/luava-0.1.0-alpha.jar
mvn test             # PUC Lua 5.4.9 suites + unit tests
```

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

A `LuaState` owns its globals, JIT settings, security policy and finalizer
ownership. Use one state per thread or per tenant; states share no mutable state.

## Embedding API

| Call | Purpose |
| :--- | :--- |
| `new LuaState()` | Fresh state with the standard libraries. |
| `state.eval(src[, chunkName])` | Compile and run a script, return its value. |
| `state.compile(src, chunkName, env)` | Compile once, call the closure many times. |
| `state.call("f", args...)` | Call a global Lua function from Java. |
| `state.set(name, obj)` / `state.get(name, type)` | Snapshot values across the bridge. |
| `state.setLive(name, obj)` | Expose a Java object as live userdata (mutations flow both ways). |
| `state.registerFunction(name, ...)` | Expose a typed Java lambda (`Supplier`, `Consumer`, `Function`, `BiFunction`, ...). |
| `state.registerModule(obj)` | Bind an `@LuaModule`-annotated class or instance. |
| `state.registerClass(clazz)` | Expose a class under its simple name (e.g. `ArrayList`). |
| `state.resourceLoader(loader)` | Resolve `require` from a JAR, classpath or virtual store. |

```java
state.registerFunction("answer", () -> 42);
state.registerFunction("add", Long.class, Long.class, (a, b) -> a + b);
state.registerFunction("onEvent", String.class, (Consumer<String>) log::info);

List<String> names = new ArrayList<>(List.of("first"));
state.setLive("names", names);
state.eval("names.add('second'); names[1] = 'changed'");
// names == ["changed", "second"]
```

## Safe embedding

```java
LuaState sandboxed = new LuaState()
        .sandbox()                          // drop os/io/package + strict Java policy
        .instructionLimit(10_000_000);      // or .timeout(Duration.ofSeconds(1))
sandboxed.setLive("api", myService);        // set/setLive/register* are not chainable
sandboxed.eval(userScript);                 // while true do end -> Lua error, host survives
```

- `sandbox()` removes `os`, `io`, `package`, `require`, `dofile`, `loadfile`,
  `java` and `luajava`, and switches Java interop to the strict allowlist.
  `deny("os","io")` / `allow("os")` give finer control.
- `instructionLimit(n)` and `timeout(d)` abort runaway scripts with a catchable
  Lua error (`pcall` works); the default path pays nothing.
- The Java bridge is filtered by a host-access policy, so reflection, process
  execution, the filesystem and the network are not reachable through `java.*`:

```java
state.javaPolicy(JavaAccessPolicy.STRICT);
state.javaAllow("java.util.*");
state.javaDeny("com.acme.internal.*");
```

`JavaAccessPolicy.DEFAULT` (a fresh state) filters the Java bridge only; a fresh
state still exposes the Lua `os`/`io`/`package` libraries. For untrusted scripts
combine `sandbox()` with `instructionLimit` or `timeout`.
`JavaAccessPolicy.UNRESTRICTED` disables filtering and is only safe for fully
trusted scripts.

## Java interop

- Dynamic reflection: scripts load any policy-allowed class with `java.import`,
  instantiate with `Class.new(...)`, and read or write static and instance
  members.
- Annotation binding: `@LuaModule`, `@LuaMethod`, `@LuaField` (on a field or on
  a zero-argument getter that acts as a computed read-only field), `@LuaParam`,
  `@LuaReturn`.
- Live collections and proxies: `List`, `Map`, `Set` and arrays passed with
  `setLive` are exposed as live userdata the script can mutate.
- Overload resolution uses a precise type distance: exact match, then widening
  primitive, then SAM lambda, then subtype, then varargs.
- SAM conversion: a Lua function passed to a Java method expecting a
  single-method interface (`Runnable`, `Consumer<T>`, `Function<T,R>`,
  `Predicate<T>`, `Comparator<T>`, ...) becomes a dynamic proxy, and a Java SAM
  object called from Lua forwards to it.
- Resolved methods are cached and invoked through `MethodHandle`s; property
  reads memoize their invoker.

### EmmyLua stubs

```java
state.registerModule(new GameApi());
String stubs = state.generateEmmyDocs();          // all registered modules
state.exportEmmyDocs(Path.of("luava-stubs"));     // one .lua per module
```

Only annotation-bound modules are documented. A module built by hand with
`registerModule(name, table -> ...)` has an arbitrary shape and is excluded.

## Loading modules from a JAR

```java
LuaState state = new LuaState()
        .resourceLoader(LuaResourceLoader.classpath())   // scripts inside the app JAR
        .resourcePath("scripts/?.lua;scripts/?/init.lua");
state.eval("local rules = require('scripts.rules')");

LuaState mem = new LuaState()
        .resourceLoader(path -> path.equals("rules.lua") ? "return { answer = 42 }" : null);
```

`require` consults the virtual loader after the filesystem searchers, so
`package.path` and error messages are unchanged; the loader adds a fifth searcher
that does nothing when no loader is set.

## CLI and REPL

```bash
java -jar luava-0.1.0-alpha.jar script.lua arg1 arg2
java -jar luava-0.1.0-alpha.jar -e "print(1 + 2)"
java -jar luava-0.1.0-alpha.jar            # interactive REPL (also on a TTY)
```

A script sees the standard `arg` table (`arg[0]` is the script name). Embedding
through `LuaState` remains the primary use; the
[Usage Guide](docs/USAGE.md) has runnable examples.

## Execution model

- Register bytecode VM: 84 opcodes (83 standard + 1 internal dead-slot cleanup)
  with 32-bit instructions. PUC-Rio semantics throughout: 1-based arrays, floor
  division, `a - floor(a/b)*b` modulo, `<close>` variables and tail calls.
- Unboxed hot path: three parallel stacks (`long[]` raw bits, `byte[]` type
  tags, `LuaValue[]` heap refs) keep numbers and booleans off the boxed heap
  during arithmetic and loops.
- Coroutines on virtual threads: each `LuaCoroutine` owns its stacks;
  `yield`/`resume` park and unpark carrier threads.

## Tiered JIT

The interpreter is always available and is the deopt target. Hot Lua functions
whose shape passes a static subset analysis tier up to one JVM method per proto
(hidden class, unboxed `long` flow, direct `INVOKESTATIC` self-recursion, type
guards with interpreter resume-at-pc deopt). Coroutines, debug hooks and
execution timeouts never touch JIT code.

Supported today: integer and float arithmetic (int and float lanes, C99 `^`,
Lua shift and floor-division rules); `for`/`while`/`repeat`; `if`/`and`/`or` and
equality; string concat; generic-for (`pairs`, `ipairs`, `gmatch`) for the
built-in iterators; closure factories; `obj:method()` and string-receiver
methods (`str:sub`, `str:gmatch`, ...); and the `math.sqrt`, `math.floor`,
`math.floor(math.sqrt(x))` and `tostring` intrinsics (identity-guarded, so
reassigning or shadowing them deopts).

Knobs:

- `state.jitEnabled(false)` (or `true`, or `null` for the global default)
  toggles JIT for one state; the process default is `-Dluava.jit=false`.
- `JitCompiler.prewarm(closure)` compiles a closure tree up front for low
  first-request latency; tier-up otherwise compiles on a background thread
  (`-Dluava.jit.sync=true` for deterministic measurement).
- Compiled classes are bounded by an LRU cache (512), so Metaspace cannot leak.
- Hotness thresholds use `-Dluava.jit.hotThreshold=N` and
  `-Dluava.jit.loopThreshold=N`.

## Conformance

- 31/31 runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` files pass, asserted by
  `OfficialSuiteEvaluationTest`, so a regression fails the build. 227 unit tests
  run alongside, including a byte-for-byte differential suite against stock PUC
  Lua 5.4 and a fixed stdlib sweep under JIT both on and off.
- `all.lua`, the PUC driver, runs in user-test mode (`_U`): it re-runs every
  suite through its `string.dump`/`load` wrapper, adding a round-trip pass over
  the corpus.
- Test files are checksum-identical to the upstream tarball and are never edited
  by the runner.
- Excluded by design: `heavy.lua` (deliberate heap-exhaustion stress that needs
  a large heap; it passes on Luava but is not a conformance suite) and
  `main.lua` (stand-alone CLI driver run with `os.execute` against the reference
  `lua` binary, not applicable to an embedded engine; the Luava CLI is covered
  by `MainTest`).
- Robustness: deep recursion (3000 tested; a clean `stack overflow` past the
  10000-frame limit), 200-coroutine churn, 100k tail calls, heavy
  table/string/`<close>`/error pressure, and 8 concurrent states on one JVM
  (`LuavaStressTest`).

## Benchmarks

Paired interleave against stock LuaJ 3.0.1 (order-flipped, median, pinned,
warm=6, iters=10, `-Dluava.jit.sync=true`), all 10 tasks won:

| Task | Luava ms | LuaJ ms | LuaJ/Luava |
| :--- | ---: | ---: | ---: |
| arithmetic loop | 33.7 | 285.6 | 8.47x |
| recursive fib | 243.8 | 2538.6 | 10.41x |
| table insert/scan | 16.8 | 47.6 | 2.83x |
| string concat | 10.9 | 33.1 | 3.03x |
| closures/upvalues | 32.3 | 39.0 | 1.21x |
| coroutines | 234.7 | 4549.9 | 19.39x |
| hash table | 72.4 | 193.9 | 2.68x |
| OOP metatables | 533.9 | 643.3 | 1.21x |
| string patterns | 53.0 | 75.8 | 1.43x |
| sieve | 168.4 | 309.1 | 1.84x |

LuaJ runs its `LuaClosure.execute` interpreter as Lua 5.2 (no `//`, bitwise ops,
`<close>` or integer subtype); Luava implements 5.4. What remains between Luava
and PUC Lua (C) is dispatch cost.

The differential suite (`Lua54ConformanceTest`) compares byte-for-byte against
stock PUC Lua 5.4 output: operator matrices, integer boundaries,
`string.format`, patterns, metatables and numeric-string coercion.

Cold start (unpinned, median of 9, JDK 21): `LuaState` construction around 50 ms,
against LuaJ's 57 to 65 ms. The first eval of a small script is around 47 ms
against LuaJ's 13 ms, because the first parse and compile load JVM classes once
(around 30 ms; a second identical compile is 0.02 ms) and a first-run hot loop
stays interpreted until tier-up. After a few calls the JIT overtakes LuaJ: a
100k loop converges by call 4 at around 35 ms per call against around 285 ms for
LuaJ. Hosts that care about first-request latency call `JitCompiler.prewarm`.

## Scope

Luava targets embedding on the JVM, not a drop-in replacement for the
stand-alone `lua` binary. Out of scope: native C modules (`.so`/`LUA_CPATH`) and
`os.execute`-driven CLI test scaffolding; hosts register Java functions instead.
`string.dump` is pure Java and round-trips within Luava
(`load(string.dump(f))`).

## Layout

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

## Contributing and design notes

The [Usage Guide](docs/USAGE.md) covers embedding, sandboxing, Java interop,
module loading, performance tuning and common pitfalls with runnable examples.
The Lua 5.4 reference manual is under `docs/`.

## License

Mozilla Public License Version 2.0 (MPL 2.0); see [`LICENSE`](LICENSE).

- Embedders may embed Luava, call its APIs and integrate it into proprietary
  software, servers, game engines or SaaS without opening their own code.
- Direct modifications to Luava's core files (for example `BytecodeVM.java`,
  `BytecodeCompiler.java`) must remain open source under MPL 2.0.
