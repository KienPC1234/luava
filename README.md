# Luava: Pure-Java Lua 5.4 Engine (Java 21)

Luava is a pure-Java implementation of Lua 5.4: lexer, parser, AST,
register-based bytecode compiler, and a register VM interpreter.
No JNI, no external runtime dependencies, single `mvn package` jar.

Pipeline: `Lua source → Lexer → Parser → AST → BytecodeCompiler →
LuaProto → BytecodeVM.execute()`.

Key design points:

- **Register VM**: 84 opcodes (83 Lua 5.4 standard + 1 internal dead-slot
  cleanup), 32-bit instructions (`OpCode`, `Instruction`,
  `LuaProto`), close to PUC-Rio Lua 5.4 semantics (1-based arrays, `//`
  floor division, `a - floor(a/b)*b` modulo, `<close>` variables, tail calls).
- **Unboxed hot path**: three parallel stacks (`long[]` raw bits,
  `byte[]` type tags, `LuaValue[]` heap refs) avoid boxing integers,
  floats, and booleans in arithmetic and loops.
- **Coroutines on virtual threads**: each `LuaCoroutine` owns its stacks;
  `yield`/`resume` park and unpark carrier threads.
- **Java interop**: `java.import` reflection, `@LuaModule`/`@LuaMethod`
  annotations, `state.setLive`/`registerFunction`, live collection proxies,
  and automatic Lua-function-to-SAM-interface adaptation.

## Quick start

Requirements: JDK 21+ (stock LTS, **no `--enable-preview`**), Maven 3.9+.

```bash
mvn compile          # build
mvn test             # full suite: PUC Lua 5.4.9 files + unit tests
mvn package          # jar in target/
```

```java
LuaState state = new LuaState();
state.set("port", 8080);
System.out.println(state.eval("return 'port=' .. port", "@demo").toLuaString());
// port=8080
state.eval("function add(a, b) return a + b end");
System.out.println(state.call("add", 10, 20).toLong());
// 30
```

### Safe embedding (untrusted scripts)

Luava targets the same niche as LuaJ but with a security-first API. Both
guards are one-liners:

```java
LuaState sandboxed = new LuaState()
        .sandbox()                          // drop os/io/package + Java interop
        .instructionLimit(10_000_000)       // or .timeout(Duration.ofSeconds(1))
        .setLive("api", myService);
sandboxed.eval(userScript);                 // while true do end -> Lua error, host survives
```

- `sandbox()` removes `os`, `io`, `package`, `require`, `dofile`,
  `loadfile`, `java` and `luajava`. `deny("os","io")` / `allow("os")`
  give fine-grained control.
- `instructionLimit(n)` and `timeout(d)` abort runaway scripts with a
  catchable Lua error (`pcall` works); the default path pays nothing.

## Conformance status

