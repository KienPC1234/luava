# Phân việc Luava — 4 worker × 1 ngày (kiến trúc sư chia)

> Nguyên tắc: mỗi worker SỞ HỮU ĐỘC QUYỀN vùng file của mình trong ngày.
> Muốn sửa ngoài vùng → dừng, ghi vào claim file, chờ kiến trúc sư duyệt.
> Cổng chung mọi việc (theo `plan.md §1.4`): 31/31 suite PUC + unit test xanh
> cả JIT on/off, fuzz zero-mismatch, đo interleave ≥7 cặp, bar 3%.
> Không sửa `tests/lua-5.4.9-tests/**`. Không mock để qua test.

## Ma trận vùng (disjoint — không chồng lấn)

| Worker | Việc 1 ngày | File SỞ HỮU (được sửa) | File CẤM (không được chạm) | Claim file |
|---|---|---|---|---|
| W1 | C. Gọn kernel để C2 inline | `runtime/jit/LuaToJvmTranslator.java` + test `jit/*Test.java` của mình | `LuaTable.java`, `BytecodeVM.java`, `LuaState.java`, `Main.java` | `docs/work-claims/W1-kernel-size.CLAIM.md` |
| W2 | B. Unbox `LuaTable.arrayPart` | `runtime/LuaTable.java`, `runtime/standard/TableLib.java` + test table của mình | `LuaToJvmTranslator.java`, `BytecodeVM.java`, `LuaState.java` | `docs/work-claims/W2-unbox-table.CLAIM.md` |
| W3 | D. Giảm alloc closure factory | `runtime/bytecode/BytecodeVM.java` (chỉ vùng `executeClosure`/factory ~L2282-2400), `runtime/eval/Upvalue.java`, `runtime/jit/JitRuntime.java` (chỉ `buildClosure`) | `LuaToJvmTranslator.java`, `LuaTable.java`, `LuaState.java`, `Main.java` | `docs/work-claims/W3-closure-alloc.CLAIM.md` |
| W4 | Ops: `stats()` + CLI + lifecycle | `runtime/LuaState.java` (CHỈ thêm method mới, không sửa logic cũ), `runtime/Stats.java` (mới), `cli/Main.java`, `docs/USAGE.md`, `examples/*` | `LuaToJvmTranslator.java`, `LuaTable.java`, `BytecodeVM.java`, `JitRuntime.java` | `docs/work-claims/W4-embedding-ops.CLAIM.md` |

W1↔W2↔W3↔W4 không giao nhau ở `src/main` → merge không conflict.
W3 chạm `JitRuntime.java` nhưng CHỈ hàm `buildClosure`; W1 không được chạm `JitRuntime.java`.

## Giao thức claim (file-based lock, không cần tool)

1. Worker mở claim file của việc mình muốn, xem dòng `Owner:`.
2. Nếu `Owner: _unclaimed_` → sửa thành `Owner: <tên-worker>` + `Status: claimed`
   + commit ngay 1 commit `claim: Wx <tên>` để giữ chỗ. Ai commit trước thắng.
3. Trong ngày chỉ sửa file trong `Allowed`. Sửa ngoài `Allowed` = vi phạm, revert.
4. Xong việc: cập nhật `Status: done` + ghi `Kết quả đo` vào claim file, mở PR/merge.
5. Hết ngày không xong: ghi `Status: blocked: <lý do>` + push nhánh dở, không giữ claim quá 24h.

## Thứ tự merge (tránh lệch baseline đo)

W4 (additive, an toàn nhất) → W1 → W3 → W2 (rủi ro cao nhất, merge cuối).
Mỗi merge xong phải chạy lại cổng chung trước khi merge tiếp theo.

## Trạng thái (2026-11-19)

| Worker | Trạng thái | Commit/nhánh | Ghi chú |
|---|---|---|---|
| W4 | done, đã merge | `8444eb1` | 374 test xanh; stats/close/evalAsync/5 cờ CLI |
| W2 | done, đã merge (chờ đo lại) | `0d4ff11` | hết regression 3.6×; 03 = 1.019, 07 = 0.925 (chưa lặp lại lần 2) |
| W1 | đang đo interleave | `/tmp/opencode/w1` `c34d4af` | kernel 1146→737B; fib còn chậm 6-9% ở lần đo đầu |
| W3 | blocked (dưới bar 3%) | `w3/closure-alloc` `0252aba` | 05 = 0.987; phần còn lại ngoài scope hẹp |

Quy tắc: kết quả dưới bar 3% **không merge**; giữ nhánh để gộp đo lại khi có
thay đổi code-shape khác (W1/W3 cùng hướng). Kết quả W2 phải tái lập ≥3% trước
khi coi là win thật, nếu không thì revert để tránh code vô ích (AGENTS §IV.4).
