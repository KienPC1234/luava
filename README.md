# Luava: Pure-Java Lua 5.4 Engine (Java 21)

Pure-Java Lua 5.4 engine for the JVM: lexer, parser, AST, register bytecode
compiler, unboxed register VM, guarded tiered JIT, sandboxing, and Java interop.
Single shaded JAR with zero runtime dependencies beyond JDK 21+.

    source -> Lexer -> Parser -> AST -> BytecodeCompiler -> LuaProto -> BytecodeVM / JitCode

- **Release**: `0.2.6` ("Meridian") on [Maven Central](https://central.sonatype.com/artifact/io.github.kienpc1234/luava) (`io.github.kienpc1234:luava:0.2.6`) and [GitHub Releases](https://github.com/KienPC1234/luava/releases/latest)
- **Build & Test**: `mvn package` (`target/luava-0.2.6.jar`) and `mvn test`
- **Documentation**: [Usage Guide](docs/USAGE.md) (CLI flags, Java interop, sandboxing, tuning) and [Roadmap](docs/ROADMAP.md)

## Quick start

```java
try (LuaState state = new LuaState()) {
    state.set("port", 8080);
    System.out.println(state.eval("return 'port=' .. port", "@demo").toLuaString());

    state.eval("function add(a, b) return a + b end");
    long sum = state.call("add", 10, 20).toLong();
}
```

Each `LuaState` owns isolated globals, registry, GC state, security policy, and
resource caps (one state per thread or tenant).

## Core API

| API | Purpose |
| :--- | :--- |
| `eval(src[, name])` / `compile(src, name, env)` | Compile and execute scripts or reusable closures |
| `LuaState.compileChunk(src, name)` / `LuaStatePool` | Precompile `AutoCloseable` bytecode/JIT chunks shared safely across pooled states |
| `call(name, args...)` / `evalAsync(src)` | Call global Lua functions or dispatch serially on virtual threads |
| `set` / `setLive` / `registerFunction` / `registerModule` | Snapshot conversion, live Java objects, lambdas, `@LuaModule` binding, `MethodHandle` cache |
| `sandbox()` / `deny(...)` / `javaPolicy(...)` | Strip `os`/`io`/`package`/`java` and enforce `JavaAccessPolicy` allow/deny rules |
| `instructionLimit(n)` / `timeout(d)` / `cancellable()` / `cancel()` | Preemptive execution budgets and cross-thread cancellation |
| `maxAllocationBytes(n)` / `maxMemoryBytes(n)` / `maxCoroutines(n)` / `maxCallDepth(n)` | Per-state stdlib allocation, retained heap, live coroutine, and stack depth limits |
| `stats()` / `close()` | Live call/JIT/deopt/allocation counters and deterministic cleanup |

## Execution Model & Tiered JIT

- **Unboxed Bytecode VM**: 84 opcodes using parallel `long[]`, `byte[]`, and `LuaValue[]` register stacks, unboxed `LuaTable` numeric array lanes with Bloom membership checks, and byte-slice `LuaString` / `LuaPattern` execution.
- **Guarded Tiered JIT**: Hot functions compile to JVM bytecode (`MethodHandles.Lookup.defineHiddenClass`) bounded by a 512-entry second-chance LRU cache. Guarded execution (`timeout`, `instructionLimit` >= 8192, `cancellable`) runs guarded JIT kernels checking `LoopGuard.tick()` at back-edges; debug hooks, `maxCallDepth`, and sub-8192 instruction budgets stay in the interpreter.
- **Stackless & Virtual-Thread Coroutines**: Pure-Lua coroutines execute on caller stacks and graduate automatically to Java virtual threads across Java callbacks or protected boundaries.
- **JIT Flags**: `state.jitEnabled(Boolean)`, `JitCompiler.prewarm(fn)`, `-Dluava.jit=false`, `-Dluava.jit.sync=true`, `-Dluava.jit.hotThreshold=N` (default 50), `-Dluava.jit.loopThreshold=N` (default 8192).

## Verification & Benchmarks

- **Conformance**: 31/31 runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` suites pass (`OfficialSuiteEvaluationTest`), alongside 388 unit, adversarial security, and PUC 5.4 differential tests across JIT ON, JIT OFF (`-Dluava.jit=false`), and synchronous aggressive tier-up (`-Dluava.jit.sync=true -Dluava.jit.hotThreshold=1`).

Paired benchmark across **Luava 0.2.6** (`-Dluava.jit.sync=true`), **LuaJ 3.0.1** (interleaved 5 pairs, pinned core 0 except coroutines, 6 warmup, 10 iters median best ms), and **PUC Lua 5.4.8 C** (6 warmup, 10 iters in-process best ms):

| Task | Luava 0.2.6 (ms) | LuaJ 3.0.1 (ms) | Lua 5.4.8 C (ms) | vs LuaJ | vs Lua C |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `01_arith_loop` | 36.50 | 258.72 | 60.21 | 7.09x | 1.65x |
| `02_fibonacci` | 235.18 | 2423.43 | 745.67 | 10.31x | 3.17x |
| `03_table_ops` | 17.45 | 47.83 | 13.50 | 2.74x | 0.77x |
| `04_string_concat` | 9.90 | 16.05 | 13.07 | 1.62x | 1.32x |
| `05_closures` | 27.78 | 63.38 | 22.79 | 2.28x | 0.82x |
| `06_coroutines` | 89.69 | 2351.69 | 27.22 | 26.22x | 0.30x |
| `07_hash_table` | 49.76 | 199.87 | 195.47 | 4.02x | 3.93x |
| `08_oop_metatables` | 289.97 | 595.44 | 222.50 | 2.05x | 0.77x |
| `09_string_pattern` | 70.73 | 78.94 | 88.33 | 1.12x | 1.25x |
| `10_sieve` | 123.46 | 307.38 | 144.12 | 2.49x | 1.17x |

## License

Mozilla Public License Version 2.0 ([`LICENSE`](LICENSE)). Embedders may integrate Luava into proprietary software without opening application code; modifications to Luava core files remain under MPL 2.0.
