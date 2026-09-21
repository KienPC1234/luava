# plan.md — Mở rộng Hybrid Tiered JIT tối đa

> **Mục tiêu tối thượng:** mở rộng độ phủ JIT tới **mọi cấu trúc Lua 5.4 có thể
> tăng tốc hợp lệ**, giữ **30/30 suite PUC 5.4.9 + toàn bộ unit test xanh**,
> đánh bại hoặc hòa LuaJ 3.0.1 trên cả 10 benchmark, và **không bao giờ** sửa
> `tests/lua-5.4.9-tests/`.
>
> Tài liệu này là **nguồn duy nhất** thay cho `plan.md` cũ và `PLANS.md`
> (đã xóa). Hồ sơ thiết kế register-VM cốt lõi được cô đọng lại ở §2; nhật ký
> thất bại quan trọng được bảo tồn ở §9.
>
> Cập nhật: 2026-09-20. Trạng thái: **Phase A, B, C, D, E đã hoàn thành**. Độ
> phủ mở rộng từ ~4/24 lên ~19/24 dạng cấu trúc; 185 unit test + 30/30 suite
> PUC xanh (ép prewarm mọi proto hợp lệ cũng 30/30). Xem §12 để biết trạng thái
> từng phase.

---

## 0. TL;DR cho người vội

- **JIT không yếu về chất lượng** — nó biến fib(35) từ ~5400 ms xuống ~266 ms
  (nhanh 20×, thắng LuaJ ~10×) nhờ "một method JVM per proto" cho C2 inline.
- **JIT yếu về độ phủ** (đã cải thiện mạnh trong đợt 2026-09-20):
  - JIT on/off: fib **0.049** (20×), arith main loop **0.18–0.21** (~5×),
    table ops **0.36** (2.8×), closures 0.50, hash 0.73, oop 0.74; còn
    pattern (cần `TFOR*`) và sieve (cần multret) ≈ 1.0.
  - **5/10 kernel benchmark** được compile (01/03/05/07 + hàm 02/08).
- **Nút thắt đầu tiên (đã xử lý):** hotness chỉ đếm ở `OP_CALL`/`OP_TAILCALL`,
  và main chunk luôn `isVararg=true` → vòng lặp ở chunk chính không tier-up.
  Đã thêm tier-up theo loop tại `FORPREP` + entry JIT top-level (kể cả void).
- **Nút thắt thứ hai (đã xử lý):** lattice số `T_INT` vs `T_NUM` xung đột;
  nay join về `T_NUM` nhờ numeric dispatch runtime.
- **Nút thắt thứ ba (đã xử lý):** `hasCalls && impure` chặn gần hết code thực
  (bảng + gọi hàm). Nay impure proto compile được, **mọi CALL/TAILCALL deopt**
  trước khi vào callee → resume tại pc, không ghi lặp.
- Kế hoạch: **Phase A–J**; A, B, C, D, E đã xong. Còn generic-for (`TFOR*`),
  vararg đọc `...`, inline-cache đa hình.

---

## 1. Nguyên tắc bất di bất dịch (áp dụng cho MỌI phase)

### 1.1 Kỷ luật test (từ `AGENTS.md`)
- **Cấm** sửa/tamper/xóa/bỏ qua bất kỳ dòng nào trong `tests/lua-5.4.9-tests/`.
- **Cấm** hardcode/mock/branch "để qua test".
- Tiến độ = **30/30 suite pass tự nhiên** + toàn bộ unit test.
- Mọi lỗi truy gốc theo tầng (Lexer/Parser/AST/Bytecode/VM/Stdlib/JIT) và sửa
  tại tầng đó.
- Mỗi lần chạy suite phải có `timeout`; không `nohup`.

### 1.2 Kỷ luật đo lường
- Máy drift ±10%. Mọi claim perf = **2 jar (pre/post)** chạy **xen kẽ từng cặp**
  (A,B,A,B…), **≥7 cặp**, lấy **median**; **bar 3%** mới tính thắng.
- **Pin core** (`taskset -c <core rảnh>`) cho mọi task trừ coroutine
  (virtual-thread pool; pin sẽ bóp chết: 297ms → 21s).
- Luôn đo thêm **JIT on/off per-task**, không chỉ tổng thể (tránh lọt việc JIT
  làm task nào đó chậm đi).
- Profiler: `async-profiler` (`-agentpath`), không JFR ExecutionSample.

### 1.3 Kỷ luật commit
- Commit theo batch, message kèm số liệu interleave.
- Revert nếu dưới bar hoặc gây regression; ghi lý do vào §9.
- Không file rác, không vanity code. Mọi thay đổi phục vụ logic/tương thích/perf.

### 1.4 Ràng buộc nền tảng
- Java 21 LTS stock, **không preview, không JNI/FFM**, chỉ `org.ow2.asm`.
- Không `System.out` trong runtime.
- Kiến trúc value: triple-stack (`long[] pStack`, `byte[] tStack`,
  `LuaValue[] oStack`) — JIT thao tác trực tiếp trên nó.

