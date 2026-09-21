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
- **Multi-tenant states**: use one `LuaState` per thread or tenant. States
  do not share globals, security policy, value metatables, JIT settings, or
  explicit finalizer ownership.
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
        .sandbox()                          // drop os/io/package + strict Java policy
        .instructionLimit(10_000_000)       // or .timeout(Duration.ofSeconds(1))
        .setLive("api", myService);
sandboxed.eval(userScript);                 // while true do end -> Lua error, host survives
```

- `sandbox()` removes `os`, `io`, `package`, `require`, `dofile`,
  `loadfile`, `java` and `luajava`, and switches the Java interop layer to
  the strict allowlist. `deny("os","io")` / `allow("os")` give
  fine-grained control.
- `instructionLimit(n)` and `timeout(d)` abort runaway scripts with a
  catchable Lua error (`pcall` works); the default path pays nothing.

### Java interop access policy

The `java.*` bridge is filtered by a host-access policy so untrusted
scripts cannot escape through reflection. Every `java.import`,
`java.new`, `java.proxy`, `java.array` and every `Class` member lookup is
checked against the active policy.

- `JavaAccessPolicy.DEFAULT` (a fresh `LuaState`) blocks process
  execution (`Runtime`, `ProcessBuilder`, `ProcessHandle`), JVM exit
  (`System`), reflection (`Class`, `ClassLoader`, `java.lang.reflect.*`,
  `java.lang.invoke.*`), the filesystem (`java.io.*`, `java.nio.*`) and
  the network (`java.net.*`), while allowing ordinary application and
  collection classes (`java.util.*`, `java.lang.Math`, `StringBuilder`,
  ...).
- **Scope of the default policy.** `DEFAULT` filters the **Java bridge
  only**. A fresh `LuaState` still exposes the Lua standard libraries
  (`os`, `io`, `package`), so `os.execute`/`io.open` from a script are
  *not* blocked by `JavaAccessPolicy.DEFAULT` — they only fail once you
  remove those libraries. For untrusted scripts always combine
  `sandbox()` (which drops `os`/`io`/`package`/`java`) with
  `instructionLimit`/`timeout`.
- `sandbox()` installs `JavaAccessPolicy.STRICT`: only the safe allowlist
  is reachable, and `allow("java")` does **not** re-open the dangerous
  classes.
- `state.javaPolicy(policy)`, `state.javaAllow("java.io.*")` and
  `state.javaDeny("com.acme.internal.*")` tune it; patterns are exact
  names, package prefixes (`java.io.*`) or `*`.
- `JavaAccessPolicy.UNRESTRICTED` disables filtering — use only for fully
  trusted scripts.

## Scope: server embedding, not a drop-in `lua` CLI

Luava is an **embedding engine for JVM servers**, not a replacement for
the stand-alone `lua` binary. Scripts are loaded by the host through
`eval` / `setLive` / `registerFunction`; host applications expose exactly
the API they want. Features that only make sense for the C command-line
interpreter are intentionally out of scope:

- loading native C modules (`.so` / `LUA_CPATH`) — hosts register Java
  functions instead;
- `os.execute`-driven CLI test scaffolding (`main.lua`) and the
  `MEMLIMIT` environment-variable tests;
- cross-loading bytecode with the reference C interpreter: `string.dump`
  is pure Java and its chunks load back in Luava (`load(string.dump(f))`);
- `heavy.lua`'s deliberate 1 GB allocation stress.

The official suite is therefore run as an engine test. `main.lua` is
purely a stand-alone interpreter driver (it spawns the CLI via
`os.execute`), so it is **excluded** rather than faked with the reference
C binary. `files.lua` runs its full i/o, `loadfile` and `os.date`
coverage on Luava in the suite's own embedded mode (`_port`), which skips
only its `arg[0]`-driven CLI block. `all.lua` (needs the C `T` harness)
and `heavy.lua` are excluded by design.

## Conformance status

- **30/30** runnable PUC-Rio `tests/lua-5.4.9-tests/*.lua` files pass on
  Luava (`OfficialSuiteEvaluationTest`, asserts failures so the build goes
  red on any regression; 170 unit tests green alongside, including a
  byte-for-byte differential conformance suite against stock PUC Lua 5.4).
- Test files are checksum-identical to the upstream tarball; the harness
  never edits them.
- Excluded by design: `heavy.lua` (intentional memory-overflow stress),
  `all.lua` (needs C test libs plus an interactive harness) and `main.lua`
  (stand-alone CLI driver, not applicable to an embedded engine).
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
| Compliance evidence | Reference | 30/30 PUC 5.4.9 files | Hand-written 5.2-era scripts, no PUC suite |
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

### Hybrid tiered JIT (on by default)

The interpreter stays an always-available engine and fallback. Hot Lua
functions decided by a static subset analysis (integer/table kernels, no
metamethods, no yield) tier up to one JVM method per proto (hidden class,
unboxed `long` flow, direct `INVOKESTATIC` self-recursion, type guards
with interpreter resume-at-pc deopt). Coroutines, debug hooks and
execution timeouts never touch JIT code paths.

Coverage and configuration:

- **Hotness is per proto, by calls and by loop trip count**, so a kernel
  tiers up whether it is *called* ≥50 times (`OP_CALL`/`OP_TAILCALL`) or
  entered once with a numeric `for` loop whose trip count reaches
  `JIT_LOOP_THRESHOLD` (8192). The loop request fires once per `FORPREP`
  (never per iteration), so a single-invocation heavy loop is covered with
  no dispatch overhead.
- **Repeated evaluation reuses bytecode**: `LuaState` keeps a bounded LRU of
  compiled proto trees keyed by chunk name + source, so a server that
  `eval`s the same script per request shares one proto and its JIT hotness
  accumulates across requests (each call still gets a fresh closure/_ENV).
- **Top-level chunks with a single-value return run compiled**: once a
  cached chunk proto tier-ups, `BytecodeVM.execute` enters its kernel
  directly instead of dispatching every instruction; a deopt resumes at the
  faulting pc. Statement-only chunks (no value return) stay interpreted.
- **Numeric `for` loops are JIT-compiled** (`FORPREP`/`FORLOOP`); the
  integer lane is unboxed and a float loop falls to the float lane.
  **`while`/`repeat` are JIT-compiled too**, via the two-register numeric
  comparisons `LT`/`LE`.
- **Arithmetic is JIT-compiled for both int and float**: `ADD`/`SUB`/`MUL`,
  `DIV`, `MOD`, `IDIV`, `POW`, and the two-register/immediate bitwise ops
  (`BAND`/`BOR`/`BXOR`/`SHL`/`SHR`/`SHLI`/`SHRI`) emit numeric or integer
  lanes; the constant forms `ADDI`, `ADDK`, `SUBK`, `MULK`, `DIVK`, `POWK`,
  `IDIVK`, `MODK`, `BANDK`, `BORK`, `BXORK` carry the same dispatch. `/`
  and `^` use C99 `pow` semantics; shifts follow the Lua 5.4 negative-count
  rule; `//` and `%` use floor semantics; division/modulo by zero deopts so
  the interpreter raises the exact error.
- **Control flow is JIT-compiled**: `EQ`, `EQK`, `LT`, `LE`, `TEST`,
  `TESTSET`, `LFALSESKIP` cover equality, `and`/`or`, truthiness and `if`.
  Mixed int/float equality (`1 == 1.0`) and NaN ordering match the
  interpreter bit-for-bit.
- **String concatenation** (`CONCAT`) is compiled when every operand is a
  plain string/number; a metatable `__concat` deopts.
- **Field access is metatable-aware**: `GETFIELD` fast-paths a non-nil
  `rawget` (independent of any metatable), so `self.x` on an OOP instance
  compiles instead of deopting; a nil raw hit (the `__index` case) deopts
  to the interpreter.
- **`math.sqrt` is an intrinsic**: `return math.sqrt(x)` and
  `local r = math.sqrt(x)` compile inline to `Math.sqrt` behind an identity
  guard on the shared `MathLib.SQRT` singleton (reassigning `math.sqrt` or
  shadowing `math` deopts to the interpreter, preserving exact semantics).
  A float-returning method such as `Vec:length()` therefore now JIT-compiles
  even though it calls a builtin; before, the builtin call fell outside the
  subset and the whole method stayed interpreted.
- **Guards disable JIT**: while `instructionLimit`/`timeout` (a
  `LoopGuard`) or a debug hook is active, calls run on the interpreter —
  correct, but without JIT speedup. This is the safe default for untrusted
  scripts.
- **Per-state control**: `state.jitEnabled(false)` (or `true`, or `null`
  to follow the global default) toggles JIT for one state, so a server can
  mix trusted and untrusted tenants in one JVM. The process-wide default
  is `ENABLE_JIT`, set with `-Dluava.jit=false`.
- **Prewarm**: `JitCompiler.prewarm(closure)` or the
  `prewarm(LuaFunction)` overload compiles up front for servers;
  `prewarm(closure, state)` is a no-op when that state has JIT disabled.
  Otherwise tier-up compiles on a background thread
  (`-Dluava.jit.sync=true` for deterministic measurement).
- **Compiled classes are bounded** by an LRU cache (512); eviction simply
  returns that proto to the interpreter, so Metaspace cannot leak.

10-task benchmark vs LuaJ 3.0.1 (paired, order-flipped, median, warm=4,
iters=8, `-Dluava.jit.sync=true`, re-run 2026-09-19):
**6 wins** (fib **10.4×**, coroutines 18.5×, hash 1.55×, arith 1.45×,
closures 1.25×, oop **1.12×**), **2 near-ties** (table 1.06×, concat 1.00×),
**2 small losses** (pattern 1.08×, sieve 1.03×). 30/30 PUC suites + 161 unit
tests stay green with JIT both off and on.

The 2026-09-20 coverage pass widened the compiled subset substantially:
`LT`/`LE`/`EQ`/`EQK`/`TEST`/`TESTSET`/`LFALSESKIP` (so `while`/`repeat`,
`and`/`or`, equality and `if` compile), `DIV`/`MOD`/`IDIV`/`POW`, the full
two-register and immediate bitwise set, `CONCAT`, and a single-value
top-level chunk fast lane. Two structural limits were also removed: the
numeric lattice now joins `INT ∪ NUM` to `NUM` instead of rejecting the
merge (so a table-read loop with an integer accumulator compiles), and a
vararg signature is only rejected when it actually reads `...`. Loop-driven
tier-up (via `FORPREP` trip count) lets a function called only once still
compile its heavy loop. Measured JIT-on vs JIT-off: `while` loops **~23×**,
`if`-in-loop **~12×**, single-invocation heavy loops **~5.6×**, concat
1.8×, and the previously-covered fib/closures/oop as before.

A follow-up pass removed the last major structural rejections. Void
(`RETURN0`-only) chunks now compile under the unboxed `long` ABI with a
sentinel callers materialize, `OP_CLOSURE`/`OP_CLOSE` compile closure
factories (`make_counter`), `OP_SELF` compiles `obj:method()` under a
rawget-non-nil guard, and `GETTABLE`/`SETTABLE` dispatch on the key type so
string-key hash tables use the hash lane. The `hasCalls && impure` blanket
rejection was replaced by a *deopt-at-call* rule: an impure proto compiles
only when a loop back-edge precedes its first general call, and every
`CALL`/`TAILCALL` then deopts before entering the callee, so the interpreter
re-executes the call with the compiled prefix committed and no write is
double-applied. Such structural deopts are expected every run, so they get a
much larger budget (`JIT_STRUCTURAL_DEOPT_BUDGET`) than genuine guard
failures: a trivial loop before the call is disarmed instead of paying an
exception forever. `OP_CLEANUP`, previously mistyped as a `T_OBJ` write
(falsely conflicting with numeric register reuse at loop merges), is now a
correct no-op. Forced-prewarm compilation of all 30 PUC suites stays green.
Measured JIT-on vs JIT-off: arith main loop **~5×**, table ops **~2.8×**,
hash table **~1.4×**, a two-million-call multret-tail shape **~4.7×**;
187 unit tests + 30/30 suites pass with JIT both on and off.

This pass also added the `math.sqrt` intrinsic: a float-returning method
such as `Vec:length()` calls a builtin, and builtin calls used to force the
whole proto out of the JIT subset, so it stayed interpreted (measured 2×
slower than its compiled equivalent). The intrinsic is guarded by an
identity check on `MathLib.SQRT`, so reassigning or shadowing still deopts
to the interpreter. Earlier passes fixed the `GETFIELD` guard
(metatable-free requirement made every OOP `self.x` deopt), the missing
float lane, and `OP_TAILCALL` hotness counting. The float lane also stored
its result with a truncating `D2L` instead of raw IEEE-754 bits (a
correctness bug pinned by a regression test).

The 2026-09-16 optimization pass closed most of the interpreter-era gaps
without weakening semantics: lazy short-string interning (canonical keys
stay reference-identical, so table lookups stop paying `String.equals`),
frameless `tostring`/`gmatch`/`math.sqrt`/`setmetatable` inlines, a
trivial-closure-factory inline for `return function() ... end` shapes, a
direct-scan `gmatch` lane for simple patterns, per-site caches for
`OP_GETTABUP`/`OP_SELF`/`OP_GETFIELD`, lazy closure parameter lists, and
state-scoped automatic collection. The remaining deficits are stdlib
engine throughput, not correctness.

A differential-fuzzing pass against stock PUC Lua 5.4.8 also fixed a
batch of conformance bugs: strings now carry the standard arithmetic
metamethods (`"10" + 1` routes through the overridable string metatable),
bitwise/arith errors blame the correct operand and use PUC descriptors,
`pairs`/`ipairs` no longer over-validate, `next`/`select`/`rawlen` and
`string.pack`/`unpack` report PUC argument errors, C functions render as
`function: 0x…`, `collectgarbage` returns the PUC integer result and is
non-reentrant inside a finalizer, and two engine bugs were fixed: the
compiler under-reported `maxStackSize` (letting JIT code index past the
shared stack array on deep recursion, an intermittent
`Index N out of bounds`), and `Varargs` could be initialized at the
recursion limit and permanently poisoned by a `StackOverflowError`.

A second differential-fuzzing pass against stock PUC Lua 5.4.9 fixed a
further batch of conformance bugs:

- **Table-constructor register layout**: list fields were emitted with
  `allocReg()`, but compiling an element that allocates its own temporaries
  (e.g. a global read `math.maxinteger` allocates a register for the table
  before the `GETFIELD`) scattered the values across non-consecutive
  registers, while `SETLIST` reads `R[a+1 .. a+n]`. So
  `{math.maxinteger, math.maxinteger, math.maxinteger}` stored the `math`
  table at index 2. Elements now land in explicit consecutive slots.
- **Zero-value returns**: `print`, `table.sort`, `table.insert`,
  `debug.sethook`, `debug.upvaluejoin`, `debug.getupvalue` (out of range)
  returned one `nil`; PUC returns zero values, observable via
  `select('#', ...)` and multi-value contexts.
- **C tail calls**: PUC does *not* reuse the caller's frame for a tail call
  to a C function (`luaD_pretailcall` only reuses it for Lua callees), so
  `debug.getlocal`/`getinfo`/return hooks must still see the Lua caller.
  Luava replaced the frame, corrupting frame levels and return-hook counts.
- **`debug.sethook` validation**: mask is checked first (a number coerces,
  missing/nil is an error), then the hook must be a function, then the
  optional count; a function with an empty mask turns hooks off, so
  `debug.gethook()` reports `nil` afterwards.
- **`debug.getlocal`**: out-of-range indices returned extra values instead
  of nothing, and tail-called Lua functions reported the wrong frame.
- **Error messages**: `debug.upvaluejoin` now uses the PUC
  `bad argument #N ... (invalid upvalue index)` form.

A third differential pass (operator matrices, integer boundaries, a 57,600-case
`string.format` matrix, pattern/metatable fuzzing, numeric-string coercion)
fixed:

- **Numeric-string coercion** (`lua_tointegerx`): `string.char/rep/sub/byte/
  find/match/gsub/unpack`, `table.insert/remove/concat/unpack/move`,
  `utf8.len/codepoint/offset`, `os.date/difftime`, `select`,
  `debug.getinfo` and `string.pack` now accept numeric strings (`"120"`,
  `"0x10"`, `" 9 "`) exactly like PUC. The VM's arithmetic and bitwise
  operators deliberately keep the stricter `luaV_tointeger` that does *not*
  coerce strings.
- **`math.abs("120")` is a float** (PUC tests `lua_isinteger` on the raw
  argument, so a string takes the float path).
- **`string.format`**: `%#o` on zero prints `"0"`; and the numeric argument is
  converted *before* the format is validated for `d/i/u/o/x/X/f/e/g/a` (but
  after, for `c/s/p/q`), matching PUC's check order.
- **C99 `pow`**: `pow(1, y) == 1` for any `y` including NaN/±inf, and
  `pow(-1, ±inf) == 1`; Java's `Math.pow` returns NaN for these.
- **Concat error blame**: C `luaG_concaterror` blames the *second* operand
  when the first is concatenable, unlike arithmetic's `luaG_opinterror`.
- **`string.pack`/`unpack` argument errors**: correct argument index, and a
  missing packed value reads as `nil` (PUC pushes a nil marker), not
  "no value".
- **`io`**: `io.read` blames the format argument at the right index
  (`#1` for `io.read`, `#2` for `file:read`); `io.input/output/lines` include
  the OS reason in "cannot open file" errors.
- **`utf8`/`os.date`**: numeric arguments are coerced to strings like PUC's
  `luaL_checklstring`.
- **`package`**: `package.searchpath` builds its "no file" error exactly like
  PUC's `pusherrornotfound` (no leading separator, every path segment listed),
  and `require` prefixes each searcher's message with `\n\t` the way PUC's
  `findloader` does.

Only the function-name qualification inside error text (`bad argument #N to
'floor'` vs `'math.floor'`) is still engine-context-dependent; PUC resolves it
from the live call frame, which the Java implementation does not track.

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

Internal contributor rules live in `AGENTS.md`; the register-VM design and
JIT expansion roadmap lives in `plan.md`. Lua 5.4 reference manual is under
`docs/`.

## License

This project is licensed under the **Mozilla Public License Version 2.0 (MPL 2.0)**.
See the [LICENSE](LICENSE) file for the full license text.

- **For Users & Embedders**: You can freely embed Luava as a dependency, call its APIs, integrate it into proprietary software, Minecraft servers, game engines, or SaaS products without being forced to open-source your proprietary code.
- **For VM/Engine Modifications**: Any direct modifications to Luava's core files (such as `BytecodeVM.java`, `BytecodeCompiler.java`, etc.) must remain open source under MPL 2.0.
