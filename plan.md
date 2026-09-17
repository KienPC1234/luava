# Luava Roadmap — Đột phá bằng JIT lai (Hybrid Tiered JIT)

> **Mục tiêu tối thượng:** đánh bại hoặc hòa LuaJ 3.0.1 trên **cả 10 benchmark**,
> trong khi giữ **30/30 suite PUC Lua 5.4.9 + 150 unit tests xanh** và không
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
- Tiến độ đo bằng **số suite pass tự nhiên** (30/30) + 150 unit tests.
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
| 02 | fibonacci | ~4400ms (interp) / **~254ms (JIT)** | ~2410ms | 1.82× → **0.11× THẮNG 9.5×** |
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
- **G-CORRECT:** 30/30 suite + 150 unit tests xanh; fuzz đối chiếu stock Lua.
- **G-PERF:** interleave ≥7 cặp pinned, bar 3%, không regression task khác.

---

### Phase 0 — Đóng băng & dựng giàn đo (0.5 ngày) — ✅ XONG (2026-09-15)

**Việc:**
1. Dọn file md thừa (đã xóa `DIFFICULTIES.md`, `OPTIMIZATION_PLAN.md`,
   `PERF_WALL.md`); giữ `plan.md` (file này), `PLANS.md`, `AGENTS.md`,
   `README.md`, `docs/`.
2. Backup harness tạm (`/tmp/opencode/prof/Bench*.java`,
   `/tmp/opencode/vs/LuaJ*`, `aggsample.py`, jars A/B, `jitspike/`) vào
   `benchmarks/harness/` (số liệu/jar tạm không commit; riêng
   `interleave.sh`, `Bench.java`, `LuaJBench.java`, `BASELINE.txt` được
   track như hạ tầng đo lường).
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
- G-CORRECT: ✅ 30/30 suite + 150 unit tests xanh **cả hai chế độ**
  (JIT-off mặc định; JIT-on qua `_JAVA_OPTIONS=-Dluava.jit=true`, và ép
  `JIT_HOT_THRESHOLD=1` để 21 proto trong suite thực sự compile — 4 deopt
  fallback đúng). Fuzz 20 kernel biên (float/missing/string args, pcall,
  closures) byte-identical on/off.
- G-PERF: ✅ fib **4943ms → 402ms (12.3×)** rồi **→ 254ms (19.5×)** sau
  tối ưu `execInner` (bỏ prologue `getValue`-upvalue-mở ~28% theo
  async-profiler leaf); LuaJ 2460ms → **thắng 9.5×**; 9 task còn lại trong
  noise (±3%, đã xác minh lại closures/hash bằng 10 cặp).
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

### Phase 4 — Hotness, tier-up, tương tác VM — ✅ XONG v1 (2026-09-15, trừ default-true)

**Việc (đã làm):**
1. Bộ đếm nóng `hotCount` trong `executeCallOp`; tier-up ở ngưỡng 50.
2. Chọn ứng viên: `analyze()` (≤200 lệnh, không mayYield, subset thuần).
3. `ENABLE_JIT` **mặc định true** (2026-09-15, sau khi mọi gate xanh cả
   hai chế độ + fuzz 63/63 + stress + RSS bounded): opt-out bằng
   `-Dluava.jit=false`. Compile **nền** (daemon `luava-jit`, queue collapse
   trùng, `jitQueued`) để request nóng không trả phí compile;
   `-Dluava.jit.sync=true` cho đo đạc đơn định (harness dùng).
4. Chống JIT storm: deopt > 8 → clear + `jitDisabled`; compile fail →
   `jitDisabled` ngay (tránh enqueue lặp); cache LRU 512.
5. Prewarm API `JitCompiler.prewarm(closure)` cho server (compile trước
   khi nhận traffic).
6. Overhead khi JIT bật trên task không hưởng lợi: interleave base-vs-JIT
   7 cặp — mọi task trong noise, không regression.

**Chưa làm:** default true (chờ Phase 5–6); backedge counter cho loop
(ít giá trị khi loop nằm ở main chunk lạnh).

**Gates:** G-CORRECT (suite chạy cả 2 chế độ JIT on/off). G-PERF: không task
nào regression > 3%.

**Rollback:** tắt flag.

---

### Phase 5 — Mở rộng độ phủ opcode — ✅ XONG v1 (2026-09-15: table R/W + NEWTABLE)

