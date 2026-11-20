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

- **Unboxed Bytecode VM**: 84 opcodes using parallel `long[]`, `byte[]`, and `LuaValue[]` register stacks, unboxed `LuaTable` numeric array lanes with Bloom membership checks, and byte-slice `LuaString` / `LuaPattern` execution.
- **Guarded Tiered JIT**: Hot functions compile to JVM bytecode (`MethodHandles.Lookup.defineHiddenClass`) bounded by a 512-entry second-chance LRU cache. Guarded execution (`timeout`, `instructionLimit` >= 8192, `cancellable`) runs guarded JIT kernels checking `LoopGuard.tick()` at back-edges; debug hooks, `maxCallDepth`, and sub-8192 instruction budgets stay in the interpreter.
- **Stackless & Virtual-Thread Coroutines**: Pure-Lua coroutines execute on caller stacks and graduate automatically to Java virtual threads across Java callbacks or protected boundaries.
- **JIT Flags**: `state.jitEnabled(Boolean)`, `JitCompiler.prewarm(fn)`, `-Dluava.jit=false`, `-Dluava.jit.sync=true`, `-Dluava.jit.hotThreshold=N` (default 50), `-Dluava.jit.loopThreshold=N` (default 8192).

## Verification & Benchmarks

- **Conformance**: 31/31 runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` suites pass (`OfficialSuiteEvaluationTest`), alongside 411 unit, lifecycle, adversarial security, and PUC 5.4 differential tests across sync JIT (`-Dluava.jit.sync=true`), async JIT (`-Dluava.jit.sync=false`), and JIT OFF (`-Dluava.jit=false`).

Reproducible paired benchmark via `benchmarks/harness/audit_bench.sh` (11 interleaved pairs, pinned core 0 except coroutines, 6 warmup, 10 iters, OpenJDK 21.0.13.1 `-Dluava.jit.sync=true -XX:+UseG1GC -Xmx1g` on Intel Xeon Gold 5120T vs PUC Lua 5.4.8 C):

| Task | Exec: Luava (ms / IQR) | Exec: Lua 5.4.8 C (ms / IQR) | Exec LuaC/Luava | E2E: Luava (ms) | E2E: Lua C (ms) | E2E LuaC/Luava |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `01_arith_loop` | 36.23 / 0.56 | 60.35 / 0.78 | 1.67x | 65.52 | 60.16 | 0.92x |
| `02_fibonacci` | 236.28 / 0.41 | 726.46 / 1.31 | 3.07x | 252.34 | 724.17 | 2.87x |
| `03_table_ops` | 17.31 / 0.06 | 13.80 / 0.12 | 0.80x | 74.55 | 15.50 | 0.21x |
| `04_string_concat` | 12.65 / 0.82 | 11.80 / 0.09 | 0.93x | 26.46 | 11.92 | 0.45x |
| `05_closures` | 28.63 / 1.12 | 23.80 / 0.16 | 0.83x | 66.11 | 24.30 | 0.37x |
| `06_coroutines` | 66.90 / 1.34 | 28.84 / 0.40 | 0.43x | 75.01 | 28.84 | 0.38x |
| `07_hash_table` | 152.41 / 6.79 | 212.01 / 0.94 | 1.39x | 230.00 | 205.99 | 0.90x |
| `08_oop_metatables` | 258.78 / 3.97 | 225.65 / 2.90 | 0.87x | 685.59 | 229.04 | 0.33x |
| `09_string_pattern` | 83.65 / 4.76 | 92.12 / 0.34 | 1.10x | 193.31 | 92.11 | 0.48x |
| `10_sieve` | 138.30 / 0.57 | 146.39 / 0.69 | 1.06x | 303.14 | 147.48 | 0.49x |

`Exec` measures precompiled chunk execution (`LuaState.compileChunk` / `luaL_loadfile`); `E2E` includes full parse, bytecode/JIT compilation, and execution per iteration. Raw pair CSVs and metadata can be regenerated with `benchmarks/harness/audit_bench.sh`.

## License

Mozilla Public License Version 2.0 ([`LICENSE`](LICENSE)). Embedders may integrate Luava into proprietary software without opening application code; modifications to Luava core files remain under MPL 2.0.
