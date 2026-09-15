# Luava Roadmap — Đột phá bằng JIT lai (Hybrid Tiered JIT)

> **Mục tiêu tối thượng:** đánh bại hoặc hòa LuaJ 3.0.1 trên **cả 10 benchmark**,
> trong khi giữ **30/30 suite PUC Lua 5.4.9 + 111 unit tests xanh** và không
> bao giờ sửa `tests/lua-5.4.9-tests/`.
>
> **Tài liệu này thay thế** `DIFFICULTIES.md`, `OPTIMIZATION_PLAN.md`,
> `PERF_WALL.md` (đã xóa, nội dung hợp nhất vào đây). `PLANS.md` giữ lại làm
> hồ sơ thiết kế register-VM gốc + ghi chú non-goal §IX. `AGENTS.md` và
> `README.md` giữ nguyên.
>
> Cập nhật: 2026-09-14. Trạng thái: interpreter đã khai thác cạn codegen;
> đột phá tiếp theo bắt buộc là JIT.

---

## 0. TL;DR cho người vội

- Interpreter hiện tại: **4 task thắng LuaJ** (arith 0.74×, sieve 0.93×,
  coroutines 0.06×, hash 0.63×), **1 hòa** (table_ops 1.04×), **5 thua**
  (fib 1.82×, oop 1.18×, concat 1.76×, pattern 1.96×, closures 2.01×).
- Mọi task thua đều bị **call-tax** chi phối (đã chứng minh bằng floor probe:
  bỏ hết metadata vẫn 1.57× chậm hơn LuaJ).
- **Spike JIT đã chứng minh đột phá:** translator tự động (không hand-code)
  dịch bytecode Lua của fib sang **một method JVM**, đệ quy thành
  `INVOKESTATIC` → fib(35) = **185ms**. So cùng máy: interpreter ~5300ms,
  LuaJ ~2411ms, C Lua ~766ms. Tức **nhanh hơn LuaJ 13×, hơn cả C 4×**.
- Kế hoạch: **JIT lai theo tầng** — interpreter là mặc định + fallback; JIT
  chỉ biên dịch "kernel nóng" với guard kiểu + deopt; coroutine giữ nguyên
  interpreter (đừng phá thế thắng 16×).
- Lộ trình chia **8 giai đoạn** (Phase 0–7), mỗi phase có cổng kiểm soát
  (size/compile/correctness/perf) và điều kiện rollback.

---

## 1. Nguyên tắc bất di bất dịch (áp dụng cho MỌI phase)

### 1.1 Kỷ luật test (từ `AGENTS.md`)
- **Cấm** sửa/tamper/xóa/bỏ qua bất kỳ dòng nào trong `tests/lua-5.4.9-tests/`.
- **Cấm** hardcode kết quả giả, mock return, hay branch "để qua test".
- Tiến độ đo bằng **số suite pass tự nhiên** (30/30) + 111 unit tests.
- Mọi lỗi phải truy gốc theo tầng (Lexer/Parser/AST/Bytecode/VM/Stdlib) và
  sửa tại tầng đó. Không vá ngọn.
- Mỗi lần chạy suite phải có `timeout`; cấm `nohup` (làm `files.lua:762` fail giả).

### 1.2 Kỷ luật đo lường (đã trả giá để rút ra)
- **Máy drift ±10%.** Cấm đo naive before/after. Mọi claim perf = build 2 jar
  (pre/post), chạy **xen kẽ từng cặp** (PRE,POST,PRE,POST…), **≥7 cặp**,
  lấy median; **bar 3%** mới tính là thắng.
- **Pin core:** mọi benchmark dùng `taskset -c <core rảnh>` (đã chứng minh:
  không pin thì add-loop swing 855→1247ms; pin thì spread <11ms).
- **Ngoại lệ coroutine:** KHÔNG pin 1 core (bóp chết virtual-thread pool:
  297ms → 21s). Đo no-pin.
- Profiler: **async-profiler** (`-agentpath:libasyncProfiler.so=start,event=cpu`),
  KHÔNG dùng JFR `ExecutionSample` cho micro (safepoint bias — JFR báo
  `divideUnsigned` 15.5% nhưng async-profiler không thấy; đã chứng minh artifact).