**Việc (đã làm):**
- Nền tảng bắt buộc trước: **resume-at-pc thật** — frame push trước,
  `tryJitCall` 3-trạng thái (1=xong frameless, 2=resume tại `jitResumePc`,
  0=chạy từ đầu); nested deopt convert về call-pc của frame hiện tại qua
  try/catch trong code JIT. Bắt được **bug fusion+resume** (skip store +
  resume tại CALL = thanh ghi stale → stress deep-recursion fail) nhờ
  `LuavaStressTest` với JIT bật; sửa bằng resume fused-CALL tại def-pc và
  chỉ skip store khi proto pure.
- READ: `GETTABUP/GETTABLE/GETI/GETFIELD` — guard table + metatable-null,
  mirror đúng fast lane interpreter (`rawgetInt`/`get`), còn lại deopt.
  Bắt được **bug stack imbalance** (guard ăn mất ref) nhờ probe dịch trực
  tiếp + ASM `COMPUTE_FRAMES`.
- WRITE: `SETTABUP/SETTABLE/SETI/SETFIELD` (mirror fast lane, deopt nếu
  metamethod), `NEWTABLE` (hằng số qua `self.proto.constants`, không đổi
  signature), `EXTRAARG` no-op.
- Quy tắc giữ nguyên: có CALL thì phải pure; callee JIT phải pure;
  TAILCALL vẫn từ chối.
- **Revert `OP_CLOSURE` khỏi JIT** (đo paired: closures 66ms → 76ms):
  body toàn allocation, JIT không bớt việc nào mà thêm entry cost
  (helper tốn 2 ThreadLocal lookup mà interpreter không cần). Factories ở
  interpreter. Ghi vào §7.
- **Fix `closeUpvalues` ở JIT return** (`closeOnJitReturn` trong
  `tryJitCall`): upvalue mở của caller có thể alias vùng callee; thiếu nó
  là bug hỏng dữ liệu (không chỉ perf).

**Kết quả:** 30/30 + 111 xanh cả hai chế độ (ép threshold=1: **43 proto**
compile); fuzz **56/56** (thêm table-write nóng, `__newindex`, string key,
NEWTABLE); fib ~264ms, closures ~65ms, các task khác trong noise.

**Chưa làm (dời):** `SELF`, varargs, generic loop, `CLOSE/TBC`,
`LOADKX` — cần khi oop/metatable vào diện. `CONCAT`/`MMBIN*` ở interpreter.

### Phase 5 v2 — opcode mở rộng + TAILCALL (2026-09-15)
`UNM/BNOT` (int), `NOT` (truthiness thuần), `LEN` (table plain + string,
`__len` deopt), `SETLIST` số lượng cố định (helper `setList`, `EXTRAARG`
tính sẵn), `LOADNIL/TRUE/FALSE/CLEANUP/LOADF` lanes, `MUL`, `RETURN B==2`,
`TAILCALL` (shift args + reuse window + return trực tiếp, cùng luật
pure/callee-pure; fused/general 3 tầng như CALL). Bắt 2 bug: descriptor
`setList` thừa int (ASM verify) và cổng `JitCompiler` vẫn cấm TAILCALL từ
Phase 2. Scoreboard: **6 thắng / 1 hòa (closures) / 3 thua**.

**Gates mỗi opcode:** ✅ G-CORRECT + fuzz (56/56); G-PERF chỉ ghi nhận
(table-write kernel ~10x nội bộ, tổng thể không regression).

**Rollback:** cờ từng opcode trong translator (`analyze`).

### Phase 5 v3 — phủ K-form + vòng lặp `for` số (2026-09-15, hậu audit)
Audit "JIT khó dùng" phát hiện hai lỗ hổng phủ lớn:
1. **Thiếu K-form** `ADDK/MULK/IDIVK/MODK/BANDK/BORK/BXORK` — compiler sinh
   chúng cho `x*2`, `x//3`, `x%7`... nên đa số kernel số không JIT.
2. **Thiếu `FORPREP`/`FORLOOP`** — mọi vòng lặp `for` số (kể cả trong hàm)
   không JIT; main-chunk loop cũng không tăng hotness.

**Đã làm:** emit `emitArithK` (LAND/LOR/LXOR/LADD/LSUB/LMUL), `emitIdivK`/
`emitModK` (`Math.floorDiv`/`floorMod` đúng ngữ nghĩa âm), `emitForPrep`/
`emitForLoop` (unsigned trip-count + `divideUnsigned`, mirror `doForPrep`/
`OP_FORLOOP`; dùng thuần stack tránh `VerifyError` do tranh scratch
local 6/8). `analyze` từ chối proto chứa K-form hằng không phải
`LuaInteger` (và `DIVK/POWK` luôn float) → giữ subset int. Sửa luôn
`prewarm` no-op khi state tắt JIT.

