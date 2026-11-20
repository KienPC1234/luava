# ROADMAP — Luava expansion plan

Status: 2026-11-20, 0.2.6 ("Meridian").
Conformance 31/31 PUC Lua 5.4.9 suites, 388 unit/security/conformance tests, JIT on/off/sync green.

This document covers **what to build next and in what order**. Measurement
logs and per-item evidence stay in `plan.md` (maintainer notes, not shipped).

---

## 0. Where the time actually goes

Profiled on the current jar (JFR `ExecutionSample` + `ObjectAllocationSample`):

| Workload | Hot frames | Allocation |
|---|---|---|
| `04_string_concat` | `tostringInline`, `LuaTable.get/rawget`, `TableLib.concat` | `LuaString`, `String`, `byte[]` |
| `09_string_pattern` | `GmatchIterator.nextSimple`, `match_class`, `JitRuntime.tforCall` | `GmatchIterator` (1 per `gmatch` call), `LuaString`, `String`, `byte[]` |

Kernel-side work was attempted and reverted: a 306 B kernel (fib −14.8 %) was
achieved by seeding parameters as `T_INT` for int-only lanes, but a function
called with a float then deopted on every call and was disarmed. The lesson is
in `plan.md` §5.C: only *provenance* (values produced by int literals) may
justify an int-only lane, never a parameter guess. What is left is not dispatch
width but the **string representation** (every Lua string is a
`java.lang.String`, so byte-oriented work pays a decode/encode and a second
object per value) and **call-site polymorphism**.

---

## 1. Measurement discipline (mandatory for every item)

Lessons from 2026-11-18/19, now rules:

1. **≥11 paired runs**, pinned, `-Dluava.jit.sync=true`; use 15 when the delta
   is under 5 %.
2. **7 pairs is not enough.** `07_hash_table` produced 0.925, 1.006 and 1.056
   in three runs of the same pair of jars; the same jar alone varied 92–173 ms.
3. A delta under 3 % is **not** accepted without a second independent round.
4. Confirm the mechanism (`PrintInlining`, `PrintCompilation`, JFR), not only
   the number.
5. No measurable gain after two honest attempts → **revert** and record why
   (`AGENTS.md` §IV.4).
6. Gates: 31/31 PUC + unit tests JIT on/off, fuzz 2500 cases across both JIT
   modes and PUC-vs-Luava.

---

## 2. Track A — String representation (highest remaining leverage)

**Why:** `04` and `09` are the weakest tasks. Every match/`tostring`/concat
element creates a `String` and a `LuaString`; `gmatch` also copies source bytes
per call. Track A removes one object per string operation and enables slice
returns without copies.

| Item | Work | Gate | Status |
|---|---|---|---|
| A1 | `LuaString` stores `byte[]` + cached hash; lazy `String` view at host/`io` boundary. Keep `%p` canonicalization and `string.dump` round-trip. | 04 >= 5 % | DONE |
| A2 | Pattern engine returns slices over the source buffer (zero `String` per capture/match). | 09 >= 10 % | DONE |
| A3 | Zero-copy byte slice views on `LuaString` (`bytes`, `offset`, `length`) for patterns/`string.byte`. | 09 >= 3 % | DONE |
| A4 | `table.concat` byte-output lane (`LuaTable.concatPlainBytes`). | 04 >= 3 % | DONE |

A1 is an internal representation change: every `instanceof LuaString` fast path
must move to `byte[]`, and UTF-8 host bridging must stay byte-exact. Do A1 and
A2 together or not at all; A3 can ship alone if A1 slips.

---

## 3. Track B — JIT depth

| Item | Work | Gate | Risk |
|---|---|---|---|
| B0 | Provenance-aware int lanes: only values produced by int literals get an int-only path; parameters stay numeric-generic, or the call site selects an int/generic kernel version by argument tags. This is the fix that would make the reverted 306 B kernel safe. | fib ≥ 10 % without any deopt storm on float-called functions | medium |
| B1 | Polymorphic inline cache for `JitRuntime.selfMethod` and closure calls: monomorphic → 2–4 shape guard chain → megamorphic deopt. | 08 ≥ 3 % | medium |
| B2 | Per-call-site type feedback (observed int/float/object mask) feeding the existing `analyze()` type lattice. | one loop task ≥ 5 % | medium |
| B3 | Vararg in kernels (53 rejected protos): extend the `exec` ABI or read the caller window without copying. | coverage +3 % | high |
| B4 | Allow `OP_TBC` in the JIT subset (45 protos), reusing `closeOnJitReturn`. | coverage only | medium |
| B5 | Impure-callee call protocol (`05_closures` +1.2 % in W3): only with a proven workload. | 05 ≥ 3 % | high |

**Not planned: tracing JIT.** Lua's dynamic shapes make trace recording and
side-exit maintenance expensive, while C2 already covers register allocation,
inlining and loop opts. Effort is better spent on Track A.

---

## 4. Track C — Embedding and product surface

| Item | Work | Gate | Risk |
|---|---|---|---|
| C1 | Debugger API: `breakpoint(line)`, `step`, `inspect(frame)`, plus `--inspect` CLI. DAP later, only if an IDE integration is actually requested. | script stops at line, locals readable | medium |
| C2 | GraalVM `native-image`: `META-INF/native-image/*-config.json` for reflection/MethodHandle/proxy paths, CI job, documented limits. | `native-image` build runs the CLI and PUC smoke | medium |
| C3 | AOT start-up cache: persist generated JIT class bytes keyed by proto fingerprint and load on startup. | first-request latency −50 % | medium |
| C4 | `LuaStatePool` with bounded states on top of `close()` + `CompiledChunk` (`AutoCloseable`), plus `maxMemoryBytes`/`maxCoroutines`/`maxCallDepth`/`cancel`. | 8 tenants, no leak | DONE |
| C5 | Async bridge beyond `evalAsync`: `registerAsyncFunction` + cancellation token, `CompletableFuture` interop. | server example passes | medium |
| C6 | State snapshot/restore for globals (JSON/CBOR, cycles handled), opt-in. | round-trip equality | medium |

---

## 5. Track D — Tooling and release train

| Item | Work |
|---|---|
| D1 | CI matrix JDK 21/23; PUC suite in both JIT modes; `spotless:check`. |
| D2 | Perf regression gate: 11-pair harness on `01/02/03`, fail a PR on > 5 % regression. |
| D3 | Refresh README numbers after each release (current table predates the 306 B kernel). |

Release train:

* **0.2.6 ("Meridian")** — shipped: byte-slice strings, pattern slices, unboxed
  `LuaTable` array lanes, guarded JIT kernels, stackless coroutines,
  `CompiledChunk`/`LuaStatePool`, security/crash fixes, and resource controls.
* **0.3.0** — C1 + C2 + C3 + C4 (debugger, native-image, AOT cache, pooling).
* **0.4.0** — A1 + A2 + A3 (byte-backed strings, pattern fast path).
* **0.5.0** — B1 + B2 if a workload proves them; B3/B4/B5 only by demand.

---

## 6. Ordering by leverage, not by novelty

1. **A1+A2** — the only remaining change that moves two benchmark tasks at once.
2. **C1/C2/C3** — unblocks real embedders; no performance risk.
3. **B1** — cheap once Track A lands; verify with `08`.
4. **B2, B3, B4, B5** — speculative; require a workload before starting.

Every item ships with: a gate number, a test that fails on revert, and a
documented rollback (flag or revert). No item may weaken a PUC suite to look
faster.