- **31/31** runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` files pass
  (`OfficialSuiteEvaluationTest`, asserts failures so the build goes red
  on any regression; 74 unit tests green alongside, including the Luava
  advanced-interop suite (14) and the stress suite (6)).
- Test files are checksum-identical to the upstream tarball; the harness
  never edits them.
- Excluded by design: `heavy.lua` (intentional memory-overflow stress)
  and `all.lua` (needs C test libs plus an interactive harness).
- Robustness: deep recursion to 8000+ levels (heap frames, clean
  `stack overflow` past the 10000 limit), 2000-coroutine churn, table /
  string / error pressure, 8-thread concurrent states, flat heap across
  repetitions — see `LuavaStressTest`.

## Comparison: PUC Lua (C) vs Luava vs LuaJ

Engines compared: PUC-Rio Lua 5.4.8 (`lua-source/src/lua`), this repo,
and LuaJ 3.0 (Lua 5.2, `LuaClosure.execute` interpreter, built from
source without its optional BCEL backend).

A 13-line script covering OOP via metatables, coroutine prime sieve,
memoized fib(30), varargs, tail-recursive fold, record sort, cyclic
deepcopy, `pcall`/`xpcall`, upvalue counters, a 100k loop, and
`string.format` produces **byte-identical output on all three engines**:

```text
area=27.707963 | primes: 46 sum=4227 | fib30=832040 | sum=15 n=3
fold=10 | sorted=ann,dee,bob,cid | deepcopy=true,3
pcall=false code=42 | xpcall-tb=true | counter=2
loop=300000 | fmt=ff|"a\"b"|0.333 | ALL-OK
```

| Criterion | PUC Lua (C) | Luava | LuaJ |
| :--- | :--- | :--- | :--- |
| Language version | 5.4 | 5.4 | 5.2 (no `//`, bitwise ops, `<close>`, integer subtype) |
| Execution model | Register VM in C | Register VM in Java (`BytecodeVM`) | Register VM in Java (`LuaClosure.execute`) |
| Number fast path | Native | Unboxed triple-stack | Boxed `LuaInteger`/`LuaDouble` objects |
| Coroutines | Own C stacks | Java virtual threads, per-coroutine stacks | Java threads / OrphanedThread |
| Compliance evidence | Reference | 31/31 PUC 5.4.9 files | Hand-written 5.2-era scripts, no PUC suite |
| Cold start (fresh JVM, 100k loop) | n/a | **~211 ms** (≈112 ms JVM boot + ~100 ms engine) | ~211 ms (≈114 ms boot + ~100 ms engine) |
| Warmed 1M-iteration loop | ~8 ms | **~41 ms** | ~70 ms |
| fib(24) | — | **~63 ms** | ~70 ms |
| Table 100k r/w | — | **~39 ms** | ~59 ms |
| Closure 500k calls | — | **~168 ms** | ~215 ms |
| Runtime deps | libc | Pure Java 21 LTS (no preview, no JNI/FFM) | Java, optional BCEL for its JIT |
| JIT status of dispatch loop | n/a | **Compiled**: `runLoop()` at 6955 bytes fits under HotSpot's 8 KB `HugeMethodLimit` (OSR + C2 verified via `PrintCompilation`) | Compiled: OSR + C2 at 3982 bytes |

Luava beats LuaJ on every measured workload while implementing the newer
language (5.4 vs 5.2). The remaining gap to C is dispatch cost; closing
it further would need superinstructions or a JIT backend.

## Layout

```text
src/main/java/org/luava/
├── frontend/lexer|parser|ast  # tokenizer, parser, AST nodes
├── midend/optimizer           # constant folding, type inference
├── runtime/                   # LuaState, LuaValue hierarchy, Varargs
│   ├── bytecode/              # OpCode, LuaProto, BytecodeCompiler, BytecodeVM
│   ├── eval/                  # CallStack, Upvalue, GCManager, Environment
│   ├── standard/              # base, table, string, math, io, os, coroutine, debug, utf8
│   ├── concurrency/           # LuaCoroutine (virtual threads)
│   └── interop/               # live proxies, SAM adaptation
├── binding/                   # annotations, ModuleBinder, data converter
└── emmydoc/                   # EmmyLua stub generator
src/test/java/org/luava/
├── OfficialSuiteEvaluationTest.java  # PUC suite runner (with assertions)
├── bytecode|frontend|binding|benchmark|stress
tests/lua-5.4.9-tests/         # upstream PUC-Rio suite (do not modify)
```

Internal contributor rules live in `AGENTS.md`; the register-VM design
record lives in `PLANS.md`. Lua 5.4 reference manual is under `docs/`.

## License

This project is licensed under the **Mozilla Public License Version 2.0 (MPL 2.0)**.
See the [LICENSE](LICENSE) file for the full license text.

- **For Users & Embedders**: You can freely embed Luava as a dependency, call its APIs, integrate it into proprietary software, Minecraft servers, game engines, or SaaS products without being forced to open-source your proprietary code.
- **For VM/Engine Modifications**: Any direct modifications to Luava's core files (such as `BytecodeVM.java`, `BytecodeCompiler.java`, etc.) must remain open source under MPL 2.0.