**API:** `LuaState.jitEnabled(Boolean)` / `isJitEnabled()` (per-state,
`null` = theo global `ENABLE_JIT`); `JitCompiler.prewarm(LuaFunction)`
overload + `prewarm(closure, state)`.

**Kết quả:** int for-loop và K-form kernel giờ compile (kiểm bằng cache
size); `JitCoverageTest` 10 test so sánh JIT on/off + giá trị đối chiếu
stock; fuzz loop 200 case byte-identical. 30/30 + 123 xanh. Float loop,
`while`/`repeat` vẫn interpreter (chủ ý — cần generic loop state).

---

### Phase 6 — Ngữ nghĩa debug/error dưới JIT — ✅ XONG audit (2026-09-15)

Thiết kế hiện tại né toàn bộ vấn đề thay vì vá từng cái, và đã kiểm chứng:
1. **Line info/frame:** JIT frameless (không push `CallStack` frame) nhưng
   mọi lỗi Lua đều deopt trước khi phát sinh (guard tag/metatable chạy
   trước op), nên interpreter dựng frame + line chính xác. `getinfo` trong
   JIT không thể xảy ra (subset không gọi ra ngoài trừ callee pure).
2. **Error:** `LuaException` rethrow nguyên (không nuốt); deopt resume giữ
   mọi side-effect đã commit đúng 1 lần.
3. **Hooks/timeout:** `HOOKS_ARMED` hoặc `loopGuard != null` → bỏ qua JIT
   hoàn toàn (đo ở entry, hooks không thể bật giữa chừng vì JIT không gọi
   ra ngoài).
4. **Bằng chứng:** fuzz pcall/traceback/getinfo/hook/coroutine/error-đệ-quy
   đồng nhất on/off; `errors.lua`, `db.lua` xanh ở threshold=1 (JIT ép chạy).

**Còn lại (chấp nhận, ghi nhận):** traceback của lỗi phát sinh *trong*
helper JIT frameless thiếu 1 frame callee (hiếm — mọi lỗi thường đã deopt
trước). Sửa đầy đủ cần push frame cho JIT (tốn ~30ns/call) — để Phase 6b
nếu cần.

**Rollback:** tắt flag (mặc định vẫn tắt).

---

### Phase 7 — Gia cố & xác thực hiệu năng cuối — ✅ XONG v1 (2026-09-15)

**Việc:**
1. Interleave Luava-JIT vs LuaJ 10 task (paired, pinned, median): **6 thắng**
   (arith 1.4×, fib **9.5×**, table ~1.0×, coroutines 16×, hash 1.4×,
   sieve 1.1×), **1 hòa** (closures 1.05×), 3 thua stdlib/metatable-bound
   (concat, oop, pattern). Mục tiêu "tất cả ≤ 1.03×" CHƯA đạt cho 3 task
   cuối — cần varargs/CLOSURE/generic-fallback (ghi nhận, không cố).
2. Đo lại **2026-09-16** sau đợt tối ưu lớn (paired, order-flipped,
   median 7-9 cặp, warm=6, iters=12, `luava.jit.sync=true`):
   **4 thắng** (arith 1.6×, fib 9.6×, coroutines 17-19×, hash 1.6×),
   **2 hòa** (table ~1.0×, closures ~1.0×), **4 thua nhẹ**
   (concat 1.03×, oop 1.2×, pattern 1.1×, sieve 1.05×). Từ 5 thua
   nặng (tới 2.8×) còn 4 thua sát ngưỡng; toàn bộ script vẫn pass
   assert nên đây là khoảng tối ưu engine, không phải bug.
