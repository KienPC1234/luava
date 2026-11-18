# CLAIM W1 — Gọn kernel JIT để C2 inline (1 ngày)

```
Owner: _unclaimed_
Status: open   # open | claimed | done | blocked
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