---

## 2. Hiện trạng kiến trúc JIT (đọc trước khi sửa)

```
Lua source ─Lexer/Parser─► AST ─BytecodeCompiler─► LuaProto
                                                     │
                                     ┌───────────────┴────────────────┐
                                     ▼                                ▼
                              BytecodeVM (interpreter)         JitCompiler (ASM)
                               - mặc định / fallback            - chỉ proto nóng
                               - coroutine, guards              - hidden class
                               - proto lớn / chưa phủ           - guard + deopt
                                     ▲                                │
                                     └──── deopt / tier-down ─────────┘
```

### 2.1 Mô hình thực thi
- **Một method JVM per `LuaProto`** (không per closure):
  `static long exec(LuaClosure, Object[] up, long[] p, byte[] t, LuaValue[] o, int base)`
  (int-return) hoặc `static LuaValue execObj(...)` (object-return).
- Hidden class qua `MethodHandles.Lookup.defineHiddenClass(..., NESTMATE)`;
  `JitCodeCache` LRU **512** chống leak Metaspace (evict → clear `proto.jitCode`).
- Register window dùng chung triple-stack; tham số truyền ngay trên window của
  caller (`base = funcIdx + 1`).
- `execInner` là biến thể đã hoist closure bất biến cho call-site fusion.

### 2.2 Hotness & tier-up
- `LuaProto.hotCount`, ngưỡng `LuaState.JIT_HOT_THRESHOLD = 50`, tăng **chỉ**
  trong `tryJitCall`/`tryJitTailCall` (tức `OP_CALL`/`OP_TAILCALL`).
- Vượt ngưỡng → `JitCompiler.requestCompile(proto)`:
  - mặc định **compile nền** (daemon `luava-jit`, queue collapse `jitQueued`);
  - `-Dluava.jit.sync=true` → compile đồng bộ (dùng cho đo đạc).
- `JitCompiler.prewarm(closure[, state])` compile trước cả cây proto.
- `ENABLE_JIT` mặc định **true**; override per-state `state.jitEnabled(Boolean)`.

### 2.3 Guard + deopt
- Mọi giả định kiểu kiểm tra ở runtime: đọc `tStack[idx]` (tag), sai → `DeoptSignal`
  (extends `Error`, không stack trace) mang `pc`.
- Ba chế độ ở entry: `0` = không JIT (cold/không hợp lệ/guard); `1` = chạy xong
  frameless; `2` = deopt giữa chừng, interpreter **resume tại `jitResumePc`**
  với state đã commit (bảng/upvalue ghi an toàn, không chạy lại).
- Deopt > 8 lần → `jitDisabled`, quay về interpreter (chống deopt storm).

### 2.4 Giới hạn tĩnh của `analyze()`
- `proto.isVararg` → **null** (reject).
- `code.length == 0 || > 200` → reject.
- `maxStackSize > 64 || numParams > 16` → reject.
- Opcode phải nằm trong allow-list (§3.1); op khác → reject.
- `hasCalls && impure` (có ghi upvalue/table/global) → reject.
- Object-return + `hasCalls` → reject.
- `CALL` phải `B>=1` và (nếu là `CALL`) `C==2`; `SETLIST B==0` → reject.
- K-form `ADDK/SUBK/MULK/IDIVK/MODK/BANDK/BORK/BXORK` đòi hằng `LuaInteger`;
  `DIVK/POWK` bị reject (luôn float).
- Type-inference forward: `T_INT ∪ T_NUM` = **conflict → reject** (nút thắt B).

### 2.5 Tương tác coroutine/debug/guard
- `proto.mayYield` → không JIT (bảo toàn coroutine).
- `ctx.thread.hooksActive || state.loopGuard != null` → bỏ qua JIT hoàn toàn.
- JIT frameless nhưng mọi lỗi Lua deopt trước khi phát sinh → line/frame chính xác.

---

## 3. Ma trận phủ JIT đầy đủ (bằng chứng 2026-09-20)

### 3.1 Opcode hiện được compile (allow-list thật)
`MOVE, LOADI, LOADF, LOADK, LOADNIL, LOADTRUE, LOADFALSE, CLEANUP, CLOSE,
GETUPVAL, SETUPVAL, GETTABUP, GETTABLE, GETI, GETFIELD, SETTABUP, SETTABLE, SETI,
SETFIELD, SELF, CLOSURE, NEWTABLE, EXTRAARG, UNM, BNOT, NOT, LEN, SETLIST,
ADD, SUB, MUL, ADDI, ADDK, SUBK, MULK, IDIVK, MODK, BANDK, BORK, BXORK,
LEI, LTI, GTI, GEI, EQI, EQ, EQK, LT, LE, TEST, TESTSET, LFALSESKIP,
DIV, DIVK, POW, POWK, MOD, IDIV, BAND, BOR, BXOR, SHL, SHR, SHLI, SHRI,
CONCAT, JMP, FORPREP, FORLOOP, CALL, TAILCALL, RETURN, RETURN1, RETURN0`
+ intrinsic `math.sqrt`.

