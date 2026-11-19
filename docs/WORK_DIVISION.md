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
| W4 | done, đã merge | `8444eb1` | 366 test xanh; stats/close/evalAsync/5 cờ CLI |
| W2 | done, **đã revert** | `0d4ff11` → revert `8a35bf8` | 15 cặp: 03 = 1.005, 07 = 0.996, 10 = 1.019 → trung tính; giữ commit trong lịch sử để phục hồi |
| W1 | **đã revert** | `3180fa2` → revert | kernel 306B/fib −15% nhưng seed tham số = INT làm hàm gọi bằng float deopt mọi lần (`math.sqrt`) và bị disarm; xem ghi chú dưới |
| W3 | blocked (dưới bar 3%) | `w3/closure-alloc` `0252aba` | 05 = 0.987; phần còn lại ngoài scope hẹp |

Quy tắc: kết quả dưới bar 3% **không merge**; giữ nhánh để gộp đo lại khi có
thay đổi code-shape khác (W1/W3 cùng hướng). W2 là ví dụ ngược: đo 7 cặp cho
`07 = 0.925` nhưng 15 cặp chỉ `0.996` — với nhiễu sàn ~13%, **7 cặp không đủ**
để kết luận dưới 3%; chỉ revert sau khi có lượt đo thứ hai.

### Vì sao W1 bị revert (2026-11-19)

Kernel nhỏ đạt được nhờ **int-only lane** dựa trên suy luận tĩnh seed tham số
`T_INT`. Hệ quả: hàm gọi bằng float (`local function f(x) return
math.sqrt(x*x+x) end` với `x = 2.5`) compile thành công nhưng **deopt ở mọi
lần gọi** (`pc=2`, `up0tag=0`) rồi bị disarm sau 8 lần. Test cũ che lỗi vì JIT
async: 400 lần gọi kết thúc trước khi kernel kịp compile.

Đo trực tiếp: base `compiled: f` rồi chạy sạch; W1 `compiled: f` + 9 dòng deopt.
Test `JitCoverageTest.sqrtIntrinsicProtoActuallyCompiles` đỏ trên CI và trên
máy khi bật `-Dluava.jit.sync=true`.

Hướng đúng nếu làm lại: phân biệt **provenance** (giá trị int chứng minh được
từ `LOADI`/hằng int) với **giả định** (tham số), hoặc hai phiên bản kernel
(int/param) chọn theo tag đối số tại call site. Không seed tham số là int.
