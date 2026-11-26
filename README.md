# Luava: Pure-Java Lua 5.4 Engine (Java 21)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kienpc1234/luava.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.kienpc1234/luava)
[![License](https://img.shields.io/badge/license-MPL%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21%2B-orange.svg)](#install)

Pure-Java Lua 5.4 engine for the JVM: lexer, parser, AST, register bytecode
compiler, unboxed register VM, guarded tiered JIT, sandboxing, and Java interop.
Single shaded JAR with zero runtime dependencies beyond stock JDK 21+ (no `--enable-preview`).

    source -> Lexer -> Parser -> AST -> BytecodeCompiler -> LuaProto -> BytecodeVM / JitCode

- **Release**: `0.3.0` ("Vanguard") on [Maven Central](https://central.sonatype.com/artifact/io.github.kienpc1234/luava) (`io.github.kienpc1234:luava:0.3.0`) and [GitHub Releases](https://github.com/KienPC1234/luava/releases/latest)
- **Build & Test**: `mvn package` (`target/luava-0.3.0.jar`) and `mvn test`
- **Documentation**: [Usage Guide](docs/USAGE.md) (CLI flags, Java interop, sandboxing, tuning) and [Roadmap](docs/ROADMAP.md)

## Install

**Maven**

```xml
<dependency>
    <groupId>io.github.kienpc1234</groupId>
    <artifactId>luava</artifactId>
    <version>0.3.0</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.kienpc1234:luava:0.3.0")
```

**Gradle (Groovy DSL)**

```groovy
implementation 'io.github.kienpc1234:luava:0.3.0'
```

**Standalone CLI / REPL** (download `luava-0.3.0.jar` from [GitHub Releases](https://github.com/KienPC1234/luava/releases/latest)):

```bash
java -jar luava-0.3.0.jar script.lua arg1 arg2
java -jar luava-0.3.0.jar -e "print(1 + 2)"
java -jar luava-0.3.0.jar -v                    # Luava 0.3.0 Vanguard (Lua 5.4)
```

## Quick start

### 1. Evaluate scripts and call Lua functions

```java
import org.luava.runtime.LuaState;

public final class QuickStart {
    public static void main(String[] args) {
        try (LuaState state = new LuaState()) {
            state.set("port", 8080);
            String banner = state.eval("return 'port=' .. port", "@demo.lua").toLuaString();
            System.out.println(banner); // port=8080

            state.eval("function add(a, b) return a + b end");
            long sum = state.call("add", 10, 20).toLong();
            System.out.println(sum);    // 30
        }
    }
}
```

### 2. Expose Java functions and live objects to Lua

```java
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.luava.runtime.LuaState;

try (LuaState state = new LuaState()) {
    // Register typed Java lambdas directly
    state.registerFunction("slugify", String.class,
            (Function<String, String>) s -> s.trim().toLowerCase().replace(' ', '-'));

    // Expose live Java collections or POJOs (mutations flow both ways)
    List<String> tags = new ArrayList<>(List.of("jvm"));
    state.setLive("tags", tags);

    String slug = state.eval("""
            tags.add(slugify("Lua 5 4 Engine"))
            return tags[1] .. ":" .. tags[2]
            """).toLuaString();
    // slug == "jvm:lua-5-4-engine", tags == ["jvm", "lua-5-4-engine"]
}
```

### 3. Sandbox untrusted scripts and pool precompiled chunks

```java
import java.time.Duration;
import org.luava.runtime.ExecutionPolicy;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaStatePool;
import org.luava.runtime.LuaValue;

// Single-tenant sandboxed execution with instruction, timeout, and memory caps
try (LuaState sandbox = new LuaState()
        .sandbox()
        .instructionLimit(1_000_000)
        .timeout(Duration.ofMillis(500))
        .maxAllocationBytes(16 * 1024 * 1024)
        .maxMemoryBytes(32 * 1024 * 1024)
        .maxCoroutines(32)
        .maxCallDepth(256)
        .cancellable()) {
    long out = sandbox.eval("local s = 0; for i = 1, 100 do s = s + i end; return s").toLong();
}

// Multi-tenant isolated pool sharing a precompiled bytecode/JIT chunk
ExecutionPolicy policy = ExecutionPolicy.sandboxed(1_000_000, Duration.ofMillis(500));
try (LuaState.CompiledChunk chunk = LuaState.compileChunk("return 6 * 7", "@rule.lua");
     LuaStatePool pool = LuaStatePool.isolated(8, policy);
     LuaStatePool.Lease lease = pool.acquireLease()) {
    LuaValue result = lease.evalSnapshot(chunk); // 42
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

- **Compact Frontend & Zero-AST Retention**: `Lexer.scanStream()` feeds a compact `TokenStream` into `Parser` and `BytecodeCompiler` without intermediate `Token` allocations (while preserving the public Java 21 `Token` record on `scanTokens()`). `LuaProto` retains zero AST nodes or raw source strings; `string.dump`/`load` serialize and bounds-verify Luava's internal `LP\x02\x00` `LuaProto` container (backward-compatible with `v0.2.7` Luava chunks; not interchangeable with PUC `luac` C bytecode), and per-state `protoCache` enforces entry (`256`) and estimated cache-byte (`8 MiB`) budgets with hot/cold second-chance eviction (combine with `maxMemoryBytes(n)` and `-Xmx` for hard JVM heap caps).
- **Unboxed Bytecode VM**: 84 opcodes using parallel `long[]`, `byte[]`, and `LuaValue[]` register stacks, unboxed `LuaTable` numeric array lanes and inline string-keyed slots with Bloom membership checks, per-instruction field inline caches, and byte-slice `LuaString` / `LuaPattern` execution.
- **Guarded Tiered JIT**: Hot functions and loops compile to JVM bytecode (`MethodHandles.Lookup.defineHiddenClassWithClassData`) bounded by a 512-entry second-chance LRU cache, with constant-folded `LuaString` keys, static `TYPE_INT` lanes, loop register promotion, and `SelfCache` dispatch for tables and strings. Guarded execution (`timeout`, `instructionLimit` >= 8192, `cancellable`) runs guarded JIT kernels checking `LoopGuard.tick()` at back-edges; debug hooks, `maxCallDepth`, and sub-8192 instruction budgets stay in the interpreter.
- **Stackless & Virtual-Thread Coroutines**: Pure-Lua coroutines execute stacklessly on caller frames (with `VarHandle` state transitions, lazy yield-frame materialization, and JIT-inlined `coroutine.resume` slices) and graduate automatically to Java virtual threads across Java callbacks or protected boundaries.
- **JIT Flags**: `state.jitEnabled(Boolean)`, `JitCompiler.prewarm(fn)`, `-Dluava.jit=false`, `-Dluava.jit.sync=true`, `-Dluava.jit.hotThreshold=N` (default 50), `-Dluava.jit.loopThreshold=N` (default 8192).

## Verification & Benchmarks

- **Conformance & Differential Fuzzing**: 31/31 runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` suites pass (`OfficialSuiteEvaluationTest`), alongside 432 unit, lifecycle, adversarial security, binary-verifier fuzz, and PUC 5.4 differential fuzz tests (433 JUnit tests total, including a dedicated CI job installing `lua5.4`/`luac5.4` with `-Dluava.test.requirePuc=true` to enforce 650/650 seeded random program comparisons across Interpreter, Sync JIT, Guarded JIT, and PUC Lua 5.4 C, plus 1,200 malformed binary-chunk verifier cases) across sync JIT (`-Dluava.jit.sync=true`), async JIT (`-Dluava.jit.sync=false`), and JIT OFF (`-Dluava.jit=false`).

### Runtime Execution vs PUC Lua 5.4.8 C

Reproducible paired benchmark via `benchmarks/harness/audit_bench.sh` (11 interleaved pairs, 6 warmup, 10 iters, OpenJDK 21.0.13.1 `-Dluava.jit.sync=true -XX:+UseG1GC -Xmx1g` on Intel Xeon Gold 5120T vs PUC Lua 5.4.8 C):
- **Default JVM scheduling (`Exec`, `core=none`)**: **10/10** `Exec` workloads faster under the measured default JVM scheduling configuration (**2.08x** geometric mean).
- **Single-core pinned (`Exec 1-Core`, `taskset -c 0`)**: **8/10** workloads faster (**1.60x** geometric mean) when pinning the entire JVM including HotSpot C2 and G1GC threads onto one CPU core (`03_table_ops` at `0.96x`, `04_string_concat` at `0.83x`; virtual-thread `06_coroutines` runs unpinned per harness).
- **Cold compile + execute (`E2E 1-Core`)**: **3/10** workloads faster (**0.70x** geometric mean) when compiling a fresh `CompiledChunk`, executing it once, and closing it every iteration.

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

### Frontend Compilation & Retained-Heap A/B (`0f0a935` v0.2.7 vs `fb3666c`)

Measured sequentially on clean detached checkouts via [`benchmarks/frontend-jmh/run_ab.sh`](benchmarks/frontend-jmh/run_ab.sh) (`2` forks, `-Xms512m -Xmx2g -XX:+UseG1GC`, 10-workload corpus from `87` tokens to `600 KB` / `234,636` tokens; full 40-char commit SHAs, JAR SHA-256 checksums, start/finish timestamps, and raw CSVs in [`benchmarks/frontend-jmh/results/baseline-0f0a935/`](benchmarks/frontend-jmh/results/baseline-0f0a935/) and [`benchmarks/frontend-jmh/results/head/`](benchmarks/frontend-jmh/results/head/)):

| Stage / Probe | Scope | `0f0a935` Total / Heap | `fb3666c` Total / Heap | Geomean Speedup / Delta | Alloc / Op Geomean |
| :--- | :--- | ---: | ---: | ---: | ---: |
| `lexOnly` | 10 workloads (`scanTokens`) | 35.39 ms | 20.62 ms | **1.49x** | -45.2% |
| `parseOnly` | 10 workloads (AST build) | 17.23 ms | 11.19 ms | **1.88x** | -61.0% |
| `bytecodeOnly` | 10 workloads (`LuaProto`) | 20.85 ms | 10.31 ms | **1.80x** | -29.2% |
| `frontendCompileOnly` | 10 workloads (`compileChunk`) | 87.86 ms | 46.05 ms | **1.64x** | -40.9% |
| `compileAndLoad` | 10 workloads (chunk lifecycle) | 93.82 ms | 45.36 ms | **1.57x** | -41.2% |
| `compileCached` | 10 workloads (`protoCache` hit) | 0.372 ms | 0.001 ms | **132.56x** | -99.1% |
| `jitCompile` | `jit_kernel`, `jit_oop_ctor` | 0.712 ms | 0.689 ms | **1.03x** | -17.0% |
| `coldExecute` / `warmExecute` | 3 kernel/OOP/table workloads | 539.03 / 284.61 ms | 440.24 / 192.08 ms | **1.50x / 1.98x** | -33.7% / -38.6% |
| Retained: `16x large_600kb` | Live `CompiledChunk` heap | 216.46 MiB | 37.07 MiB | **-82.9%** (5.84x lower) | - |
| Retained: `32x 1MB comments` | Live `CompiledChunk` heap | 32.02 MiB | 0.01 MiB | **-99.97%** | - |
| Retained: `1500x 8KB` churn | Per-state `protoCache` heap | 28.82 MiB | 14.47 MiB | **-49.8%** (`7.49 / 8.00 MiB` est. budget) | - |

`jitCompile` maintains parity (`1.03x`, `-17.0%` alloc/op, `0` rejected JIT attempts) while adding static `TYPE_INT`, backward-liveness, and loop-promotion passes that drive the `1.50x` cold and `1.98x` warm execution gains. Regenerate with `benchmarks/frontend-jmh/run_ab.sh` and `benchmarks/harness/audit_bench.sh`.

## License

Mozilla Public License Version 2.0 ([`LICENSE`](LICENSE)). Embedders may integrate Luava into proprietary software without opening application code; modifications to Luava core files remain under MPL 2.0.
