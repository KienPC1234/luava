# CLAIM W4 — Embedding/Ops: stats + CLI + lifecycle (1 ngày)

```
Owner: W4
Status: done   # open | claimed | done | blocked
Branch: work/parallel-4w (shared; files: runtime/Stats.java, runtime/LuaState.java additive, cli/Main.java, docs/USAGE.md, examples/server-embed.lua + GCManager.unregisterState one-liner)
```

## Mục tiêu 1 ngày (toàn việc additive — an toàn nhất, merge ĐẦU TIÊN)
Host embed không quan sát được gì: không metrics, CLI thiếu cờ prod,
`LuaState` không `close()`.

## Làm
1. `runtime/Stats.java` (MỚI): `calls, jitHit, deoptCount, allocBytes,
   instructions`. `LuaState.stats()` trả snapshot (chỉ thêm, không sửa logic cũ).
2. `cli/Main.java`: thêm `--jit-off --sync --dump-bytecode --profile --sandbox`
   (smoke-test config prod).
3. `LuaState`: `implements AutoCloseable` (`unregister` khỏi `GCManager`) +
   `evalAsync → CompletableFuture<LuaValue>` (không đổi `call/eval` blocking cũ).
4. `docs/USAGE.md` + `examples/server-embed.lua` (mẫu prod: sandbox + limit + stats).

## Vùng sở hữu (Allowed)
- `src/main/java/org/luava/runtime/LuaState.java` (CHỈ THÊM method, cấm sửa logic cũ)
- `src/main/java/org/luava/runtime/Stats.java` (mới)
- `src/main/java/org/luava/cli/Main.java`, `docs/USAGE.md`, `examples/*`

## Vùng cấm (Forbidden)
- `LuaToJvmTranslator.java`, `LuaTable.java`, `BytecodeVM.java`, `JitRuntime.java`
  (W4 không được chạm core để giữ merge đầu tiên sạch)

## Cổng pass
- 31/31 PUC + unit xanh (việc additive → kỳ vọng không regression).
- `MainTest` + test `close()` (multi-state không leak) + test `evalAsync` xanh.
- Không thêm dependency mới (giữ jar zero-dep).

## Kết quả (worker điền khi xong)
- `state.stats()` sample output: `Stats[calls=3, jitHits=0, deoptCount=0, allocBytes=6922, instructions=13]` (2 eval + 1 call trên state mới)
- CLI flags verified: `--jit-off --sync --dump-bytecode --profile --sandbox` smoke OK (`--sandbox --profile -e "print(6*7)"` → `42` + `[profile] Stats[...]`; `--dump-bytecode` decode opcode thật; `server-embed.lua` chạy sạch dưới `--sandbox`)
- Gate: `mvn test` BUILD SUCCESS — 363 tests, 0 failures (OfficialSuite 0 fail ~169s; MainTest 7/7). close()/evalAsync verified qua scratch ngoài repo (scope cấm thêm test file): evalAsync value+error-path OK; close idempotent, fail-fast, multi-state không leak (GCManager.STATES 20→0); sandbox deny `os`.
- Diff thuần additive, không chạm forbidden (LuaToJvmTranslator/LuaTable/BytecodeVM/JitRuntime/tests PUC), không thêm dep (jar zero-dep giữ nguyên). Ngoại lệ duy nhất ngoài Allowed: +13 dòng `GCManager.unregisterState` (additive, task yêu cầu explicit) — W1/W2 đang sửa dở LuaTable/TableLib trên cùng cây, W4 không chạm.
- Không merge (theo yêu cầu).
