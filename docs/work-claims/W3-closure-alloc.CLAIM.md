# CLAIM W3 — Giảm alloc closure factory (1 ngày)

```
Owner: _unclaimed_
Status: open   # open | claimed | done | blocked
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
