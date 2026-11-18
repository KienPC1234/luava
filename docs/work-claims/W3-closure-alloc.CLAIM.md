# CLAIM W3 — Giảm alloc closure factory (1 ngày)

```
Owner: W3
Status: blocked   # open | claimed | done | blocked — xem ## Kết quả đo: 05 chỉ +1.2% < bar 3% sau 2 vòng, phần còn lại chứng minh nằm ngoài scope
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
- 05_closures (pinned core 5, 11 cặp, interleave B/A new/base):
  JIT-on: 27.275 → 26.910 ms = **0.987 (+1.3%)**;
  JIT-off (`-Dluava.jit=false`): 80.750 → 79.805 ms = **0.988 (+1.2%)**.
  Dưới bar 3% → blocked (không bịa số; lần đo sớm 0.926 là nhiễu khi chạy
  chung worker khác, đã loại bằng pin core + 11 cặp).
- Commit: `0252aba` trên nhánh `w3/closure-alloc` (worktree /tmp/opencode/w3).
  Diff vs `f34a97b`: 3 file vùng hẹp, +236/−42
  (`BytecodeVM.java` executeClosure/factory, `Upvalue.java`, `JitRuntime.buildClosure`).
- Cổng đúng đắn (trên đúng commit 0252aba): 31/31 PUC xanh cả JIT-on và
  JIT-off; 363 unit test xanh; fuzz closure tự viết 2500 case: JIT-on/off
  0 mismatch, PUC-vs-Luava 0 mismatch ngữ nghĩa. Không regression task khác
  ngoài bar (full 10 task JIT-on: 0.972–1.000; JIT-off full-table lần đầu có
  1.009–1.022 ở 4 task nhưng các task đó không chạy code đã sửa → nhiễu tải
  chung, đã ghi nhận).
- Phân rã chi phí JFR execution-sample trên 05_closures (new jar, 149 events):
  factory path (callFactory+factoryBuild, in-scope) ~35% stacks; generic
  child-call (`executeCallOp`/`invoke`/`tryJitCall`, vùng CALL cấm) ~58–72%;
  `LuaClosure.<init>`+`onAlloc` ~11% (file cấm). Trong factory, dòng nóng nhất
  là `Upvalue.closedInt` (36/149 events) = alloc cell tươi — bắt buộc semantics
  (identity cho `upvalueid` + isolation `setupvalue`, đã chứng minh bằng probe).
- Đã loại có lý do, không chạm: (1) share cell giữa closure — alias
  setupvalue/upvalueid; (2) pool Upvalue/Upvalue[] sống — lifetime gắn closure,
  reuse là unsound; (3) subclass Upvalue gọn — chỉ tiết kiệm zeroing ~1% tổng
  mà rủi ro megamorphic + join/close/debug; (4) `onAlloc` AtomicLong,
  `tryJitCall`, generic CALL — ngoài scope hẹp (file/vùng cấm).
- Hướng cho worker sau (ngoài scope W3): muốn 05 vượt 3% phải đụng vùng cấm —
  inline/lane cho child-call `c()` (CALL), hoặc `onAlloc` (GCManager).

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