### 3.2 Opcode/cấu trúc thiếu → nguyên nhân reject

| Opcode thiếu | Sinh bởi cấu trúc Lua | Hệ quả |
|---|---|---|
| `LT`, `LE` | `while i<n`, `repeat until i>=n`, `if a<b` | while/repeat/so sánh biến không JIT |
| `EQ` (RK), `EQK` | `if x==y` (không phải hằng int) | nhánh so sánh không JIT |
| `TEST`, `TESTSET` | `and`/`or`, `if x then`, ternary | and/or không JIT |
| `TFORPREP`, `TFORCALL`, `TFORLOOP` | `for k,v in pairs/ipairs/gmatch` | generic-for không JIT |
| `VARARG`, `VARARGPREP` | `...`, select | hàm vararg (đọc `...`) không JIT |
| `LOADKX` | hằng vượt 2^18 | hiếm |
| `MMBIN`,`MMBINI`,`MMBINK` | metamethod arith | (deopt đúng — giữ interpreter) |

Các opcode từng bị reject nay **đã hỗ trợ**: `CLOSURE`, `CLOSE`, `SELF`,
`RETURN0` (void), `CONCAT`, `DIV/DIVK`, `POW/POWK`, bitwise hai ngôi + immediate.
Còn lại chủ yếu là generic-for (`TFOR*`) và vararg đọc `...`.

### 3.3 Khảo sát 24 dạng cấu trúc "cần JIT" (probe tự động)

Chú thích: ✅ compile · ❌ reject · (lý do opcode reject)

| # | Cấu trúc | Kết quả | Opcode chặn |
|---|---|---|---|
| 01 | `for i=1,n` số nguyên | ✅ | — |
| 02 | `for i=1.0,n` số thực | ✅ | — |
| 03 | `while i<n` | ❌ | `LT` |
| 04 | `repeat … until` | ❌ | `LE` |
| 05 | `for k,v in ipairs(t)` | ❌ | `TFORPREP/TFORCALL/TFORLOOP/CLOSE` |
| 06 | `for k,v in pairs(t)` | ❌ | `TFOR*` |
| 07 | `o:method()` | ❌ | `CLOSURE`, `SELF` |
| 08 | closure nội bộ + gọi | ❌ | `CLOSURE` |
| 09 | `s = s .. "x"` | ❌ | `CONCAT` |
| 10 | `s = s + i/2.0` | ❌ | `DIVK` |
| 11 | `s = s + i^2` | ❌ | `POWK` |
| 12 | `s = s + i%7` | ✅ | — |
| 13 | `s = s + (i&255)` | ❌ | `BAND` |
| 14 | `if i==5` (hằng int) | ✅ | — |
| 15 | `if i<x` (biến) | ❌ | `LT` |
| 16 | `x or i` | ❌ | `TESTSET` |
| 17 | vararg `...` | ❌ | `VARARG` (+`isVararg`) |
| 18 | `#t` (độ dài bảng) | ❌ | `LEN`? thực tế reject do CLOSURE+type |
| 19 | `math.floor(i/3)` | ❌ | `DIVK` |
| 20 | đa giá trị `a,b=f()` | ❌ | `CLOSURE` + multret |
| 21 | OOP `__index` method | ❌ | `CLOSURE`, `SELF` |
| 22 | hàm lồng gọi nhau | ❌ | `CLOSURE`, `CLOSE` |
| 23 | `pcall(function()…)` | ❌ | `CLOSURE` |
| 24 | table constructor `{1,2,3}` | ❌ | `CLOSURE` + type |

> Hầu hết case reject vì **`CLOSURE` xuất hiện ở main chunk** (mọi `local
> function` đều phát `OP_CLOSURE` trong chunk chứa nó). Đây là lý do "cả main
> chunk bị loại" và cũng là lý do hàm nóng bị loại nếu thân nó tạo closure.

### 3.4 10 benchmark: ai được JIT, ai không

| task | kernel JIT? | JIT on/off | vs LuaJ (paired) |
|---|---|---|---|
| 01 arith (main loop) | ✅ main chunk (void + loop) | **0.18–0.21** | **8.3×** |
| 02 fib | ✅ `fib` | **0.049** | **9.93×** |
| 03 table (main loop) | ✅ main chunk (impure, call deopt) | **0.36** | ~1.03× |
| 04 concat (main loop) | ❌ (main không eligible) | ~0.85–0.96 (nhiễu) | ~0.9× |
| 05 closures | ✅ `make_counter` (CLOSURE) | **0.50–0.55** | ~0.98× |
| 06 coroutines | ❌ (chủ ý) | ~0.98 | **16.4×** |
| 07 hash (main loop) | ✅ main chunk (string key) | **0.73** | **1.96×** |
| 08 oop | ✅ `dot`/`length`, ❌ `new`/`add` | 0.74 | 1.18× |
| 09 pattern (main loop) | ❌ (cần `TFOR*`) | ~1.00 | 0.91× |
| 10 sieve (main loop) | ❌ (cần multret call `math.floor(math.sqrt(N))`) | ~1.00 | ~1.02× |

