# CLAIM W3 — Giảm alloc closure factory (1 ngày)

```
Owner: W3
Status: claimed   # open | claimed | done | blocked
Branch: w3/closure-alloc
```

## Mục tiêu 1 ngày
`05_closures` là task yếu nhất vs LuaJ (1.21x). Profile: `factoryBuild +
LuaClosure + Upvalue` (560+541 sample) là nguồn rác nóng nhất.
`BytecodeVM.executeClosure ~L2282 + factory L2373`: `new Upvalue[] +
findOrCreateOpenUpvalue + new LuaClosure` mỗi `OP_CLOSURE`.

## Làm
1. Share/read-only upvalue khi capture không bị ghi (check tại compile).
2. Pool upvalue đóng-int (kích thước cố định, thread-local).
3. Mở rộng `JitRuntime.buildClosure` trivial-factory thêm 1-2 shape phổ biến.
   Không đổi semantics upvalue (`05_closures` + suite closure/upvalue khóa).

## Vùng sở hữu (Allowed — HẸP, chú ý)
- `BytecodeVM.java`: CHỈ vùng `executeClosure`/factory (~L2282-2400)
- `src/main/java/org/luava/runtime/eval/Upvalue.java` (toàn file OK)
- `JitRuntime.java`: CHỈ hàm `buildClosure` (~L312)

## Vùng cấm (Forbidden)
- Mọi phần khác của `BytecodeVM.java` (runLoop, CALL, FORPREP…),
  `LuaToJvmTranslator.java`, `LuaTable.java`, `LuaState.java`, `Main.java`

## Cổng pass
- Suite closure/upvalue + 31/31 PUC xanh; fuzz 2500 0 mismatch.
- Interleave `05_closures` cải thiện, không regression task khác ngoài bar 3%.

## Kết quả đo (worker điền khi xong)
- 05_closures: __ → __ | alloc/closure (JFR): __ → __

## Checkpoint (model đổi — W3 tiếp tục trên worktree /tmp/opencode/w3, nhánh w3/closure-alloc)
- Đã khảo sát: `05_closures` = factory 1-int-capture (`make_counter`), đã frameless
  qua `factoryBuild`; per-creation = 1 `Upvalue[]` + 1 `Upvalue` + 1 `LuaClosure`
  (JFR xác nhận Upvalue 605 + LuaClosure 514 sample). Probe public-API cho thấy
  analyzer cũ loại `LOADI/LOADF/LOADTRUE/FALSE` (`local k = 42`), `LOADKX`,
  `GETUPVAL`-forward — đều đã reproduce.
- Đã sửa (commit 0252aba trên nhánh w3/closure-alloc, worktree /tmp/opencode/w3):
  `Upvalue.java` (+`EMPTY`, `closedInt/closedFloatBits/closedNil/closedValue`),
  `BytecodeVM.java` CHỈ vùng closure/factory (`executeClosure` fast-path 0/1
  upvalue; `analyzeFactory` thêm LOADI/LOADF/TRUE/FALSE/LOADKX/kind-4 GETUPVAL
  snapshot + guard; `factoryBuild` dùng triple-stack thô, hết boxing trung gian),
  `JitRuntime.buildClosure` (fast-path 0/1 upvalue, `EMPTY`).
  KHÔNG share cell giữa các closure (alias `debug.setupvalue`/`upvalueid` —
  đã verify isolation); KHÔNG pool object sống (lifetime gắn closure, reuse là
  unsound). File cấm/chung (LuaState/LuaTable/...) không đụng.
- Trạng thái: `mvn -q compile` + `spotless:check` xanh (trong worktree).
  `OfficialSuiteEvaluationTest` 31/31 xanh cả JIT-on và JIT-off (`-Dluava.jit=false`).
  Fuzz closure 2500 case tự viết: JIT-on/off 0 mismatch; PUC-vs-Luava 0 mismatch
  ngữ nghĩa (sau khi trừ prefix tên chunk + `ERR:` của driver).
  Interleave 05_closures mới ~1% (0.990, 7 cặp) — CHƯA đạt bar 3%, đang đào tiếp.
- Kế tiếp: profile execution-sample xem factoryBuild còn bao nhiêu %,
  cắt tiếp chi phí/creation trong vùng factory (không đụng runLoop/CALL);
  rồi interleave đủ 10 task + đo JFR alloc/closure, điền Kết quả đo, báo done.