3. Đợt tối ưu 2026-09-16 (không đổi ngữ nghĩa, 30/30 + 148 test xanh
   cả JIT on/off):
   - Intern chuỗi ngắn **lazy**: `valueOf` không bao giờ pool (dữ liệu
     script không ghim bộ nhớ); key canonical (hằng số compiler,
     metamethod, tên stdlib) giữ **định danh tham chiếu** qua
     `interned`, nên tra bảng thôi trả `String.equals`.
   - Inline không frame cho `tostring(x)`, `string.gmatch`,
     `math.sqrt` (raw-bits, không cấp `LuaFloat`), `setmetatable`.
   - Inline **closure factory** cho `make_counter`/`Vec.new` (dựng
     closure trực tiếp từ thanh ghi, cả `OP_CALL` lẫn `OP_TAILCALL`).
   - `gmatch` quét trực tiếp cho pattern đơn giản `%<class><quant>`.
   - Cache theo site cho `OP_GETTABUP`/`OP_SELF`/`OP_GETFIELD` (guard
     `readVersion()` + định danh bảng/key).
   - Danh sách tham số closure materialize lazy; `ensureFilled` rút
     gọn; GC tự động **giới hạn theo state** (không còn chạy finalizer
     / dọn weak table của state khác).
   - Hardening: `runErrorHandler` luôn reset cờ `handling` dù push
     frame handler ném `StackOverflowError`.
4. Đợt fix tương thích 2026-09-16 (differential fuzz đối chiếu stock Lua
   5.4.8; 30/30 + 148 test xanh):
   - **Số học chuỗi qua metatable**: cài `__add/__sub/__mul/__div/__idiv/
     __mod/__pow/__unm` mặc định trên string metatable như `lstrlib.c`;
     người dùng override/xoá được. Trước đây Luava ép kiểu trực tiếp nên
     `"10"+1` bỏ qua metamethod của người dùng.
   - **Blame đúng toán hạng** cho lỗi bitwise (`!isNumber ? p1 : p2`).
   - **`kname` chỉ đặt tên cho hằng chuỗi**: hết descriptor sai
     `number (constant '?') has no integer representation`.
   - `pairs`/`ipairs` không kiểm tra kiểu bảng (đúng PUC); `next`/`select`/
     `rawlen` dùng `luaL_argcheck` đúng thông điệp; `tostring` hàm C in
     `function: 0x…` như PUC.
   - **`collectgarbage` trả integer** cho collect/stop/restart và **thất bại
     (nil) khi gọi trong finalizer** (chống tái nhập).
   - `string.pack/unpack/packsize`: blame đúng số tham số.
   - **Bug compiler `maxStackSize`**: ghi trực tiếp `freereg` bỏ qua cập
     nhật `maxstacksize`, khiến frame nhỏ hơn số thanh ghi dùng thật → JIT
     guard cho qua rồi index tràn mảng (lỗi ngẫu nhiên `Index N out of
     bounds`) khi đệ quy sâu. Nay mọi thay đổi `freereg` đi qua `setFreereg`.
   - **Khởi tạo lớp giá trị sớm**: `Varargs.<clinit>` từng chạy ở đỉnh đệ
     quy (test tràn C-stack), ném `StackOverflowError` và khiến JVM "poison"
     lớp vĩnh viễn (`NoClassDefFoundError`). Nay `LuaState` chạm các lớp
     giá trị lõi lúc khởi tạo.
5. Đợt sửa JIT 2026-09-17 (JIT từ chỗ chỉ hữu ích cho fib → 8/10 task;
   30/30 + 150 test xanh, fuzz JIT on/off 12k case đồng nhất):
   - **Bug `GETFIELD` deopt**: guard cũ đòi bảng **không metatable**, nên
     mọi `self.x` trên instance OOP (có `Vec.__index`) deopt mỗi lần —
     `Vec:dot` không bao giờ chạy JIT. Nay guard là "rawget non-nil"
     (độc lập metatable); trường hợp `__index` (rawget nil) deopt đúng.
   - **Thiếu lane số thực**: JIT chỉ có số nguyên, nên hàm nóng trộn
     int/float (`dot` toàn float) deopt ngay ở `MUL`. Nay `ADD`/`SUB`/`MUL`
     (và các dạng K) phát **numeric dispatch**: lane int không box khi cả
     hai là int, lane float raw-bit khi còn lại; kiểu trả về suy luận
     `T_NUM` để chọn protocol đúng.
   - `doTailCall` thêm fast lane như `OP_CALL` (bỏ `resolveCallable` khi
     thanh ghi đã giữ `LuaFunction`).
   - Gate mới: đo **JIT on/off từng task**, không chỉ tổng thể — trước đây
     lọt việc JIT làm `arith_loop` chậm đi.
   - Còn lại: main chunk không tier-up (hotness đếm số lần **gọi hàm**),
     subset chưa gồm generic-for/closure/metatable-call; 3 task thua còn
     lại (oop/pattern ~1.06×, sieve ~0.99×) là throughput engine.
