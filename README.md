# Luava: Pure-Java Lua 5.4 Engine (Java 21)

Pure-Java Lua 5.4 engine for the JVM: lexer, parser, AST, register bytecode
compiler, unboxed register VM, guarded tiered JIT, sandboxing, and Java interop.
Single shaded JAR with zero runtime dependencies beyond JDK 21+.

    source -> Lexer -> Parser -> AST -> BytecodeCompiler -> LuaProto -> BytecodeVM / JitCode

- **Release**: `0.2.7` ("Bastion") on [Maven Central](https://central.sonatype.com/artifact/io.github.kienpc1234/luava) (`io.github.kienpc1234:luava:0.2.7`) and [GitHub Releases](https://github.com/KienPC1234/luava/releases/latest)
- **Build & Test**: `mvn package` (`target/luava-0.2.7.jar`) and `mvn test`
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
| `maxAllocationBytes(n)` / `maxMemoryBytes(n)` / `maxCoroutines(n)` / `maxCallDepth(n)` | Per-state stdlib allocation, best-effort retained heap, live coroutine, and stack depth limits |
| `stats()` / `close()` | Live call/JIT/deopt/allocation counters and deterministic cleanup |

## Execution Model & Tiered JIT

- **Unboxed Bytecode VM**: 84 opcodes using parallel `long[]`, `byte[]`, and `LuaValue[]` register stacks, unboxed `LuaTable` numeric array lanes and inline string-keyed slots with Bloom membership checks, per-instruction field inline caches, and byte-slice `LuaString` / `LuaPattern` execution.
- **Guarded Tiered JIT**: Hot functions and loops compile to JVM bytecode (`MethodHandles.Lookup.defineHiddenClassWithClassData`) bounded by a 512-entry second-chance LRU cache, with constant-folded `LuaString` keys, static `TYPE_INT` lanes, loop register promotion, and `SelfCache` dispatch for tables and strings. Guarded execution (`timeout`, `instructionLimit` >= 8192, `cancellable`) runs guarded JIT kernels checking `LoopGuard.tick()` at back-edges; debug hooks, `maxCallDepth`, and sub-8192 instruction budgets stay in the interpreter.
- **Stackless & Virtual-Thread Coroutines**: Pure-Lua coroutines execute stacklessly on caller frames (with `VarHandle` state transitions, lazy yield-frame materialization, and JIT-inlined `coroutine.resume` slices) and graduate automatically to Java virtual threads across Java callbacks or protected boundaries.
- **JIT Flags**: `state.jitEnabled(Boolean)`, `JitCompiler.prewarm(fn)`, `-Dluava.jit=false`, `-Dluava.jit.sync=true`, `-Dluava.jit.hotThreshold=N` (default 50), `-Dluava.jit.loopThreshold=N` (default 8192).

## Verification & Benchmarks

- **Conformance**: 31/31 runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` suites pass (`OfficialSuiteEvaluationTest`), alongside 417 unit, lifecycle, adversarial security, and PUC 5.4 differential tests (418 JUnit tests total) across sync JIT (`-Dluava.jit.sync=true`), async JIT (`-Dluava.jit.sync=false`), and JIT OFF (`-Dluava.jit=false`), plus standalone JMH frontend/JIT pipeline benchmarks (`benchmarks/frontend-jmh`).

Reproducible paired benchmark via `benchmarks/harness/audit_bench.sh` (11 interleaved pairs, 6 warmup, 10 iters, OpenJDK 21.0.13.1 `-Dluava.jit.sync=true -XX:+UseG1GC -Xmx1g` on Intel Xeon Gold 5120T vs PUC Lua 5.4.8 C):

| Task | Exec: Luava (ms / IQR) | Exec: Lua 5.4.8 C (ms / IQR) | Exec LuaC/Luava | Exec 1-Core: Luava (LuaC/Luava) | E2E 1-Core: Luava (ms) | E2E 1-Core: Lua C (ms) | E2E LuaC/Luava |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `01_arith_loop` | 6.45 / 0.05 | 60.26 / 0.08 | 9.34x | 6.49 (9.30x) | 21.84 | 60.16 | 2.75x |
| `02_fibonacci` | 234.75 / 1.48 | 724.84 / 4.64 | 3.09x | 236.96 (3.08x) | 252.70 | 721.29 | 2.85x |
| `03_table_ops` | 5.76 / 0.21 | 13.69 / 0.14 | 2.38x | 14.42 (0.96x) | 58.54 | 15.43 | 0.26x |
| `04_string_concat` | 5.94 / 0.23 | 11.76 / 0.21 | 1.98x | 14.34 (0.83x) | 26.43 | 11.84 | 0.45x |
| `05_closures` | 16.45 / 0.09 | 23.77 / 0.10 | 1.44x | 19.87 (1.20x) | 58.02 | 24.20 | 0.42x |
| `06_coroutines` | 26.59 / 0.58 | 28.83 / 0.41 | 1.08x | 26.48 (1.09x) | 50.22 | 29.12 | 0.58x |
| `07_hash_table` | 64.38 / 2.43 | 209.14 / 2.66 | 3.25x | 127.02 (1.67x) | 201.02 | 204.97 | 1.02x |
| `08_oop_metatables` | 211.44 / 2.84 | 225.86 / 3.27 | 1.07x | 213.72 (1.06x) | 611.53 | 229.49 | 0.38x |
| `09_string_pattern` | 70.51 / 0.72 | 92.61 / 0.71 | 1.31x | 68.35 (1.35x) | 164.52 | 92.13 | 0.56x |
| `10_sieve` | 93.78 / 1.34 | 145.62 / 0.80 | 1.55x | 94.47 (1.55x) | 266.44 | 147.62 | 0.55x |
| **Geomean / Total** | **736.05 ms** | **1536.38 ms** | **2.08x** | **822.12 ms (1.60x)** | **1711.26 ms** | **1536.25 ms** | **0.70x** |

`Exec` measures precompiled chunk execution (`LuaState.compileChunk` / `luaL_loadbuffer`) under default JVM scheduling (`core=none`, 10/10 tasks faster than PUC Lua 5.4.8 C, 2.08x geometric mean); `Exec 1-Core` pins the entire JVM including HotSpot C2 and G1GC threads onto core 0 (`taskset -c 0`, except virtual-thread coroutines). `E2E 1-Core` compiles a fresh `CompiledChunk`, executes it once, and closes it on every iteration; isolated Lexer, Parser, BytecodeCompiler, and JIT compilation stages are benchmarked in `benchmarks/frontend-jmh`. Raw pair CSVs and metadata can be regenerated with `benchmarks/harness/audit_bench.sh`.

## License

Mozilla Public License Version 2.0 ([`LICENSE`](LICENSE)). Embedders may integrate Luava into proprietary software without opening application code; modifications to Luava core files remain under MPL 2.0.