- JIT nội tại: `-XX:+PrintCompilation`, `-XX:+PrintInlining`,
  `-XX:+UnlockDiagnosticVMOptions`; hằng số C2: `HugeMethodLimit=8000`,
  `MaxInlineSize/FreqInlineSize≈325`, `MaxRecursiveInlineLevel=1`.

### 1.3 Kỷ luật commit
- Commit theo batch, message kèm số liệu interleave.
- Revert nếu dưới bar hoặc gây regression; ghi lại lý do vào §7 (nhật ký thất bại).
- Không thêm file rác, không vanity code. Mọi thay đổi phải phục vụ logic/
  tương thích/perf đo được.

### 1.4 Ràng buộc nền tảng
- Java 21 LTS stock, **không preview, không JNI/FFM**.
- Không `System.out` trong runtime.
- Kiến trúc value: triple-stack (`long[] pStack`, `byte[] tStack`,
  `LuaValue[] oStack`) — giữ nguyên, JIT thao tác trực tiếp trên nó.

---

## 2. Hiện trạng & bằng chứng (baseline để đo tiến độ)

### 2.1 Bảng điểm (paired, best-of-N, cùng core)
| # | Task | Luava | LuaJ 3.0.1 | Tỷ lệ | Trạng thái |
|---|---|---|---|---|---|
| 01 | arith_loop | ~196ms | ~264ms | **0.74×** | THẮNG |
| 02 | fibonacci | ~4400ms (interp) / **~400ms (JIT)** | ~2410ms | 1.82× → **0.17× THẮNG** |
| 03 | table_ops | ~97ms | ~94ms | 1.04× | HÒA |
| 04 | string_concat | ~82ms | ~46ms | 1.76× | THUA |
| 05 | closures | ~93ms (interp) / **~66ms (JIT)** | ~55ms | 2.01× → **1.15× (sát nút)** |
| 06 | coroutines | ~282ms | ~4415ms | **0.06×** | THẮNG 16× |
| 07 | hash_table | ~114ms | ~181ms | **0.63×** | THẮNG |
| 08 | oop_metatables | ~701ms | ~594ms | 1.18× | THUA |
| 09 | string_pattern | ~147ms | ~75ms | 1.96× | THUA |
| 10 | sieve | ~285ms | ~305ms | **0.93×** | THẮNG |

Fibonacci tuyệt đối: **7930ms (đầu dự án) → ~4400ms**.

### 2.2 Bằng chứng call-tax là gốc duy nhất của khoảng cách
| Tầng | Luava | LuaJ | C Lua |
|---|---|---|---|
| dispatch loop rỗng (50M) | **5.4ns/iter** | 11.0ns | 4.0ns |
| add int (50M) | 25.6ns | 24.7ns | 7.6ns |
| gọi `f(x)=x+1` (20M) | 116ns/call | 63ns | 22.5ns |
| **thuế gọi (call−add)** | **~90ns** | ~38ns | ~0 |

- Luava **thắng dispatch**, **hòa số học**, **thua ở thuế gọi**.
- **Floor probe:** bỏ gần hết metadata frame vẫn 1805ms vs LuaJ 1152ms = 1.57×.
  → trần của mọi tối ưu interpreter là ~20%, không đủ hòa.
- Allocation trên đường gọi = **0** → không phải GC, mà là store/dispatch.

### 2.3 Vì sao LuaJ nhanh ở call-heavy
LuaJ (`LuaC` interpreter) biên dịch **mỗi closure thành một method Java riêng**
(`LuaClosure.execute`), C2 inline thẳng call site. Luava dùng **một `runLoop`
chung + bảng `ctx` trung gian**, mỗi call phải reload trạng thái từ heap →
C2 không fusion được. Đây là khác biệt kiến trúc, không phải micro-opt.

### 2.4 Bằng chứng đột phá: spike JIT (2026-09-14)
- `/tmp/opencode/jitspike/JitSpike.java` (ASM 9.7 + `defineHiddenClass`).
- Là **translator**, không hand-code: đọc `LuaProto` thật của fib (12 lệnh:
  `LEI/JMP/RETURN1/GETUPVAL/SUBK/ADD/CALL/RETURN0`), sinh một method JVM
  static `long exec(...)`, đệ quy self-call → `INVOKESTATIC`, load bằng
  hidden class.