6. Stress JIT-on: `LuavaStressTest` 6/6 (deep recursion, tailcall 100k,
   coroutine churn, table/string/error pressure); suite ép threshold=1:
   45 proto compile, deopt đúng.
4. Metaspace/RSS bounded: cache LRU 512, hidden class GC được; đo RSS fib:
   JIT 55MB < interp 94MB.
5. `string.dump`/`load` round-trip với JIT bật: OK (`jitCode` không lọt
   vào dump; proto fresh compile lại khi cần).
6. `README.md` thêm mục Hybrid JIT; `PLANS.md` giữ hồ sơ gốc.
7. Nhật ký §7 cập nhật (SETLIST descriptor, cổng TAILCALL, CLOSURE revert,
   closeUpvalues fix).

**Quyết định mặc định:** `ENABLE_JIT` **true từ 2026-09-15** (mọi gate
xanh, escape hatch `-Dluava.jit=false` giữ lại). Lưu ý kiến trúc: hotness
tính theo proto-object, nên server eval-lại-script-mỗi-request (proto
mới mỗi lần) không bao giờ tier-up — compile một lần rồi gọi nhiều lần
(+ `prewarm`) mới hưởng JIT; script ngắn vẫn chạy interpreter nhanh.

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
| JIT `OP_CLOSURE`/object-return (`make_counter`) | closures 66→76ms (+15%, paired 7) | body toàn allocation, helper tốn 2 ThreadLocal lookup; revert, factories ở interpreter |
| Thứ tự shift/move sai trong TAILCALL (`a < nArgs`) | (chưa nổ: compiler luôn đặt `a ≥ nArgs`; bắt bằng review) | move func trước, shift sau; base-1 không overlap nguồn |
| `ctx.top` stale sau frameless call | (chưa nổ: compiler luôn thiết lập lại trước open-use; bắt bằng audit) | mirror `top = base+numParams` cho giống interpreter hệt |
| General-call arity `==` thành `>=` + nilFill | (tránh deopt-disable oan cho gọi thừa/thiếu args) | extras bỏ qua, thiếu nil-fill như interpreter |
| SETLIST descriptor thừa 1 int | ASM verify `NegativeArraySizeException` | đếm nhầm params helper (6 không phải 7); probe dịch trực tiếp bắt ngay |
| Cổng `JitCompiler` cấm TAILCALL từ Phase 2 | tailcall protos "not eligible" dù translator xong | quên mở cổng khi thêm op; probe chỉ ra |
| Kỳ vọng sai trong probe (`mix`) | tưởng JIT sai (600 vs 45750) | tự tính nhẩm sai: Σ(2+n−n)=600 mới đúng; luôn assert bằng tay trước |
| JIT return thiếu `closeUpvalues` | (bug, chưa đo) | upvalue mở của caller alias vùng callee → hỏng khi slot tái dùng; fix `closeOnJitReturn` |
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
3. **ĐÃ XÁC NHẬN (2026-09-15):** `BASIC_METATABLES` static toàn JVM thật sự
   ô nhiễm đa-state. Tái hiện: state A cài `debug.setmetatable('', ...)`,
   tạo `new LuaState()` B (constructor gọi `resetBasicMetatables()`), rồi A
   mất metatable string (`A:foo` → `nil`). Hai state cũng tranh nhau
   metatable string dùng chung (ai cài sau thắng).
   **Hướng fix (chưa làm, cần redesign):** metatable string nằm trên hot
   path `LuaString.getMetatable()`; fix đúng cần tra metatable qua state
   hiện hành (registry) hoặc gắn state vào chuỗi intern — cả hai đụng
   đường nóng nên phải đo lại interleave. Trước mắt: dùng một `LuaState`
   cho mỗi tenant, tránh tạo state mới sau khi đã cài metatable tùy biến.
4. **ĐÃ XÁC NHẬN (2026-09-15):** `GCManager` là singleton toàn JVM
   (`STATES`/`FINALIZERS`/`WEAK_TABLES` static). `GCManager.reset()` xóa
   finalizer/root của **mọi** state đang sống, không chỉ state gọi. Tương
   tự #3: fix cần chuyển sang instance per-state hoặc tách mark set.
5. `calls.lua` flake ~15% (`LuaUnwindException: null`) — pre-existing, chưa sửa.

