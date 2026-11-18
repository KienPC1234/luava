# CLAIM W2 — Unbox `LuaTable.arrayPart` (1 ngày)

```
Owner: _unclaimed_
Status: open   # open | claimed | done | blocked
Branch: w2/unbox-table
```

## Mục tiêu 1 ngày
`arrayPart` đang `ArrayList<LuaValue>` boxed → profile `03_table` top frame là
`ArrayList.add/elementData + LuaInteger.valueOf`. Thêm lane `long[]/byte[]`
song song triple-stack; `rawgetInt/rawsetInt` đọc lane thô.

## Làm (đi từng bước nhỏ, fuzz sau mỗi bước)
1. Thêm lane `long[] + byte[]` cho mảng số, giữ `oStack` cho object.
2. `rawgetInt` (LuaTable.java:378) / `rawsetInt` (:399) đọc/ghi lane thô;
   miss mới boxing + `hashPart.get`.
3. Rehash 2 chiều (`rehash:625`, `maybeRehashForInt:592`); giữ `modCount`
   semantics cho site-cache. Không đổi hành vi `#`/sparse.

## Vùng sở hữu (Allowed)
- `src/main/java/org/luava/runtime/LuaTable.java`
- `src/main/java/org/luava/runtime/standard/TableLib.java`
- test table mình thêm

## Vùng cấm (Forbidden)
- `LuaToJvmTranslator.java`, `BytecodeVM.java`, `JitRuntime.java`,
  `LuaState.java`, `Main.java`

## Cổng pass
- 31/31 PUC + unit xanh; fuzz PUC-vs-Luava 2500 0 mismatch (table là semantics nhạy).
- Interleave `03_table ≤ 0.5`, `07/10` không regression ngoài bar 3%.
- Rủi ro trung-cao → merge CUỐI, rollback bằng revert commit.

## Kết quả đo (worker điền khi xong)
- 03_table: __ → __ | 07_hash: __ → __ | alloc/call (JFR): __ → __