- Kết quả fib(35): **~185ms** (interpreter ~5300ms, LuaJ ~2411ms, C ~766ms).
- Ý nghĩa: "một method JVM per Lua function" cho C2 inlining là **thật và
  cực mạnh**. Đây là con đường duy nhất để thắng call-heavy.

---

## 3. Vấn đề cần giải: non-goal §IX và cách giải

`PLANS.md §IX` tuyên bố phát JVM bytecode là **anti-pattern** vì 3 lý do.
Kế hoạch mới thừa nhận cả 3 và có đối sách:

| Rủi ro §IX | Bản chất | Đối sách trong thiết kế mới |
|---|---|---|
| **Metaspace leak** | mỗi closure = 1 class → OOM Metaspace | `defineHiddenClass` (JEP 371): lớp ẩn không bị classloader giữ, GC dọn khi closure chết. + **code cache có giới hạn LRU** (chỉ giữ N class nóng nhất). + chỉ JIT kernel nóng, không JIT mọi closure. |
| **Coroutine yield bất khả** | method JVM không yield giữa frame | **JIT không bao giờ biên dịch hàm có khả năng yield.** Hàm gọi `coroutine.yield` (trực tiếp/gián tiếp) chạy interpreter. Coroutine giữ thế thắng 16×. |
| **Giới hạn 64KB/method** | hàm Lua khổng lồ | Chỉ JIT proto ≤ ngưỡng opcode (vd 200 lệnh); proto lớn hơn ở lại interpreter. |

**Quyết định kiến trúc:** JIT là **tầng tăng tốc tùy chọn**, interpreter vẫn là
engine mặc định và fallback. Không có JIT thì hành vi y hệt hiện tại.

---

## 4. Kiến trúc đích: Hybrid Tiered JIT

```
Lua source ──Lexer/Parser──► AST ──BytecodeCompiler──► LuaProto
                                                          │
                                          ┌───────────────┴────────────────┐
                                          ▼                                ▼
                                  BytecodeVM (interpreter)          JitCompiler (ASM)
                                   - mặc định                        - chỉ proto nóng
                                   - fallback                        - int/float chuyên biệt
                                   - coroutine                       - guard + deopt
                                   - proto lớn                       - hidden class
                                          ▲                                │
                                          └────── deopt / tier-down ───────┘
```

### 4.1 Mô hình thực thi JIT
- **Một method JVM per Lua proto** (không phải per closure instance):
  `static long/int/LuaValue exec(Object[] upvals, long[] p, byte[] t, LuaValue[] o, int base)`.
- Hàm số học thuần (int) → chữ ký trả `long` (không box). Hàm trả object →
  trả `LuaValue`. Chọn theo type-inference tĩnh ở frontend (`Typer` đã có).
- **Guard kiểu:** tại điểm cần giá trị, đọc `tStack[idx]`; nếu tag không như
  giả định → `deopt` (throw một signal đặc biệt, unwound về interpreter với
  `pc` chính xác).
- **Call-site:** giai đoạn đầu chỉ xử lý self-recursion (`INVOKESTATIC` trực
  tiếp, đã chứng minh). Sau mở rộng ra monomorphic call-site → `MethodHandle`
  cache; polymorphic → `invokedynamic` + PIC.

### 4.2 Hotness & tier-up
- Mỗi `LuaProto` có bộ đếm gọi/backedge (`int`, plain field). Khi vượt ngưỡng
  (vd 1000) và proto "JIT-able" (không yield, ≤ ngưỡng lệnh) → đưa vào hàng
  đợi biên dịch (1 luồng nền, hoặc biên dịch đồng bộ lần đầu cho đơn giản).
- Biên dịch xong → lưu `MethodHandle` vào `LuaProto.jitCode` (volatile).
  `OP_CALL` kiểm tra `jitCode != null` → gọi JIT thay vì push frame.
- Deopt → tăng bộ đếm, tạm khóa proto (đừng JIT lại liên tục).

### 4.3 Tương tác coroutine
- `LuaProto.mayYield` đánh dấu tĩnh: có lệnh gọi tới hàm có thể yield
  (`coroutine.*`, hoặc hàm chưa xác định). Gọi qua ranh giới yield luôn đi
  interpreter.
