# CLAIM W4 — Embedding/Ops: stats + CLI + lifecycle (1 ngày)

```
Owner: _unclaimed_
Status: open   # open | claimed | done | blocked
Branch: w4/embedding-ops
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
- `state.stats()` sample output: __ | CLI flags verified: __