Kết luận: nhờ mở phủ main-chunk loop + void/CLOSURE/SELF, **5/10 kernel** giờ
tier-up (01/03/05/07 + 02/08 hàm). Hai task còn chặn là `04` (main không eligible)
và `09`/`10` (generic-for / multret).

---

## 4. Năm nút thắt gốc rễ

| # | Nút thắt | Tầng | Bằng chứng | ROI |
|---|---|---|---|---|
| **A** | Hotness chỉ đếm ở `OP_CALL`; main chunk không bao giờ tier-up; không đếm back-edge cho `while`/`repeat` | `BytecodeVM` + `JitCompiler` | loop arith bọc hàm + prewarm: **39.7 vs 220 ms (5.5×)** | **Rất cao** |
| **B** | Lattice `T_INT ∪ T_NUM` = conflict → reject loop `s=0; s=s+t[i]` | `LuaToJvmTranslator.transfer` | tab_int reject, đổi `s=0.0` compile; **42.9 vs 151.7 ms (3.5×)** | **Rất cao** |
| **C** | Thiếu `LT/LE/EQ/TEST/TESTSET` → `while`/`repeat`/and-or/so sánh không JIT | `analyze` + emit | probe 03/04/15/16 ❌ | Cao |
| **D** | `CLOSURE`/`SELF`/object-return bị cấm → hầu hết hàm "thực tế" (có closure con, method) bị loại | `analyze` + `OP_CLOSURE` revert trước đây | probe 07/08/21/22/23 ❌; benchmark main chunk đều có `CLOSURE` | Cao |
| **E** | Thiếu `CONCAT/DIV/POW/bitwise 2 ngôi/generic-for/vararg` | `analyze` + emit | probe 05/06/09/10/11/13/17 ❌ | Trung–cao |

---

## 5. Roadmap mở rộng (Phase A → J)

Mỗi phase có: việc, file, cổng (G-CORRECT/G-PERF/G-SIZE), rủi ro/rollback.
Cổng chuẩn:
- **G-CORRECT:** 30/30 suite + toàn bộ unit test xanh **cả JIT on/off**; fuzz
  đối chiếu JIT on/off byte-identical; test cụ thể cho phase.
- **G-PERF:** interleave ≥7 cặp pinned, bar 3%, **không regression task khác**.
- **G-SIZE:** method sinh ra không phình; `PrintCompilation` xác nhận C2.

---

### Phase A — Hotness theo back-edge + main chunk (ROI #1)

**Mục tiêu:** mọi vòng lặp thực sự nóng (kể cả main chunk, `while`, `repeat`)
được tier-up; mở khoá arith/table/hash/pattern/sieve.

**Việc:**
1. Thêm bộ đếm back-edge **không cấp phát**: `LuaProto.loopHotCount` (plain int).
   Tăng tại `OP_FORLOOP`, `OP_JMP` có `offset < 0` (back-edge), và
   `OP_TFORLOOP` khi đã hỗ trợ.
2. Khi counter vượt ngưỡng (đề xuất `JIT_LOOP_THRESHOLD`, ví dụ = `code.length`
   hoặc hằng số riêng) → `requestCompile(proto)`.
3. Cho phép tier-up **main chunk**: tại entry `BytecodeVM.execute`, seed
   `hotCount`/kiểm tra sớm để main chunk có cơ hội compile; hoặc đếm back-edge
   trong main chunk và request compile chính proto đó.
4. **Relax `isVararg`:** chỉ reject khi proto thực sự chứa `OP_VARARG`
   (hoặc `VARARGPREP`), thay vì reject mọi `isVararg == true`. Main chunk
   không dùng `...` sẽ hợp lệ.
5. Tránh compile storm: dùng `jitQueued`/`jitDisabled` như hiện tại; ngưỡng
   back-edge nên đủ lớn để không compile loop chạy vài lần.

**File:** `LuaProto.java` (thêm counter), `BytecodeVM.java` (đếm back-edge,
entry main chunk), `JitCompiler.java`/`LuaToJvmTranslator.analyze` (relax
vararg), `LuaState.java` (ngưỡng).

**Rủi ro:** JIT một proto đang chạy interpreter giữa loop → phải an toàn
(compile là idempotent, lần gọi/loop sau dùng `jitCode`). Deopt storm nếu loop
có kiểu động → giữ `jitDisabled` sau 8 deopt.