- Nếu một hàm vừa nóng vừa `mayYield` → **không JIT** (bảo toàn thế thắng).

---

## 5. Lộ trình 8 giai đoạn

Mỗi phase có: **việc**, **file**, **cổng (gates)**, **rủi ro/rollback**.
Cổng chuẩn dùng chung:
- **G-SIZE:** method sinh ra / `runLoop` không vượt ngưỡng đã định; in ra log.
- **G-COMPILE:** `-XX:+PrintCompilation` xác nhận code JIT + interpreter được C2.
- **G-CORRECT:** 30/30 suite + 111 unit tests xanh; fuzz đối chiếu stock Lua.
- **G-PERF:** interleave ≥7 cặp pinned, bar 3%, không regression task khác.

---

### Phase 0 — Đóng băng & dựng giàn đo (0.5 ngày) — ✅ XONG (2026-09-15)

**Việc:**
1. Dọn file md thừa (đã xóa `DIFFICULTIES.md`, `OPTIMIZATION_PLAN.md`,
   `PERF_WALL.md`); giữ `plan.md` (file này), `PLANS.md`, `AGENTS.md`,
   `README.md`, `docs/`.
2. Backup harness tạm (`/tmp/opencode/prof/Bench*.java`,
   `/tmp/opencode/vs/LuaJ*`, `aggsample.py`, jars A/B, `jitspike/`) vào
   `benchmarks/harness/` (untracked, không commit) để tái lập interleave.
3. Viết script `benchmarks/harness/interleave.sh`:
   nhận 2 jar + task list → chạy 7 cặp đảo thứ tự, in median ratio.
4. Ghi baseline hiện tại (§2.1) vào `benchmarks/harness/BASELINE.txt`.

**File:** `benchmarks/harness/*` (untracked), `plan.md`.

**Gates:** G-CORRECT (30/30 + 111). Harness tái lập được số §2.1 ±3%.

**Rollback:** không có (chỉ dọn + đo).

### Phase 1 — Hạ tầng JIT (2–3 ngày) — ✅ XONG (2026-09-15)

**Việc:**
1. Thêm dependency `org.ow2.asm:asm:9.7` vào `pom.xml` (+
   `maven-shade-plugin` gộp asm, relocate `org.luava.shaded.asm`; jar
   439KB → 581KB, chạy độc lập).
2. `runtime/jit/JitCode.java`: wrapper `MethodHandle` + metadata (proto gốc,
   signature, trạng thái khóa/deopt).
3. Hidden class qua `MethodHandles.Lookup.defineHiddenClass` (NESTMATE)
   trong `JitCompiler` (gộp vai `JitClassLoader`).
4. `runtime/jit/JitCodeCache.java`: LRU giới hạn 512 class — chống Metaspace;
   evict thì clear `proto.jitCode`.
5. Feature flag `LuaState.ENABLE_JIT` (mặc định **false**; bật bằng
   `-Dluava.jit=true`), `JIT_HOT_THRESHOLD=50`.

**File:** `pom.xml`, `runtime/jit/JitCode.java`, `JitCodeCache.java`,
`JitCompiler.java`, `LuaState.java` (flag), `LuaProto.java`
(`jitCode`/`jitDisabled`/`mayYield`/`hotCount`).

**Gates:** G-CORRECT với JIT off (build + suite xanh). ✅ 30/30 + 111.
G-SIZE (không đụng runLoop — chỉ thêm `tryJitCall` ngoài loop).

**Rollback:** gỡ dependency + package jit; không ảnh hưởng engine.

---

### Phase 2 — Translator lõi: int chuyên biệt + guard + deopt — ✅ XONG v1 (2026-09-15)

Đây là **trái tim** của dự án. Bắt đầu từ spike, mở rộng thành translator
tổng quát cho tập opcode số học.

**Việc:**
1. `runtime/jit/LuaToJvmTranslator.java`: duyệt `LuaProto.code`, phát bytecode
   ASM cho từng opcode. Bảng opcode Phase 2 (số học/branch/return/upvalue):
   `MOVE, LOADI, LOADF, LOADK, LOADNIL, LOADTRUE/FALSE, GETUPVAL, SETUPVAL,
   ADD/SUB/MUL/DIV/IDIV/MOD, ADDI, ADDK/SUBK/MULK/DIVK/IDIVK/MODK,
   LTI/LEI/GTI/GEI/EQI/EQ/LT/LE, JMP, TEST/TESTSET, FORPREP/FORLOOP,
   RETURN0/RETURN1/RETURN, GETTABUP/GETFIELD/GETI/GETTABLE (read-only trước),
   CALL (self-recursion)`.