### 7.4 Bug đã sửa (2026-09-15, audit "khó dùng/bug")
| # | Bug | Tầng | Fix |
|---|---|---|---|
| 1 | `evalWithTimeout` rò rỉ vthread chạy mãi sau timeout | LuaState | guard riêng + `cancel()` + join worker |
| 2 | `SHORT_STRING_CACHE` phình vô hạn (200k entry giữ mãi) | LuaString | intern yếu (WeakHashMap + WeakReference); giữ identity (literals.lua) |
| 3 | Chuỗi non-ASCII từ host sai `#`/`byte`/`utf8.len` | LuaDataConverter | encode UTF-8 tại biên host, decode UTF-8 khi trả về |
| 4 | `maxAllocationBytes` bỏ qua `..` (x=x..x phình tới MB) | LuaValue.concat | áp cap, fast-path cờ `ANY_MAX_ALLOC` |
| 5 | `ConstantFolder` dịch âm sai (`2 << -1` → 0, Lua = 1) | midend | `shiftLeft/Right` theo `luaV_shiftl` |
| 6 | `Typer`/`ConstantFolder` là code chết (AGENTS §IV.1) | midend/bytecode | tích hợp fold vào `compileExprToReg` (choke point) |
| 7 | Tài liệu `DEFAULT` gây hiểu nhầm chặn os/io | README | ghi rõ policy chỉ lọc cầu `java.*` |
| 8 | Số liệu test lệch (31/31 vs 30/30) | AGENTS/PLANS | chú thích mốc lịch sử |
| 9 | `BASIC_METATABLES` toàn cục: state mới xóa metatable state cũ | runtime | registry theo active `LuaState`, fallback toàn cục, scope lồng nhau |
| 10 | `collectgarbage('collect')` chạy finalizer của state khác | GCManager | gắn owner theo state thực thi, lọc finalizer khi collect tường minh |
| 11 | `GCManager.onAlloc` khóa toàn cục mỗi table/string | GCManager | đếm `AtomicLong` không khóa, chỉ khóa khi vượt ngưỡng/khi collect |

### 7.5 Audit JIT (2026-09-15): phủ + API + bug hook
Phát hiện khi soi tính năng JIT "có ổn định/dễ dùng không":
| # | Vấn đề | Tầng | Fix |
|---|---|---|---|
| J1 | Thiếu `ADDK/MULK/IDIVK/MODK/BANDK/BORK/BXORK` → `x*2`, `x//3`, `x%7` không JIT | translator | `emitArithK`/`emitIdivK`/`emitModK`; `analyze` đòi hằng `LuaInteger` |
| J2 | Thiếu `FORPREP/FORLOOP` → mọi vòng `for` số không JIT | translator | `emitForPrep/emitForLoop` mirror `doForPrep`/`OP_FORLOOP` |
| J3 | **`HOOKS_ARMED` static toàn JVM rò vĩnh viễn**: hook đặt rồi bỏ quên (hoặc coroutine bị bỏ) → **JIT tắt cho MỌI `LuaState` về sau** | LuaCoroutine/VM | đổi thành `hooksActive` per-coroutine (đúng ngữ nghĩa Lua: hook theo thread) |
| J4 | `ENABLE_JIT`/ngưỡng là static, không điều khiển per-state | LuaState | `jitEnabled(Boolean)`/`isJitEnabled()` |
| J5 | `prewarm` chỉ nhận `LuaClosure`, bỏ qua `LuaFunction`; vẫn compile khi JIT tắt | JitCompiler | overload `prewarm(LuaFunction)`; `prewarm(closure,state)` no-op khi state tắt JIT |

**Bằng chứng:** `JitCoverageTest` (12 test) so JIT on/off + giá trị đối chiếu stock;
fuzz loop 200 case byte-identical; J1/J2 kiểm bằng cache/proto `jitCode`.
Hiệu năng: fib 9.5–21×; loop nhỏ gọi nhiều lần ~6.5×; loop 30M lần đầu
ngang interpreter (OSR/C2 chưa chín) — không hồi quy. 30/30 + 135 xanh cả
JIT on lẫn `-Dluava.jit=false`.

**Giới hạn còn lại (chủ ý):** float loop, `while`/`repeat` (generic loop
state), `CONCAT`/`VARARG`/metamethod vẫn interpreter. Hotness đếm theo
`OP_CALL` nên loop ở main chunk không tier-up (kể cả loop trong hàm chỉ
hưởng nếu hàm được gọi ≥ 50 lần hoặc `prewarm`).


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