**Gates:** arith/table/sieve JIT on/off < 0.5; G-CORRECT; không regression.

**Rollback:** ngưỡng = ∞ / cờ tắt back-edge.

---

### Phase B — Hợp nhất lattice số (ROI #2)

**Mục tiêu:** loop trộn `T_INT`/`T_NUM` không còn bị reject.

**Việc:**
1. Trong `transfer`/hợp nhất, đổi `T_INT ∪ T_NUM = T_NUM` (an toàn: emit đã có
   numeric dispatch runtime; chỉ tốn một guard tag). Loại bỏ nhánh conflict
   cho cặp int/num.
2. Xác nhận mọi điểm dùng `T_INT`-specific (`emitGuardInt`) vẫn đúng khi giá
   trị là `T_NUM` (đã có float lane cho `ADD/SUB/MUL/K`; cần bổ sung cho
   bitwise/`UNM/BNOT` — chúng vốn int-only, giữ guard int).
3. Giữ `T_OBJ ∪ T_NUM = reject` (object mode khác protocol).

**File:** `LuaToJvmTranslator.java` (`transfer`, hợp nhất forward analysis).

**Rủi ro:** suy luận sai subtype → kết quả sai. Giảm thiểu: mọi giá trị vẫn đi
qua guard tag runtime; test `JitCoverageTest` numeric + fuzz.

**Gates:** `tab_int` compile và JIT on/off < 0.5; numeric mixed test xanh.

**Rollback:** khôi phục conflict.

---

### Phase C — Control-flow: `while`/`repeat`, so sánh, and/or

**Mục tiêu:** `while`, `repeat`, `if a<b`, `and`/`or` JIT.

**Việc (thêm emit + allow-list):**
1. `LT`, `LE` (2 thanh ghi, có thể mixed int/float): so sánh numeric với
   numeric dispatch; non-number → deopt (mirror interpreter).
2. `EQ`, `EQK` (RK so sánh): hỗ trợ int/num/string/boolean; object phức tạp
   (bảng/hàm) → deopt.
3. `TEST`, `TESTSET`: truthiness thuần (đã có `isTruthy` helper cho `NOT`);
   `TESTSET` ghi kết quả.
4. `LFALSESKIP` nếu compiler phát (dùng trong `if` tối ưu).
5. `CLOSE` mức tối thiểu khi không có upvalue mở (hoặc deopt an toàn).

**File:** `LuaToJvmTranslator.java` (analyze + emit + transfer + successors).

**Rủi ro:** semantics dấu `NaN`, so sánh int/float, `-0.0`; phải khớp
interpreter bit-for-bit. Test ma trận `-7/3`, NaN, ±0.

**Gates:** probe 03/04/15/16 compile; kết quả khớp JIT off; while/repeat loop
micro-bench JIT on/off < 0.7.

**Rollback:** cờ từng opcode trong allow-list.

---

### Phase D — Closure, SELF, object-return (dư địa lớn)

**Mục tiêu:** hàm "thực tế" (tạo closure con, method OOP) JIT được.

**Việc:**
1. **`OP_CLOSURE` trong JIT** — trước đây revert vì helper tốn ThreadLocal.
   Nay làm đúng: dựng `LuaClosure` trực tiếp từ `proto.protos[idx]` + upvalue
   descriptors, **không** qua helper lookup. Đo lại paired; chỉ giữ nếu thắng.
2. **`SELF`**: `R[A+1]=R[B]; R[A]=R[B][K[C]]` với guard "rawget non-nil" như
   `GETFIELD`; miss `__index` → deopt.
3. **Nới `returnsObj`**: cho phép object-return + call khi callee là pure
   (truyền object protocol). Object-return hiện chỉ cho leaf call-free.
4. **`CLOSE`/`TBC`** đúng ngữ nghĩa upvalue mở; nếu phức tạp → deopt an toàn.

**File:** `LuaToJvmTranslator.java`, có thể `JitRuntime.java`,
`Upvalue.java` (nếu cần lane mở).

**Rủi ro:** Metaspace/alloc; đóng upvalue (`closeOnJitReturn`) sai → hỏng dữ
liệu. Đã có bug này trước đây; test `LuavaStressTest` + closure/upvalue fuzz.

**Gates:** benchmark `05_closures`, `08_oop`, `main` của mọi benchmark hết
`CLOSURE` reject; closures/oop JIT on/off giảm thêm ≥10%; G-CORRECT.

**Rollback:** cờ `OP_CLOSURE`/`SELF`/object-return.

---

### Phase E — CONCAT, DIV/POW, bitwise 2 ngôi

**Việc:**
1. `CONCAT` (B..C): fast path khi mọi operand là string/int/float (dùng
   `LuaValue.concat` có cap alloc); có metamethod/object → deopt.