2. **Type specialization:** hai biến thể hàm: `int-specialized` (mọi operand
   giả định TYPE_INT, trả `long`) và `generic` (dùng LuaValue, gọi helper VM).
   Chọn theo `Typer` tĩnh; mặc định generic.
3. **Guard + deopt:** `runtime/jit/DeoptSignal.java` (extends `Error`, cold).
   Tại mỗi đọc `pStack[idx]`, guard `tStack[idx]==TYPE_INT`; sai → ném
   `DeoptSignal` mang `pc`. `BytecodeVM` bắt signal này, khôi phục frame tại
   `pc` và chạy tiếp interpreter.
4. **Self-recursion & direct calls:** `OP_CALL` tới closure có cùng proto →
   `INVOKESTATIC`. Còn lại: gọi `BytecodeVM` helper (fallback trong code JIT).
5. **Bảng deopt metadata:** mỗi vị trí guard lưu `(pc, liveRegs)` để tái lập
   chính xác trạng thái khi deopt.

**File:** `runtime/jit/LuaToJvmTranslator.java`, `DeoptSignal.java`,
`runtime/bytecode/BytecodeVM.java` (bắt DeoptSignal tại OP_CALL/runLoop),
`runtime/bytecode/LuaProto.java` (`jitCode`, `hotCount`, `mayYield`).

**Gates (kết quả 2026-09-15):**
- G-CORRECT: ✅ 30/30 suite + 111 unit tests xanh **cả hai chế độ**
  (JIT-off mặc định; JIT-on qua `_JAVA_OPTIONS=-Dluava.jit=true`, và ép
  `JIT_HOT_THRESHOLD=1` để 21 proto trong suite thực sự compile — 4 deopt
  fallback đúng). Fuzz 20 kernel biên (float/missing/string args, pcall,
  closures) byte-identical on/off.
- G-PERF: ✅ fib **4943ms → 402ms (12.3×)**, LuaJ 2460ms → **thắng 6×**;
  9 task còn lại trong noise (±3%, đã xác minh lại closures/hash bằng 10 cặp).
- G-COMPILE: ✅ async-profiler thấy `Gen$*.exec` đệ quy C2.
- **Gate quyết định: VƯỢT** (400ms ≤ 500ms; mục tiêu 200ms còn hở — tối ưu
  GETUPVAL/CALL guard là dư địa Phase 3).

Thiết kế v1 đã chốt (khác dự thảo ban đầu ở 2 điểm, đều vì đơn giản + đúng):
- Deopt = **restart toàn bộ call bằng interpreter** (thay vì resume tại pc),
  an toàn vì subset thuần khiết (không table/global/upvalue-write).
- Chỉ JIT proto thỏa subset số-nguyên hẹp; còn lại interpreter nguyên vẹn.

**Rủi ro/rollback:** phức tạp deopt; nếu deopt không tái lập đúng → sai ngữ
nghĩa. Giảm thiểu: khởi đầu chỉ int, guard dày, test fuzz. Rollback = flag off.

---

### Phase 3 — Call-site tổng quát: PIC & invokedynamic — ✅ XONG v1 (2026-09-15, monomorphic, chưa invokedynamic)

**Việc (đã làm):**
1. Call-site monomorphic 3 tầng trong code JIT: hoisted self (phân loại ở
   entry, không re-check), self-direct `INVOKESTATIC`, callee proto khác có
   `jitCode && pure` qua `JitRuntime.invoke` helper. Callee lạ → deopt.
   (Bài học: hoisting "mọi callee cùng proto" SAI với `add` — sửa thành
   classify + slow path re-validate.)
2. `SETUPVAL` leaf + lane int cho upvalue (`Upvalue.isClosedInt/
   getClosedInt/setClosedInt`; open/non-int → generic/deopt).
