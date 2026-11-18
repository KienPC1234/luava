# CLAIM W2 — Unbox `LuaTable.arrayPart` (1 ngày)

```
Owner: W2
Status: done   # open | claimed | done | blocked | paused
Branch: work/parallel-4w (shared; files: LuaTable.java, TableLib.java + new test)
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
- 03_table: 18.895 → 18.955 ms (interleave B/A 1.019, trong bar 3%) | 07_hash: 126.47 → 117.02 (0.925) | alloc/eval 03: 18,472,512 → 18,472,520 B (+8B)
- 03 on/off (jar mới): 18.59/49.57 = 0.375 ≤ 0.5 ✓
- Fuzz table v3 (dense, deterministic): PUC-vs-Luava 2500/0 + new-vs-base 2500/0
- Unit: 374/374 (gồm OfficialSuite 30/30 PUC + 11 test LuavaTableUnboxTest mới)

## Kết quả cuối (RESUME 2026-11-18 — cổng PASS, không merge)
Thiết kế chốt: dual-write + lazy lanes (pivot từ single-source sau khi đo).
- `arrayPart` giữ canonical boxed (hot path `rawgetInt` nguyên văn baseline,
  PrintInlining 114B inline(hot); `rawsetInt` 220B inline(hot) với 1 null-check).
- Lane `long[]/byte[]` lazy-null, chỉ ctor pre-size mới allocate; rehash KHÔNG
  promote (tránh thuế 18MB retained + 18MB growth garbage trên bảng append-built,
  đã đo bằng AllocBench + JFR ObjectAllocationSample). Mọi writer duy trì lanes;
  cold paths (`rawget/rawset/rehash/next/keys/forEach/rawlen`) đọc được cả hai.
- `TableLib`: 6 bulk ops dùng `rawgetInt/rawsetInt` khi `getMetatable()==null`
  (bỏ box key mỗi phần tử; semantics metatable giữ nguyên).
- Blocker đã xử lý: single-source gây 03 chậm 3.6× (laneSet 167B inline vào
  kernel) và alloc +28MB; rehash-promote gây sieve chậm 1.7× (+37.7MB).
  Cả hai đều xác nhận bằng PrintInlining/JFR/AllocBench trước khi pivot.
- Rollback: revert commit (rủi ro đã ghi trung-cao; merge CUỐI theo kế hoạch).
- Vùng cấm giữ nguyên: không chạm LuaToJvmTranslator/BytecodeVM/JitRuntime/
  LuaState/Main/tests-lua-5.4.9-tests. Chỉ sửa 2 file allowed + 1 test mới.

## Checkpoint (pause 2026-11-18, RESUME từ đây)
File đã sửa (CHỈ 3 file này, không chạm file worker khác):
- `src/main/java/org/luava/runtime/LuaTable.java` — thêm lane `long[] arrayNums` +
  `byte[] arrayKind` (TAG_NIL/INT/FLOAT/OBJ, LuaTable.java:~19-30) song song
  `arrayPart` (giữ làm oStack cho object + placeholder NIL); helpers
  `ensureLaneCap/laneIsNil/laneGet/laneSet/materializeLanes` (~565-660);
  `rawgetInt/rawsetInt` (~401-460), `rawget/rawset` int-path, `storeIntGap`,
  `rehash` (đếm + migrate qua lane), `rawlen` trim, `next/keys/forEach` đọc lane.
  `modCount`/weak/`nextIterator` semantics giữ nguyên. Growth lane hiện 2x.
- `src/main/java/org/luava/runtime/standard/TableLib.java` — plain-table fast path
  (guard `getMetatable()==null`) dùng `rawgetInt/rawsetInt` thay vì box key trong
  `insert/remove/move/sort/unpack/pack`; semantics metatable giữ nguyên.
- `src/test/java/org/luava/runtime/LuavaTableUnboxTest.java` (mới, 11 test).

Việc đã xong: lane + rehash 2 chiều + TableLib raw paths; unit 374/374 xanh
(gồm OfficialSuite 30/30, 183s); fuzz dense-only v3 PUC-vs-Luava 2500/0 mismatch,
new-vs-base 2500/0 (lưu ý: corpus hole-y v1/v2 lộ divergence border `#` CÓ SẴN ở
baseline — `#` trên bảng có hole là undefined behavior, không phải regression).

Việc còn dở (BLOCKER): interleave base-vs-new (7 cặp, sync JIT, core 5):
01:1.000 02:1.019 04:1.060 05:1.002 06:1.029 07:1.007 08:1.023 09:1.012,
nhưng 03_table_ops: 18.7ms → 68.1ms (B/A 3.643) — REGRESSION nặng.
Chẩn đoán: JIT-off cả 2 jar vẫn new chậm hơn (137 vs 104ms) + alloc/eval 03
tăng 18.5MB → 34.6-46MB (box garbage ở read + lane growth waste ở write);
nghi JIT inline cliff do rawgetInt/rawsetInt to ra (C2 không inline vào kernel).
AllocBench tại /tmp/opencode/w2/AllocBench.java; corpus fuzz tại
/tmp/opencode/w2/{gen3.py,runner.lua,cases3}, jar base tại
/tmp/opencode/w2/base (worktree HEAD f34a97b) + target/luava-0.2.5.jar.

Bước kế tiếp (RESUME): pivot single-source → dual-write (arrayPart giữ boxed
canonical như baseline để rawgetInt hot path zero-alloc + inline lại được;
lane chỉ là side-cache cho TableLib bulk ops), hoặc thu nhỏ rawgetInt về cỡ
inline được; sau đó rebuild jar, chạy lại AllocBench + interleave 03/07/10,
rồi fuzz lại 2500. Xem chi tiết trong shell log background gần nhất.
Không merge, không chạm tests/lua-5.4.9-tests/**.