2. `DIV`, `DIVK`, `POW`, `POWK`: luôn float (raw-bit lane), mirror `luaNumPow`
   (đặc biệt `pow(1,y)=1`, `pow(-1,±inf)=1`).
3. `BAND/BOR/BXOR/SHL/SHR/SHRI/SHLI`: integer-only + guard int; float → deopt
   (đúng lỗi interpreter). `SHRI/SHLI` là dạng immediate.
4. `MOD`, `IDIV` 2 ngôi (đã có K-form): thêm dạng thanh ghi.

**File:** `LuaToJvmTranslator.java`.

**Gates:** probe 09/10/11/13 compile; ma trận số học mở rộng (JIT off vs on vs
stock) khớp bit-for-bit; không regression.

**Rollback:** cờ từng opcode.

---

### Phase F — Generic-for: `pairs`/`ipairs`/`gmatch`

**Việc:**
1. `TFORPREP`, `TFORCALL`, `TFORLOOP`: mô hình hóa iterator 3 giá trị
   (func, state, control). Fast path: iterator là closure pure của JIT →
   gọi kernel; còn lại deopt.
2. `ipairs`/`pairs` builtin: intrinsic hóa khi iterator đúng singleton
   (như mô hình `math.sqrt`), guard identity để tôn trọng việc gán lại.
3. `CLOSE` sau vòng lặp (nếu có upvalue mở).

**Rủi ro:** ngữ nghĩa `__pairs`, thứ tự next, generic-for đa giá trị. Test đối
chiếu interpreter và `nextvar.lua`.

**Gates:** probe 05/06 compile; `nextvar.lua` xanh; pattern JIT on/off < 0.7.

**Rollback:** cờ `TFOR*`.

---

### Phase G — Vararg

**Việc:**
1. `VARARGPREP`: thiết lập varargs từ frame.
2. `VARARG` mức cố định `B!=0` (không mở multret) với guard; multret deopt.
3. Cho phép `isVararg` proto nếu mọi `VARARG` đều cố định.

**Rủi ro:** layout multret/stack. Test `vararg.lua`, `calls.lua`.

**Gates:** vararg hàm cố định compile; toàn bộ vararg suite xanh.

---

### Phase H — Call-site đa hình & inline cache

**Việc:**
1. Mở rộng call-site ngoài self/monomorphic: **inline cache** theo
   `(proto identity)`; polymorphic nhỏ → chuỗi guard; megamorphic → deopt
   hoặc `invokedynamic` (chỉ khi có bằng chứng cần).
2. Cache `MethodHandle` cho callee JIT; tránh `Method.invoke`.

**Gates:** OOP kế thừa/đệ quy chéo JIT on/off giảm ≥15%; không tăng Metaspace
quá cache bound.

---

### Phase I — Deopt robustness & debug semantics mở rộng

**Việc:**
1. Khi phủ rộng hơn, kiểm tra lại: line info, traceback, `pcall`/`xpcall`,
   `debug.getinfo`, hook, coroutine, `error` đệ quy — tất cả phải khớp JIT off.
2. Cân nhắc push frame cho JIT (nếu traceback thiếu frame trở thành vấn đề);
   đo chi phí (~30ns/call) trước khi quyết định.
3. Fuzz differential PUC 5.4.9 diện rộng sau mỗi phase mở rộng.

**Gates:** `db.lua`, `errors.lua`, `events.lua`, `calls.lua` xanh ở threshold=1
(JIT ép chạy); fuzz đồng nhất.

---

### Phase J — Xác thực hiệu năng cuối

**Việc:**
1. Interleave 10 task vs LuaJ 3.0.1 (≥9 cặp, pinned, median, warm=5, iters=8).
2. Đo **cold start / warm start / hot** cho Luava và LuaJ (harness
   `ProfileLuava`/`ProfileLuaj`), ghi bảng vào `benchmarks/harness/`.
3. Đo JIT on/off từng task; mọi task phải `< 1.0` (JIT có lợi) hoặc trong noise.
4. RSS/Metaspace bounded; stress JIT-on.
5. Cập nhật `README.md` + bảng coverage.

**Mục tiêu số:** thắng ≥7/10, hòa 2, không thua > 1.03×; arith/table/sieve
JIT on/off ≤ 0.5.

---

## 6. Mục tiêu hiệu năng & cổng

| Hạng mục | Hiện tại | Mục tiêu |
|---|---|---|
| arith JIT on/off | ~1.00 | ≤ 0.5 |
| table JIT on/off | ~1.00 | ≤ 0.5 |
| sieve JIT on/off | ~1.00 | ≤ 0.5 |
| while/repeat | không JIT | JIT, on/off ≤ 0.7 |
| closures JIT on/off | 0.55 | ≤ 0.4 |
| oop JIT on/off | 0.74 | ≤ 0.5 |
| fib JIT on/off | 0.049 | giữ ≤ 0.06 |
| vs LuaJ | 6 thắng / 2 hòa / 2 thua nhẹ | ≥7 thắng, 0 thua >1.03× |
| Cold start engine | 356 ms | giữ ≤ LuaJ |