3. Sửa gốc `Upvalue.setValue` giữ tag nguyên thủy (int/float) thay vì ép
   OBJECT — xóa cả một lớp deopt ở biên tier-up (closure tạo trước khi
   compile).
4. Quy tắc an toàn: proto có CALL thì phải pure; impure leaf thì
   `numParams==0` (resume-at-pc không bị frame setup clobber).

**Kết quả:** closures **~80ms → ~65ms (thắng 1.23× nội bộ, chỉ còn thua
LuaJ 1.15×)**; fib giữ ~400ms; 8 task còn lại trong noise. 30/30 + 111 xanh
cả hai chế độ (ép threshold=1: 24 proto compile, deopt fallback đúng);
fuzz 35/35 đồng nhất.

**Chưa làm (dời):** `invokedynamic` + PIC đa hình thật, megamorphic
fallback — chỉ cần khi oop/metatable vào diện (Phase 5). TAILCALL trong JIT
hiện từ chối conservative (compiler biến `return f()` thành TAILCALL).

**File:** `runtime/jit/JitRuntime.java`, `LuaToJvmTranslator.java`,
`Upvalue.java` (lane int).

**Gates:** ✅ G-CORRECT (metamethod/`__call`/Java/coroutine đều deopt về
interpreter — subset không chứa chúng). G-PERF: closures hòa→thắng nội bộ.

**Rollback:** flag; call-site luôn có đường interpreter.

---

### Phase 4 — Hotness, tier-up, tương tác VM (3–5 ngày)

**Việc:**
1. Bộ đếm nóng trong `runLoop` cho `OP_CALL`/`OP_FORLOOP` backedge; tier-up
   khi vượt ngưỡng.
2. Chọn ứng viên JIT: proto ≤ 200 lệnh, không `mayYield`, không chứa lệnh
   chưa hỗ trợ.
3. `LuaState.ENABLE_JIT` mặc định **true** sau khi Phase 2–3 xanh; nhưng
   vẫn cho phép tắt để so sánh.
4. Chống JIT storm: proto deopt > K lần → đưa vào danh sách "never-JIT".
5. Đo overhead khi bật JIT trên task không hưởng lợi (đảm bảo không regression).

**Gates:** G-CORRECT (suite chạy cả 2 chế độ JIT on/off). G-PERF: không task
nào regression > 3%.

**Rollback:** tắt flag.

---

### Phase 5 — Mở rộng độ phủ opcode (1–2 tuần)

**Việc:** thêm dần, mỗi opcode một commit + fuzz đối chiếu:
- Table: `NEWTABLE, SETTABLE, SETI, SETFIELD, SETTABUP, SETLIST, SELF, GETTABUP`.
- Metamethod: `MMBIN/MMBINI/MMBINK`, `UNM, BNOT, NOT, LEN, CONCAT`.
- Varargs: `VARARG, VARARGPREP`.
- Generic loop: `TFORPREP, TFORCALL, TFORLOOP`.
- `CLOSE, TBC` (to-be-closed) — hoặc cấm JIT proto chứa chúng.
- `EXTRAARG`, `LOADKX`.

**Gates mỗi opcode:** G-CORRECT + fuzz; G-PERF chỉ ghi nhận (không bắt buộc
mỗi opcode phải nhanh hơn, nhưng tổng thể sau phase phải cải thiện).

**Rollback:** cờ từng opcode trong translator.

---

### Phase 6 — Ngữ nghĩa debug/error dưới JIT (1 tuần)

Mấu chốt để giữ 30/30: `debug.getinfo`, `debug.traceback`, hook line/call/
return, `error` position phải đúng khi chạy code JIT.

**Việc:**
1. **Line info:** code JIT cập nhật frame line khi cần (chỉ bật khi
   `HOOKS_ARMED` hoặc có error handler — gate bằng cờ, không phải mỗi lệnh).
2. **Frame mirror:** khi JIT chạy, đồng bộ `vmPcMirror`/`CallStack.Frame` tối
   thiểu đủ cho `getinfo`/traceback. Cân nhắc: chỉ JIT proto "không cần debug
   line chính xác" — nhưng suite đòi chính xác, nên phải làm đúng.
3. **Error trong JIT:** `LuaException` phát từ code JIT mang `pc`/line đúng;
   `decorateFault` chạy như interpreter.
