# CLAIM W1 — Gọn kernel JIT để C2 inline (1 ngày)

```
Owner: W1
Status: done   # open | claimed | done | blocked | paused
Branch: work/parallel-4w (làm trong worktree /tmp/opencode/w1, detached f34a97b; không merge nhánh)
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
- Before/after fib ms: 235.5 / 200.6 (interleave 7 cặp, median) — **-14.8%**
- Kernel bytes: exec 1174 → 334, **execInner 1146 → 306** (-73%)
- `PrintInlining`: `execInner (306 bytes) inline (hot)` — **hết `hot method too big`**

## Kết quả cuối (2026-11-19, DONE — thay checkpoint paused trước đó)
- **File giao:** `src/main/java/org/luava/runtime/jit/LuaToJvmTranslator.java`
  (+1123/-476 vs `f34a97b`) + `src/test/java/org/luava/bytecode/JitKernelSizeTest.java`
  (mới, 3 test). Không chạm file cấm nào; không merge nhánh.
- **Việc đã làm (khác plan ở thứ tự, có số đo):**
  1. Hoist `base` → **REVERT** (đo 6 cặp: fill-loop/execInner-call +12% fib).
  2. Lane int-typed qua `isInt(types,pc,r)` (ADD/SUBK/LEI + dispatch): giữ.
  3. Tách cold path ra helper trong generated class: `callSlow` (CALL slow),
     `callSlowGeneral` (general leaf, C2 inline), `callDirect` (same-proto),
     `deopt/deoptS/guardInt/guardNumber/guardObject`, lane leaf
     (`subKImm/addIntRR/leiImm/retInt/storeRaw` — **chỉ dùng trong `execInner`**;
     exec/execOsr giữ inline vì C2 không inline helper 49B vào caller 1234B,
     đo +25% arith khi làm ngược lại).
  4. Sửa 3 VerifyError do chính W1 gây ra (NAP `callSlow` thiếu `pc`,
     `deopt`-void fallthrough sai stack, thiếu CHECKCAST): đều bắt bằng
     `LoadTest`/`VerifyAll` + gate `everyTranslatedKernelPassesTheJvmVerifier`.
- **Cổng pass (code cuối, worktree /tmp/opencode/w1):**
  - 31/31 PUC + 366 unit xanh **cả JIT on và JIT off** (0 fail/error).
  - Fuzz JIT-on/off 2500 case: **0 mismatch**.
  - Interleave 7 cặp: fib **0.852 (-15%)**; PrintInlining `inline (hot)`.
    Không task nào regression quá bar 3% (max +2.0% closures, +1.7% oop;
    closures chạy lại 6 cặp median ≈1.0 → nhiễu).
  - `mvn spotless:check` xanh.
- **Bài học:** chỉ outline cold path; hot path phải inline (C2 chỉ inline
  helper nhỏ vào caller nhỏ). Đo A/B ≥6 cặp trước mọi kết luận (nhiễu máy chung lớn).

## Checkpoint (2026-11-18, paused — da resume va DONE, xem ## Ket qua cuoi o tren)
Lich su commit trong worktree /tmp/opencode/w1 (detached, khong merge nhanh):
- c34d4af base hoist + int lanes (execInner 1146->737)
- 7c6a913 revert hoist (fill-loop +12% fib), giu int lanes (789B)
- bdc1f93 callSlow outlined (execInner 789->515); innerCall revert (hot, +10%)
- 5ba1e35 guard/deopt outlined (515->421); sua 3 VerifyError tu tao
- a69feb8 helpers public (verifier gate link qua foreign lookup)
- f9579d7 lane/ret/store helpers (338->308, inline hot, fib -15%)
- 1f83307 JitKernelSizeTest (3 test)
- 28a6e4f fused->helper, non-fused->baseline inline (oop +4% ve +0.5%)
- 81338e7 lane helpers chi trong execInner (arith +25% ve -2.5%)