---

## 7. Đo lường (harness có sẵn)

- `benchmarks/lua/01..10*.lua` — 10 task.
- `benchmarks/harness/interleave.sh <jarA> <jarB> <pairs> <warm> <iters> <core> [tasks]`
  — paired đảo thứ tự, median, ratio `B/A`.
- `benchmarks/harness/Bench.java` (Luava), `LuaJBench.java` (LuaJ).
- `BenchmarkRunner.java` — cold/warm/hot trong 1 JVM.
- `/tmp/opencode/cold/ProfileLuava.java`, `ProfileLuaj.java` — cold/warm/hot
  đối xứng hai engine (harness tạm; số liệu §3.4).
- Fuzz JIT on/off: sinh case số học/bảng/closure/pattern, so byte-identical
  (đã dùng 12k–15k case trong các đợt trước).

---

## 8. Rủi ro & rollback

| Rủi ro | Mức | Đối sách |
|---|---|---|
| Deopt sai → lệch ngữ nghĩa | Cao | guard dày + fuzz đối chiếu stock mỗi opcode; mở từng opcode |
| Metaspace leak khi phủ rộng | Cao | hidden class + LRU 512 + chỉ JIT proto nóng |
| Coroutine yếu đi | Cao | cấm JIT `mayYield`; test 2000 churn |
| Debug/traceback sai | Cao | Phase I; suite db/errors/events |
| Compile storm / deopt storm | Trung | ngưỡng + `jitDisabled` sau 8 deopt |
| C2 không inline code JIT | Trung | giữ method nhỏ; `PrintInlining` |
| JIT chậm hơn interpreter task nhỏ | Trung | đo on/off per-task; tier-up ngưỡng |
| `OP_CLOSURE` lại thua (đã revert 1 lần) | Trung | implement không-helper rồi đo paired; revert nếu dưới bar |

Mọi phase rollback bằng cờ (opcode allow-list / ngưỡng / `ENABLE_JIT=false`).

---

## 9. Nhật ký thất bại (bảo tồn — đọc trước khi đề xuất lại)

| Thử nghiệm | Kết quả | Nguyên nhân |
|---|---|---|
| JIT `OP_CLOSURE` bản cũ (helper ThreadLocal) | closures 66→76ms (+15%) | body toàn alloc, helper tốn 2 ThreadLocal lookup; revert |
| `SELF` fast lane interpreter cũ | 1/3 thua | OOP dùng metatable → lane không cháy |
| Method PIC 4-entry | ~2.3% | `rawget` 19ns ăn nửa savings |
| MERGE CallInfo→Frame | 0…−10% | C2 không inline qua boundary |
| Java-recursive OP_CALL | −14% | `runLoop` >8KB hot, không inline |
| Inline SUBK vào dispatch | fib −2.5% | `runLoop` vượt ≤7000B |
| Bỏ caller-frame stamp | 29/30 fail | `errors.lua:399` cần line caller |
| Tắt `vmPcMirror` | sieve +4–5% | sai `debug.getinfo().currentline` |
| Lazy 2 List `LuaFunction` | closures +3%, oop −5% | đổi class-shape, C2 compile tệ |
| `BASIC_METATABLES` static toàn JVM | state mới xóa metatable state cũ | đã fix: registry theo active state |
| `GCManager` singleton | xóa finalizer mọi state | đã fix: owner theo state |

**Bài học:** mọi micro-opt quanh call/table/frame của interpreter đã cạn; hai
lần exp2reg fail 12 suite vì thiếu loại trừ. Đột phá bắt buộc đến từ **phủ JIT
rộng hơn**, không từ micro-opt interpreter.

---

## 10. Điều KHÔNG làm

- Không sửa `tests/lua-5.4.9-tests/**`.
- Không hardcode/mock để qua test.
- Không JIT hàm có thể yield (bảo toàn coroutine).
- Không bỏ metadata debug để lấy tốc độ (ngược `AGENTS.md`).
- Không thêm file rác/vanity; harness tạm để `/tmp/opencode`.
- Không JIT metamethod call phức tạp (`__call`, `__index` fallback) — deopt.

---

## 11. Ước lượng thời lượng

| Phase | Nội dung | Effort |
|---|---|---|
| A | Back-edge + main chunk + relax vararg | 3–5 ngày |
| B | Hợp nhất lattice số | 1–2 ngày |
| C | while/repeat/cmp/and-or | 4–6 ngày |
| D | CLOSURE/SELF/object-return | 1–2 tuần |
| E | CONCAT/DIV/POW/bitwise | 4–6 ngày |
| F | Generic-for | 1 tuần |
| G | Vararg | 3–5 ngày |
| H | Inline cache đa hình | 1–2 tuần |
| I | Deopt/debug robustness | 1 tuần |
| J | Xác thực cuối | 3–5 ngày |