4. **Hooks:** nếu `HOOKS_ARMED` (line/call/return), tạm hạ cấp proto về
   interpreter (đơn giản, đúng) — hoặc gate hook trong code JIT.

**Gates:** G-CORRECT toàn bộ suite, đặc biệt `db.lua`, `errors.lua`,
`events.lua`, `locals.lua`, `attrib.lua`. Fuzz hook.

**Rollback:** nếu không giữ được 30/30, JIT chỉ chạy khi KHÔNG có debug hook
và vẫn phải đúng traceback cơ bản.

---

### Phase 7 — Gia cố & xác thực hiệu năng cuối (1 tuần)

**Việc:**
1. Chạy toàn bộ interleave Luava-JIT vs LuaJ trên 10 task; mục tiêu **tất cả
   ≤ 1.03×** (trừ các task vốn đã thắng).
2. Stress: 2000 coroutine churn, deep recursion 8000+, GC pressure — JIT on.
3. Kiểm Metaspace/RSS với code cache giới hạn.
4. Kiểm `string.dump`/`load` round-trip khi proto có `jitCode` (phải reset
   jitCode sau undump).
5. Cập nhật `README.md` (bảng so sánh + mô tả kiến trúc lai), giữ `PLANS.md`
   làm hồ sơ gốc với ghi chú "JIT đã được mở lại theo plan.md".
6. Ghi toàn bộ nhật ký thắng/thua vào §7 của plan này.

**Gates:** G-CORRECT + G-PERF tổng thể. Nếu một task vẫn > 1.03× sau JIT →
phân tích async-profiler trên code JIT, lặp micro-opt có đo.

**Rollback:** `ENABLE_JIT=false` là an toàn tuyệt đối.

---

## 6. Ước lượng tổng thời lượng

| Phase | Nội dung | Effort | Lũy kế |
|---|---|---|---|
| 0 | Đóng băng & harness | 0.5 ngày | 0.5 |
| 1 | Hạ tầng JIT | 2–3 ngày | ~3.5 |
| 2 | Translator lõi + guard/deopt | 1–2 tuần | ~2.5 tuần |
| 3 | PIC/invokedynamic | 1–2 tuần | ~4.5 tuần |
| 4 | Hotness/tier-up | 3–5 ngày | ~5.5 tuần |
| 5 | Mở rộng opcode | 1–2 tuần | ~7.5 tuần |
| 6 | Debug/error semantics | 1 tuần | ~8.5 tuần |
| 7 | Gia cố & xác thực | 1 tuần | **~9.5 tuần** |

Đây là **dự án ~2–2.5 tháng** nếu làm tuần tự. Có thể rút ngắn bằng cách
chỉ nhắm các kernel số học (fib/arith/sieve) trước và bỏ Phase 5 rộng nếu
mục tiêu chỉ là "hòa call-heavy cơ bản".

---

## 7. Nhật ký thất bại (đọc trước khi đề xuất lại — tiết kiệm thời gian)

### 7.1 Interpreter đã thử & revert (có bằng chứng, bar 3%)
| Thử nghiệm | Kết quả | Nguyên nhân thất bại |
|---|---|---|
| Lazy callName (P1b) | hòa tuyệt đối | Name đã cache 256-entry, ~2%; machinery phức tạp vô ích |
| Cache callName external (P2e) | 1/3 (dưới bar) | Walk gốc chỉ ~20ns |
| SELF fast lane (P2f) | 1/3 thua | OOP dùng metatable → lane không bao giờ cháy |
| Method PIC 4-entry | ~2.3% | rawget 19ns ăn nửa savings |
| MERGE CallInfo→Frame | 0 đến −10% | C2 không inline qua boundary mới |
| RECUR Java-recursive OP_CALL | −14% | `runLoop 7634B hot method too big`, không inline |
| Inline SUBK vào dispatch | fib −2.5% | runLoop 6907→7128B vượt mục tiêu ≤7000 |
| Bỏ caller-frame stamp | 29/30 fail | `errors.lua:399` cần line caller |
| Gate `ctx.oldpc` khi hooks off | 29/30 fail | hỏng line-hook |
| Tắt `vmPcMirror` | sieve +4–5% | sai `debug.getinfo().currentline` |
| Lazy 2 List của `LuaFunction` | closures +3%, oop −5% | đổi class-shape → C2 compile executeCallOp tệ; net âm |
| Pattern ASCII + lazy Capture | 4/8, 5/8 (dưới bar) | per-match plumbing chi phối |

