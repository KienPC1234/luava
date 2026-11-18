# CLAIM W1 — Gọn kernel JIT để C2 inline (1 ngày)

```
Owner: W1
Status: paused   # open | claimed | done | blocked | paused (host đổi model, sẽ resume)
Branch: w1/kernel-size
```

## Mục tiêu 1 ngày
`execInner` fib hiện ~1087B > `FreqInlineSize` 325 → C2 báo `hot method too big`.
Ép `-XX:FreqInlineSize=2000` đã chứng minh nhanh 1.5x (227→150ms). Việc là
giảm kích thước kernel THẬT, không bắt user chỉnh cờ JVM.

## Làm (theo thứ tự, dừng khi hết ngày)
1. Hoist `base` vào local JVM (bỏ `ILOAD 5; IADD` mỗi access).
2. Lane int-typed: khi `analyze()` chứng minh int, bỏ float-branch.
3. Tách prologue/guard ra method nhỏ (giữ `runLoop` < 8KB).

## Vùng sở hữu (Allowed)
- `src/main/java/org/luava/runtime/jit/LuaToJvmTranslator.java`
- `src/test/java/org/luava/**/*Jit*.java` (chỉ test mình thêm)

## Vùng cấm (Forbidden — chạm là revert)
- `runtime/LuaTable.java`, `runtime/bytecode/BytecodeVM.java`,
  `runtime/jit/JitRuntime.java`, `runtime/LuaState.java`, `cli/Main.java`

## Cổng pass
- 31/31 PUC + unit xanh cả JIT on/off; fuzz JIT-on/off 2500 case 0 mismatch.
- Interleave ≥7 cặp task `02_fibonacci`: cải thiện ≥3%, `PrintInlining` hết `too big`.
- Rollback: cờ allow-list / revert commit (kernel là pure codegen).

## Kết quả đo (worker điền khi xong)
- Before/after fib ms: __ / __  | Kernel bytes: __ → __ | `PrintInlining`: __

## Checkpoint (2026-11-18, tạm dừng do host đổi model — sẽ resume)
- **Worktree làm việc:** `/tmp/opencode/w1` (worktree detached tại `f34a97b`, KHÔNG
  commit/merge sang nhánh nào). Commit checkpoint: `c34d4af`
  "W1: base hoist + int lanes (execInner 1146->737)".
- **File đã sửa (duy nhất, trong worktree):**
  `src/main/java/org/luava/runtime/jit/LuaToJvmTranslator.java`
  (diff vs `f34a97b`: 278+/207-, 1 file). Test mới CHƯA thêm.
  Harness tạm (ngoài repo, đúng AGENTS.md): `/tmp/opencode/Measure.java`,
  `/tmp/opencode/Disasm.java`, `/tmp/opencode/smoke.lua`, `/tmp/opencode/fib30.lua`.
- **Đã xong:**
  1. Hoist `base`: slot JVM `9+reg` giữ `base+reg` (fill 1 lần/invocation ở
     `emitPrologue` + `execInner` init); fused-callee dời lên `FUSED_BASE=74`
     (`LuaToJvmTranslator.java:47-55, 232-242, 389-400, ~4100 emitIndex`).
     Mọi `ILOAD 5;ldc;IADD` đơn-reg đã thay bằng 1 `ILOAD` (giữ lại window-end
     vượt `regs` và composite `base+a+1+maxSS` ở dạng số học cũ — xem ghi chú
     an toàn trong code). Đo: execInner fib 1146→1036B (-9.6%).
  2. Lane int-typed qua `isInt(types,pc,r)` (`:1926-1936`): `emitArithNum/KNum`,
     `emitIdivNum/ModNum`, `emitCmpImm/RR`, `emitEqK` nhận thêm `intOnly`, bỏ
     float-branch + dùng `guardInt` khi `analyze()` chứng minh T_INT; dispatch
     ở `emitBody` (`:650-660, :855-890`). Đo: execInner 1036→737B (-29%;
     tổng -36% vs baseline).
- **Còn dở / anomaly đang điều tra:** fib(35) CHẬM hơn baseline ~6-9%
  (w0 HEAD ~235ms vs w1 ~257ms, 4 cặp đo; fib30 ~22.0 vs ~23.4ms) dù hot path
  gọn hơn — nghi fill-loop `execInner` (5 iter/invocation) hoặc nhiễu C2.
  Smoke đúng đắn OK (JIT on/off identical trên arith int+float + fib20).
  Chưa làm: bước 3 (outline deopt/guard/prologue + CALL slow-path ra helper
  trong generated class), test `Jit*` mới, mọi cổng (PUC/unit/fuzz/interleave).
- **Bước kế tiếp chính xác (resume tại đây):**
  1. Cô lập regression: build biến thể hoist-only (revert tạm int-lane) trong
     worktree, chạy 6 cặp xen kẽ A/B (`Bench benchmarks/lua/02_fibonacci.lua
     3 3` với `-Dluava.jit.sync=true`) để tách fill-cost vs int-lane.
  2. Nếu fill là thủ phạm: chỉ hoist regs thực dùng (scan operand) hoặc bỏ fill
     ở `execInner`; nếu là nhiễu: tiếp tục bước 3.
  3. Bước 3: emit helper `deopt/guardInt/guardNumber/fusedCheck` + outline CALL
     slow-path trong generated class (cần truyền `owner` xuống các emitter —
     cascade signature, làm bằng sed + `mvn compile` kiểm tra).
  4. Thêm `src/test/java/org/luava/bytecode/JitKernelSizeTest.java` (assert
     execInner < ngưỡng + equivalence int/float), rồi chạy cổng đầy đủ.
- **Trạng thái build/test gần nhất:**
  - Worktree `/tmp/opencode/w1`: `mvn -q compile -DskipTests` XANH (0 error),
    `git status` sạch (mọi thứ đã commit ở `c34d4af`).
  - `/data/luava` (cây chung): BIÊN DỊCH HỎ — do W4 đang sửa dở
    `src/main/java/org/luava/runtime/LuaState.java` (gọi `ensureOpen()/close()/
    stats()` chưa tồn tại). File cấm với W1 nên KHÔNG chạm, KHÔNG stash đồ của
    worker khác; translator + test của W1 tại đây còn nguyên pristine.
  - Không merge, không tạo nhánh, không xóa diff ai.