Ưu tiên thực dụng: **A + B + C** trước (≈1.5 tuần) đã mở khoá phần lớn
benchmark main-chunk loop; D/E/F/G mở rộng dần theo nhu cầu thực tế.

---

## 12. Trạng thái thực hiện (2026-09-20)

| Phase | Trạng thái | Nội dung đã làm |
|---|---|---|
| A1/A2 | ✅ xong | Tier-up theo loop qua `FORPREP` trip count (`JIT_LOOP_THRESHOLD=8192`); entry JIT cho top-level chunk trả 1 giá trị (`runTopLevelJit`) |
| A3 | ✅ xong | Bỏ reject mọi `isVararg`; chỉ reject khi proto thực dùng `...` (VARARG ngoài allow-list) |
| B | ✅ xong | `mergeTy`: `T_INT ∪ T_NUM = T_NUM` (an toàn nhờ numeric dispatch runtime) |
| C | ✅ xong | `LT/LE/EQ/EQK/TEST/TESTSET/LFALSESKIP` + `successors`/`transfer` |
| D1 | ✅ xong | `RETURN0`/void proto: kernel trả sentinel `long`, caller materialize 0/nil; `Info.returnsVoid`; `execInner` phát cả cho void |
| D2 | ✅ xong | `OP_CLOSURE` + `OP_CLOSE`: `JitRuntime.buildClosure`/`closeAt`; đánh dấu impure (factory object-return) |
| D3 | ✅ xong | `OP_SELF`: `emitSelf` + `JitRuntime.selfMethod` guard rawget-non-nil |
| D4 | ✅ xong | Bỏ reject `hasCalls && impure` **khi có loop back-edge trước call đầu tiên**: impure proto compile được nhưng **mọi CALL/TAILCALL deopt trước khi vào callee** (resume tại pc, không double-write). Deopt cấu trúc dùng budget riêng `JIT_STRUCTURAL_DEOPT_BUDGET=4096` (tránh phạt exception vĩnh viễn); `GETTABLE/SETTABLE` dispatch theo tag khoá (int vs string); `OP_CLEANUP` no-op kiểu (sửa reject sai `07/10`) |
| E | ✅ xong | `CONCAT`, `DIV/DIVK`, `POW/POWK`, `MOD`, `IDIV`, bitwise 2 ngôi + `SHLI/SHRI`; helper `shiftLeft/Right`, `luaFloatMod`, `luaNumPow` |
| F | ⬜ chưa | Generic-for (`TFOR*`) |
| G | ⬜ chưa | Vararg đọc `...` |
| H | ⬜ chưa | Inline cache đa hình |
| I | ⬜ chưa | Mở rộng deopt/debug robustness |
| J | ⬜ chưa | Xác thực cuối |

**Bằng chứng (paired, core 8, `luava.jit.sync=true`):**
- JIT on/off (main-chunk loop giờ tier-up): arith **~5×**, table ops **~2.8×**,
  closures **~2×**, hash **~1.4×**, oop 1.35×; `while` **~23×**, `if`-in-loop
  **~12×**, single-invocation heavy loop **~5.6×**.
- Fuzz đối chiếu JIT on/off: FuzzC 868, FuzzE 1656, FuzzConcat 45, FuzzTL 13,
  FuzzVoid 8, FuzzSelf 6, FuzzClosure 8, FuzzImpure 10, FuzzTable 12 — **0
  mismatch**.
- **Prewarm cưỡng bức mọi proto hợp lệ** trên toàn bộ 30 suite PUC → 30/30
  PASS (JIT on, `luava.jit.sync=true`).
- 185 unit test + 30/30 suite PUC xanh (JIT cả on/off).
- vs LuaJ (paired): arith 8.3×, hash 1.96×, oop 1.18× thắng;
  table/sieve/pattern/closures ~1.0. Không task nào thua > 1.1×.

**Bài học đo lường:** đếm back-edge mỗi vòng lặp (dù có granularity) làm
`runLoop` chậm ~15–20% trên task top-level không hưởng lợi. Giải pháp cuối:
chỉ request compile **một lần tại `FORPREP`** khi trip count đã biết là lớn —
chi phí per-iteration bằng 0, vẫn phủ được loop trong hàm gọi một lần.

---

## 13. Tham chiếu

- `AGENTS.md` — chuẩn kỹ thuật, đạo đức test, kiến trúc table.
- `README.md` — mô tả engine, bảng so sánh (mục Hybrid tiered JIT).
- `docs/lua-5.4-reference-manual.md` — đặc tả.
- `benchmarks/harness/interleave.sh`, `Bench.java`, `LuaJBench.java` — harness.
- `/tmp/opencode/jitspike/` — spike translator fib (FINDINGS.txt).