**Bài học lớn:** mọi micro-opt quanh call/table/frame đã cạn. Hai lần exp2reg
fail 12 suite vì thiếu loại trừ (call/vararg/CONCAT/AND/OR ghi nhiều register).

### 7.2 Codegen gaps đã đóng (so với PUC `luac`, mạch THÀNH CÔNG)
Đây là mạch hiệu quả nhất của interpreter — học PUC từng byte:
| Commit | Nội dung | Kết quả |
|---|---|---|
| `bd734d6` | literal RHS dạng RK const | sieve −24% (hòa LuaJ) |
| `92923f2` | K-form opcodes (MULK...) | table_ops −20%, fib −7.5% |
| `7afd64f` | VM implement LTI/LEI/GTI/GEI + immediate | fib −4.5% |
| `1064dc5` | `if` compare+JMP trực tiếp | fib −14.5% |
| `09bc3ac` | `while` compare+JMP | while −17% |
| `92b2a1d` | `repeat` compare+JMP | repeat −33% |
| `29dd6a9` | exp2reg `acc = acc + i` → ADD trực tiếp | arith −42% |
| `a054de9` | immediate compare trong điều kiện | fib −8% |

**Kết luận:** Luava giờ **ít lệnh tĩnh hơn PUC ở mọi task** (fib 29/32,
oop 86/94...). Mạch codegen đã cạn.

### 7.3 Câu hỏi mở (chưa giải, đừng tưởng đã hiểu)
1. `closeTbc` 1.4% self ở oop dù không có tbc var — ai vào?
2. P1a cho fib +5% không rõ cơ chế (nghi inline side-effect).
3. `BASIC_METATABLES` static toàn JVM — ảnh hưởng đa-state? chưa đo.
4. `LuaValue.BASIC_METATABLES` ô nhiễm giữa concurrent `LuaState`.
5. `calls.lua` flake ~15% (`LuaUnwindException: null`) — pre-existing, chưa sửa.

---

## 8. Biện pháp đối phó rủi ro chính

| Rủi ro | Mức | Đối sách |
|---|---|---|
| Deopt sai → lệch ngữ nghĩa | Cao | guard dày + fuzz đối chiếu stock Lua mỗi opcode; lộ trình int trước |
| Metaspace leak | Cao | hidden class + LRU code cache + chỉ JIT proto nóng |
| Coroutine yếu đi | Cao | cấm JIT proto `mayYield`; test 2000 churn |
| Debug/traceback sai | Cao | Phase 6 riêng; gate HOOKS_ARMED; suite db/errors/events |
| C2 không inline code JIT | Trung | đo PrintInlining; giữ method JIT nhỏ gọn |
| JIT chậm hơn interpreter task nhỏ | Trung | tier-up theo ngưỡng; đo overhead |
| Build phức tạp (asm dep) | Thấp | shade asm vào jar; test `mvn package` |
| Mất thế thắng dispatch/coroutine | Trung | interpreter giữ nguyên, JIT chỉ tăng tốc |

---

## 9. Điều KHÔNG làm

- Không sửa `tests/lua-5.4.9-tests/**`.
- Không hardcode/mock để qua test.
- Không bật JIT mặc định cho tới khi Phase 2–3 qua gate.
- Không JIT hàm có thể yield (bảo toàn coroutine).
- Không bỏ metadata debug để lấy tốc độ (đi ngược AGENTS.md).
- Không thêm file rác/vanity.

---

## 10. Tham chiếu
- `AGENTS.md` — chuẩn kỹ thuật, đạo đức test, kiến trúc table.
- `PLANS.md` — hồ sơ register-VM gốc; §IX non-goal JIT (đã mở lại có kiểm soát
  trong plan này), §XI gates beat-LuaJ.
- `README.md` — mô tả engine, bảng so sánh.
- `docs/lua-5.4-reference-manual.md` — đặc tả.
- `/tmp/opencode/jitspike/` — spike JIT (translator fib, FINDINGS.txt).
- `benchmarks/lua/01..10` — bộ benchmark (untracked).
