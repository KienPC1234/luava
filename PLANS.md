# PLANS.md: Kế Hoạch & Thiết Kế Kỹ Thuật Register-based Bytecode VM (Giai Đoạn 3)

Tài liệu này xác lập chi tiết kiến trúc máy ảo thanh ghi (Register-based Bytecode Virtual Machine), trình biên dịch bytecode từ AST (AST-to-Bytecode Compiler), và các chiến lược tối ưu hóa phần cứng / máy ảo Java HotSpot C2 JIT cho dự án Luava.

---

## I. Tổng Quan Kiến Trúc & Mục Tiêu

### 1. Mục Tiêu Cốt Lõi
1. **100% Tương Thích Ngữ Nghĩa Lua 5.4**:
   - Hỗ trợ đầy đủ 83 OpCodes chuẩn của Lua 5.4.9.
   - Định dạng lệnh chuẩn 32-bit: `iABC`, `iABx`, `iAsBx`, `iAx`, `isJ`.
   - Cơ chế quản lý phạm vi biến: Open / Closed Upvalues, biến to-be-closed (`<close>`).
   - Tối ưu hóa đệ quy đuôi (Tail-Call Optimization: `OP_TAILCALL`).
   - Xử lý tham số biến thiên (Varargs: `OP_VARARGPREP`, `OP_VARARG`).
   - Các vòng lặp tối ưu hóa: Numeric For-loop (`OP_FORPREP`, `OP_FORLOOP`) và Generic For-loop (`OP_TFORPREP`, `OP_TFORCALL`, `OP_TFORLOOP`).
2. **Tiêu Chuẩn Hiệu Năng JVM HotSpot C2**:
   - **Zero-Allocation Hot Paths**: Trong suốt quá trình thực thi phép tính số học, logic, di chuyển thanh ghi, rẽ nhánh, không cấp phát bất kỳ đối tượng Java nào trên heap.
   - **HotSpot C2 Inlining Friendly**: Giữ kích thước bytecode của vòng lặp dispatch và các phương thức trợ năng nóng dưới ngưỡng inlining 325 bytes bytecode (`-XX:MaxInlineSize=325`) và tránh vượt ngưỡng `HugeMethodLimit` (8,000 bytes bytecode).
   - **O(1) Branch Table**: Sử dụng lệnh Java `tableswitch` tự nhiên cho 83 opcodes (được HotSpot biên dịch thẳng sang indirect branch table trong assembly).
   - **Tái Sử Dụng Khung Ngăn Xếp (Call Frame Recycling)**: Ngăn xếp cuộc gọi dạng mảng phẳng (`LuaValue[] stack`) với con trỏ `base`, `top`, `pc` dạng `int` nguyên thủy.

---

## II. Đặc Tả Chi Tiết Tập Lệnh Lua 5.4 (Instruction Set Architecture)

### 1. Khuôn Dạng Lệnh 32-bit (Instruction Layout)
Mỗi lệnh là một số nguyên 32-bit không dấu (`int` trong Java):

```
       31             24 23             16 15        8 7        0
iABC:  [    B: 8 bit    |    C: 8 bit    | k |  A: 8   |  Op: 7  ]
iABx:  [             Bx: 17 bit              |  A: 8   |  Op: 7  ]
iAsBx: [            sBx: 17 bit (signed)     |  A: 8   |  Op: 7  ]
iAx:   [                   Ax: 25 bit                  |  Op: 7  ]
isJ:   [                  sJ: 25 bit (signed)          |  Op: 7  ]
```

- **Hằng số độ dời (Biases)**:
  - `OFFSET_sBx = 65,535` ($2^{16} - 1$)
  - `OFFSET_sJ = 16,777,215` ($2^{24} - 1$)
- **Hằng số vị trí bit**:
  - `POS_OP = 0`, `SIZE_OP = 7`
  - `POS_A = 7`, `SIZE_A = 8`
  - `POS_k = 15`, `SIZE_k = 1`
  - `POS_B = 16`, `SIZE_B = 8`
  - `POS_C = 24`, `SIZE_C = 8`
  - `POS_Bx = 15`, `SIZE_Bx = 17`
  - `POS_Ax = 7`, `SIZE_Ax = 25`
  - `POS_sJ = 7`, `SIZE_sJ = 25`

### 2. Danh Mục 83 OpCodes & Hành Vi Chuẩn

| OpCode (Mã) | Định dạng | Tóm tắt ngữ nghĩa |
| :--- | :--- | :--- |
| `OP_MOVE` (0) | iABC | `R[A] = R[B]` |
| `OP_LOADI` (1) | iAsBx | `R[A] = (lua_Integer)sBx` |
| `OP_LOADF` (2) | iAsBx | `R[A] = (lua_Number)sBx` |
| `OP_LOADK` (3) | iABx | `R[A] = K[Bx]` |
| `OP_LOADKX` (4) | iABx | `R[A] = K[Ax(lệnh sau)]` |
| `OP_LOADFALSE` (5) | iABC | `R[A] = false` |
| `OP_LFALSESKIP` (6) | iABC | `R[A] = false; pc++` |
| `OP_LOADTRUE` (7) | iABC | `R[A] = true` |
| `OP_LOADNIL` (8) | iABC | `R[A..A+B] = nil` |
| `OP_GETUPVAL` (9) | iABC | `R[A] = UpValue[B]` |
| `OP_SETUPVAL` (10) | iABC | `UpValue[B] = R[A]` |
| `OP_GETTABUP` (11) | iABC | `R[A] = UpValue[B][K[C]]` |
| `OP_GETTABLE` (12) | iABC | `R[A] = R[B][R[C]]` |
| `OP_GETI` (13) | iABC | `R[A] = R[B][C]` |
| `OP_GETFIELD` (14) | iABC | `R[A] = R[B][K[C]]` |
| `OP_SETTABUP` (15) | iABC | `UpValue[A][K[B]] = RK(C)` |
| `OP_SETTABLE` (16) | iABC | `R[A][R[B]] = RK(C)` |
| `OP_SETI` (17) | iABC | `R[A][B] = RK(C)` |
| `OP_SETFIELD` (18) | iABC | `R[A][K[B]] = RK(C)` |
| `OP_NEWTABLE` (19) | iABC | `R[A] = {}` (kích thước mảng B, băm C) |
| `OP_SELF` (20) | iABC | `R[A+1] = R[B]; R[A] = R[B][RK(C)]` |
| `OP_ADDI` (21) | iABC | `R[A] = R[B] + sC` (số nguyên trực tiếp) |
| `OP_ADDK` (22) | iABC | `R[A] = R[B] + K[C]` |
| `OP_SUBK` (23) | iABC | `R[A] = R[B] - K[C]` |
| `OP_MULK` (24) | iABC | `R[A] = R[B] * K[C]` |
| `OP_MODK` (25) | iABC | `R[A] = R[B] % K[C]` |
| `OP_POWK` (26) | iABC | `R[A] = R[B] ^ K[C]` |
| `OP_DIVK` (27) | iABC | `R[A] = R[B] / K[C]` |
| `OP_IDIVK` (28) | iABC | `R[A] = R[B] // K[C]` |
| `OP_BANDK` (29) | iABC | `R[A] = R[B] & K[C]` |
| `OP_BORK` (30) | iABC | `R[A] = R[B] \| K[C]` |
| `OP_BXORK` (31) | iABC | `R[A] = R[B] ~ K[C]` |
| `OP_SHRI` (32) | iABC | `R[A] = R[B] >> sC` |
| `OP_SHLI` (33) | iABC | `R[A] = sC << R[B]` |
| `OP_ADD` (34) | iABC | `R[A] = R[B] + R[C]` |
| `OP_SUB` (35) | iABC | `R[A] = R[B] - R[C]` |
| `OP_MUL` (36) | iABC | `R[A] = R[B] * R[C]` |
| `OP_MOD` (37) | iABC | `R[A] = R[B] % R[C]` |
| `OP_POW` (38) | iABC | `R[A] = R[B] ^ R[C]` |
| `OP_DIV` (39) | iABC | `R[A] = R[B] / R[C]` |
| `OP_IDIV` (40) | iABC | `R[A] = R[B] // R[C]` |
| `OP_BAND` (41) | iABC | `R[A] = R[B] & R[C]` |
| `OP_BOR` (42) | iABC | `R[A] = R[B] \| R[C]` |
| `OP_BXOR` (43) | iABC | `R[A] = R[B] ~ R[C]` |
| `OP_SHL` (44) | iABC | `R[A] = R[B] << R[C]` |
| `OP_SHR` (45) | iABC | `R[A] = R[B] >> R[C]` |
| `OP_MMBIN` (46) | iABC | Gọi metamethod C qua `R[A]` và `R[B]` |
| `OP_MMBINI` (47) | iABC | Gọi metamethod C qua `R[A]` và số nguyên `sB` |
| `OP_MMBINK` (48) | iABC | Gọi metamethod C qua `R[A]` và hằng số `K[B]` |
| `OP_UNM` (49) | iABC | `R[A] = -R[B]` |
| `OP_BNOT` (50) | iABC | `R[A] = ~R[B]` |
| `OP_NOT` (51) | iABC | `R[A] = not R[B]` |
| `OP_LEN` (52) | iABC | `R[A] = #R[B]` |
| `OP_CONCAT` (53) | iABC | `R[A] = R[A] .. ... .. R[A + B - 1]` |
| `OP_CLOSE` (54) | iABC | Đóng các upvalues mở $\ge R[A]$ |
| `OP_TBC` (55) | iABC | Đánh dấu biến $R[A]$ là to-be-closed |
| `OP_JMP` (56) | isJ | `pc += sJ` |
| `OP_EQ` (57) | iABC | `if ((R[A] == R[B]) ~= k) pc++` |
| `OP_LT` (58) | iABC | `if ((R[A] < R[B]) ~= k) pc++` |
| `OP_LE` (59) | iABC | `if ((R[A] <= R[B]) ~= k) pc++` |
| `OP_EQK` (60) | iABC | `if ((R[A] == K[B]) ~= k) pc++` |
| `OP_EQI` (61) | iABC | `if ((R[A] == sB) ~= k) pc++` |
| `OP_LTI` (62) | iABC | `if ((R[A] < sB) ~= k) pc++` |
| `OP_LEI` (63) | iABC | `if ((R[A] <= sB) ~= k) pc++` |
| `OP_GTI` (64) | iABC | `if ((R[A] > sB) ~= k) pc++` |
| `OP_GEI` (65) | iABC | `if ((R[A] >= sB) ~= k) pc++` |
| `OP_TEST` (66) | iABC | `if (not R[A] == k) pc++` |
| `OP_TESTSET` (67) | iABC | `if (not R[B] == k) pc++ else R[A] = R[B]` |
| `OP_CALL` (68) | iABC | Gọi hàm: $R[A..A+C-2] = R[A](R[A+1..A+B-1])$ |
| `OP_TAILCALL` (69) | iABC | Gọi đuôi: ghi đè stack frame hiện tại |
| `OP_RETURN` (70) | iABC | Trả về: $R[A..A+B-2]$ |
| `OP_RETURN0` (71) | iABC | Trả về 0 giá trị |
| `OP_RETURN1` (72) | iABC | Trả về 1 giá trị $R[A]$ |
| `OP_FORLOOP` (73) | iABx | Cập nhật bước lặp số: `if continues then pc -= Bx` |
| `OP_FORPREP` (74) | iABx | Khởi tạo và kiểm tra bước lặp số nguyên/thực |
| `OP_TFORPREP` (75) | iABx | Khởi tạo upvalue cho biến lặp generic; `pc += Bx` |
| `OP_TFORCALL` (76) | iABC | Gọi iterator function: $R[A+4..A+3+C] = R[A](R[A+1], R[A+2])$ |
| `OP_TFORLOOP` (77) | iABx | `if R[A+2] ~= nil then { R[A] = R[A+2]; pc -= Bx }` |
| `OP_SETLIST` (78) | iABC | Điền mảng: $R[A][C+i] = R[A+i], 1 \le i \le B$ |
| `OP_CLOSURE` (79) | iABx | $R[A] = \text{new closure}(KPROTO[Bx])$ |
| `OP_VARARG` (80) | iABC | Lấy tham số biến thiên: $R[A..A+C-2] = \text{vararg}$ |
| `OP_VARARGPREP` (81) | iABC | Điều chỉnh ngăn xếp tham số biến thiên ban đầu |
| `OP_EXTRAARG` (82) | iAx | Tham số mở rộng $Ax$ cho lệnh đứng ngay trước |

### 3. Ba Quy Chuẩn Ngữ Nghĩa Bắt Buộc Chuẩn Lua 5.4
1. **Cặp Lệnh Ghép `OP_EXTRAARG`**:
   - Khi index bảng hằng số `K` vượt quá phạm vi 17-bit $Bx$ trong `OP_LOADKX`, hoặc khi số phần tử mảng vượt quá phạm vi $C$ trong `OP_SETLIST`, trình biên dịch sinh lệnh `OP_EXTRAARG` ngay kế tiếp.
   - Khi máy ảo xử lý `OP_LOADKX` hoặc `OP_SETLIST (C == 0)`, nó đọc instruction kế tiếp tại `pc++`, trích xuất 25-bit $Ax$ để xác định index thực tế.
2. **Cơ Chế Metamethod Fallback Hai Bước (`OP_MMBIN*`)**:
   - Trong Lua 5.4, khi biên dịch phép toán số học (`OP_ADD`, `OP_SUB`, v.v.), trình biên dịch sinh cặp lệnh: lệnh số học chính, nối tiếp ngay sau là `OP_MMBIN` (hoặc `OP_MMBINI`, `OP_MMBINK`).
   - Nếu phép tính trên toán hạng số nguyên/số thực thành công (Fast Path), máy ảo tự động tăng `pc++` để bỏ qua lệnh `OP_MMBIN`.
   - Nếu toán hạng không phải là số hoặc có metatable chứa hàm tương ứng, máy ảo rơi xuống thực thi lệnh `OP_MMBIN` để dispatch metamethod (`__add`, `__sub`, v.v.).
3. **Điều Phối Ngăn Xếp Tham Số Biến Thiên (`OP_VARARGPREP`)**:
   - Mọi prototype của hàm vararg bắt đầu bằng lệnh `OP_VARARGPREP A`.
   - Lệnh này dịch chuyển các tham số cố định về đúng vị trí $R[0..A-1]$, dời các tham số biến thiên vào vùng vararg trước frame, đảm bảo thanh ghi cục bộ bắt đầu chuẩn xác từ $R[0]$.

---

## III. Cấu Trúc Dữ Liệu Cốt Lõi (Core Data Structures)

### 1. Prototype (`LuaProto.java`)
Đại diện cho khối mã nhị phân tĩnh đã biên dịch:
```java
public final class LuaProto {
    public final String source;          // Tên file / chunk ("@main.lua")
    public final int lineDefined;        // Dòng bắt đầu
    public final int lastLineDefined;    // Dòng kết thúc
    public final int numParams;          // Số lượng tham số cố định
    public final boolean isVararg;       // Có nhận varargs (...) hay không
    public final int maxStackSize;       // Số lượng thanh ghi tối đa mà hàm sử dụng
    
    public final int[] code;             // Mảng lệnh 32-bit
    public final LuaValue[] constants;   // Bảng hằng số (K)
    public final LuaProto[] protos;      // Bảng prototype con lồng nhau
    public final UpvalueDesc[] upvalues; // Danh sách mô tả upvalues
    public final int[] lineInfo;         // Ánh xạ từng instruction tới số dòng mã nguồn
}
```

### 2. Closure Tại Runtime (`LuaClosure.java`)
Kế thừa từ `LuaFunction`:
```java
public final class LuaClosure extends LuaFunction {
    public final LuaProto proto;
    public final Upvalue[] upvals;
    public final LuaTable env;           // Môi trường _ENV gắn kết
    
    public LuaClosure(LuaProto proto, Upvalue[] upvalues, LuaTable env, LuaState state) {
        super(state);
        this.proto = proto;
        this.upvals = upvalues;
        this.env = env;
    }
}
```

#### 3. Cấu Trúc Ngăn Xếp Phẳng Kép/Ba & Quy Ước Bất Biến (Lazy Materialization Invariant)
Để giải quyết triệt để vấn đề boxing overhead trên HotSpot JVM (tránh cấp phát hàng triệu object `LuaInteger` / `LuaFloat` trong vòng lặp):
- **`long[] primitiveStack`**: Lưu trực tiếp giá trị 64-bit thô (giá trị nguyên `long`, bit-cast của `double` qua `Double.doubleToRawLongBits`, hoặc boolean `1L`/`0L`).
- **`byte[] typeStack`**: Lưu thẻ kiểu dữ liệu byte (`TYPE_NIL = 0`, `TYPE_BOOLEAN = 1`, `TYPE_INT = 2`, `TYPE_FLOAT = 3`, `TYPE_OBJECT = 4`).
- **`LuaValue[] objectStack`**: Chỉ lưu các tham chiếu đối tượng Heap thực thụ (`LuaTable`, `LuaClosure`, `LuaString`, `LuaUserdata`).

**Quy Ước Bất Biến Đọc/Ghi (Execution Invariant):**
```text
typeStack[reg] == TYPE_INT     --> Đọc rawValue dạng long từ primitiveStack[reg]
typeStack[reg] == TYPE_FLOAT   --> Đọc Double.longBitsToDouble(primitiveStack[reg])
typeStack[reg] == TYPE_BOOLEAN --> Đọc boolean từ (primitiveStack[reg] != 0)
typeStack[reg] == TYPE_NIL     --> Trả về LuaNil.NIL (không cần đọc dữ liệu)
typeStack[reg] >= TYPE_OBJECT  --> Đọc tham chiếu đối tượng từ objectStack[reg]
```
- **Khi ghi giá trị nguyên thủy**: Ghi `rawValue` vào `primitiveStack[reg]`, gán `typeStack[reg] = TYPE_*`, và bắt buộc gán `objectStack[reg] = null` để JVM GC thu hồi ngay đối tượng cũ từng nằm tại ô nhớ đó.
- **Khi ghi đối tượng Heap**: Gán đối tượng vào `objectStack[reg]`, gán `typeStack[reg] = TYPE_OBJECT`, và không cần bận tâm giá trị cũ trong `primitiveStack[reg]`.

### 4. Open & Closed Upvalue Không Boxing (`Upvalue.java`)
`Upvalue` được thiết kế để không box kiểu nguyên thủy ngay cả khi đã bị đóng (closed):
```java
public final class Upvalue {
    // Khi Open: liên kết trực tiếp với stack của LuaState
    private LuaState state;
    private int stackIndex;
    private boolean open;

    // Khi Closed: lưu trực tiếp không qua boxing nếu là primitive
    private long rawValue;
    private byte typeTag;
    private LuaValue objectValue; // Chỉ dùng khi typeTag == TYPE_OBJECT

    // Con trỏ danh sách liên kết đơn (Open Upvalue Linked List)
    public Upvalue nextOpen;

    public Upvalue(LuaState state, int stackIndex) {
        this.state = state;
        this.stackIndex = stackIndex;
        this.open = true;
    }

    public void close() {
        if (open) {
            this.typeTag = state.getTypeStack()[stackIndex];
            this.rawValue = state.getPrimitiveStack()[stackIndex];
            this.objectValue = state.getObjectStack()[stackIndex];
            this.state = null;
            this.open = false;
        }
    }
}
```

### 5. Danh Sách Quản Lý Open Upvalues (Open Upvalue Linked List & Instance Deduplication)
Quy chuẩn Lua 5.4 yêu cầu mọi closure capture cùng một biến $R[A]$ trên cùng một frame **bắt buộc phải chia sẻ chung đúng một thể hiện (instance) `Upvalue` duy nhất**.
- `LuaState` duy trì một con trỏ đầu `Upvalue openUpvaluesHead`, danh sách được sắp xếp theo chiều giảm dần của `stackIndex`.
- **Khi tạo Upvalue mở (`findOrCreateOpenUpvalue(stackIndex)`)**:
  Duyệt qua danh sách. Nếu tìm thấy upvalue có cùng `stackIndex`, trả về ngay instance có sẵn. Nếu chưa có, tạo mới và chèn vào đúng vị trí để giữ trật tự sắp xếp giảm dần.
- **Khi đóng Upvalues (`closeUpvalues(fromIndex)`)**:
  Được gọi trong `OP_CLOSE`, `OP_RETURN`, `OP_TAILCALL`, và biến to-be-closed `OP_TBC`. Duyệt từ đầu danh sách (`openUpvaluesHead`), đóng tất cả node có `stackIndex >= fromIndex` và ngắt liên kết chúng ra khỏi danh sách.

### 6. Khung Ngăn Xếp Cuộc Gọi (`CallInfo.java`)
Tái sử dụng bằng pooling, hoàn toàn không cấp phát mới:
```java
public final class CallInfo {
    public LuaClosure closure;
    public int funcIndex;        // Vị trí function trên stack
    public int baseIndex;        // R(0) = stack[baseIndex]
    public int topIndex;         // Đỉnh stack khả dụng
    public int savedPc;          // Vị trí lệnh tiếp theo khi hàm con trả về
    public int expectedResults;  // Số lượng kết quả gọi mong đợi (C - 1 trong OP_CALL)
}
```

---

## IV. Thiết Kế Trình Biên Dịch Bytecode (`BytecodeCompiler.java`)

Trình biên dịch nhận vào AST (`Statement` / `Block`) từ `Parser` hiện tại và phát ra `LuaProto`.

### 1. Phân Phối Thanh Ghi (Register Allocation)
- Duy trì cấu trúc `ScopeContext`:
  - `int numLocals`: số biến cục bộ đang hoạt động trong scope.
  - `int freereg`: chỉ số thanh ghi tự do tiếp theo ($R[\text{freereg}]$).
  - Quản lý phạm vi lồng nhau bằng linked list hoặc stack các `ScopeContext`.
- Biến tạm thời được cấp phát tại `freereg++`, và giải phóng ngay khi biểu thức kết thúc để tái sử dụng thanh ghi, giảm thiểu `maxStackSize`.

### 2. Vá Bước Nhảy (Jump & Branch Backpatching)
- Các cấu trúc điều khiển (`if`, `while`, `repeat`, `for`, `goto`, `break`) sinh ra lệnh nhảy `OP_JMP` với khoảng cách chưa xác định ($sJ = 0$).
- Quản lý danh sách liên kết các lệnh nhảy chưa vá (`labelList`, `pendingJumps`).
- Khi gặp nhãn kết thúc khối, duyệt qua danh sách và vá độ lệch thực tế vào trường `sJ` hoặc `sBx` thông qua `Instruction.setsJ(code, pc, offset)`.

---

## V. Thiết Kế Máy Ảo Thực Thi (`BytecodeVM.java`)

### 1. Phân Tách Bytecode Tránh Ngưỡng C2 JIT `HugeMethodLimit` (8,000 bytes)
Theo đặc tả HotSpot VM, khi phương thức có bytecode vượt quá 8,000 bytes, C2 JIT sẽ từ chối biên dịch phương thức đó. Để giải quyết:
- **Hot Opcodes (Giữ inline trực tiếp trong `tableswitch` của `execute`)**:
  `OP_MOVE`, `OP_LOADI`, `OP_LOADF`, `OP_LOADK`, `OP_LOADFALSE`, `OP_LOADTRUE`, `OP_LOADNIL`, `OP_GETUPVAL`, `OP_SETUPVAL`, `OP_ADD`, `OP_ADDI`, `OP_SUB`, `OP_MUL`, `OP_EQ`, `OP_EQI`, `OP_LT`, `OP_LE`, `OP_TEST`, `OP_TESTSET`, `OP_JMP`, `OP_FORPREP`, `OP_FORLOOP`.
- **Heavy / Cold Opcodes (Bắt buộc ủy quyền ra `static` helper methods)**:
  - `executeNewTable(state, inst, code, pc, stack, base, a)` -> xử lý bảng và `OP_EXTRAARG`.
  - `executeSetList(state, inst, code, pc, stack, base, a)` -> xử lý điền mảng theo lô và `OP_EXTRAARG`.
  - `executeClosure(state, inst, proto, closure, upvals, stack, base, a)` -> khởi tạo closure.
  - `executeCall(state, inst, stack, callStack, callDepth, ...)` -> quản lý frame gọi hàm.
  - `executeTailCall(state, inst, stack, callStack, callDepth, ...)` -> tái sắp xếp frame TCO.
  - `executeReturn(state, inst, stack, callStack, callDepth, ...)` -> thu dọn frame và trả kết quả.
  - `executeConcat(stack, base, a, b)` -> nối chuỗi nhiều toán hạng.
  - `executeMetamethodBin(state, inst, op, stack, base, k, a)` -> fallback cho `OP_MMBIN*`.
- Nhờ phân tách này, kích thước bytecode của phương thức chính `execute()` được duy trì ổn định dưới 2,500 bytes (thấp hơn nhiều so với ngưỡng 8,000 bytes), đảm bảo HotSpot C2 biên dịch thành mã máy tối ưu.

### 2. Tối Ưu Hóa Đệ Quy Đuôi Chuẩn Mảng Phẳng (`OP_TAILCALL`)
Khi gặp `OP_TAILCALL`:
1. Tính toán địa chỉ hàm mới $R[A]$ và các tham số $R[A+1..A+B-1]$.
2. Đóng toàn bộ upvalues mở của frame hiện tại: `state.closeUpvalues(base)`.
3. Dịch chuyển đồng thời các tham số mới trên cả 3 mảng phẳng:
   ```java
   System.arraycopy(primitiveStack, base + a + 1, primitiveStack, base, nActualArgs);
   System.arraycopy(typeStack,      base + a + 1, typeStack,      base, nActualArgs);
   System.arraycopy(objectStack,    base + a + 1, objectStack,    base, nActualArgs);
   // Dọn null vùng nhớ trên objectStack để triệt tiêu rò rỉ tham chiếu (GC Leak Prevention)
   Arrays.fill(objectStack, base + nActualArgs, oldTop, null);
   ```
4. Cập nhật `closure = targetClosure`, `proto = closure.proto`, `code = proto.code`, `k = proto.constants`, `upvals = closure.upvals`.
5. Đặt lại `pc = 0` và tiếp tục vòng lặp mà không tạo thêm bất kỳ Java call frame nào.

---

## VI. Lộ Trình Triển Khai Từng Bước (Implementation Roadmap)

| Bước | Hạng mục công việc | File ảnh hưởng | Kết quả đầu ra |
| :--- | :--- | :--- | :--- |
| **Bước 1** | Xây dựng ISA, mô hình dữ liệu lệnh và prototype | `OpCode.java`<br>`Instruction.java`<br>`LuaProto.java`<br>`UpvalueDesc.java` | 83 OpCodes sẵn sàng; các hàm pack/unpack 32-bit có unit test đạt 100% |
| **Bước 2** | Cài đặt các cấu trúc runtime cốt lõi | `LuaClosure.java`<br>`CallInfo.java`<br>`Upvalue.java` | Closure thực thi được; cơ chế đóng/mở upvalue hoàn chỉnh |
| **Bước 3** | Cài đặt `BytecodeCompiler` (AST to Bytecode) | `BytecodeCompiler.java`<br>`RegisterAllocator.java`<br>`JumpPatcher.java` | Biên dịch các câu lệnh & biểu thức Lua thành `LuaProto` hợp lệ |
| **Bước 4** | Cài đặt `BytecodeVM` (Vòng lặp thực thi 83 OpCodes) | `BytecodeVM.java`<br>`VMExtensions.java` | Vòng lặp `tableswitch` hoàn thiện, hỗ trợ đầy đủ số học, bảng, rẽ nhánh, lời gọi hàm |
| **Bước 5** | Tích hợp vào `LuaState` với cơ chế chuyển tiếp A/B | `LuaState.java`<br>`ChunkSerializer.java` | Cờ `LuaState.USE_BYTECODE_VM = true`; chạy song song kiểm tra đối chiếu |
| **Bước 6** | Chạy bộ kiểm thử chuẩn và tối ưu hiệu năng | `OfficialSuiteEvaluationTest.java`<br>`PerformanceBenchmarkTest.java` | Vượt qua 31/31 suites và chạy `all.lua`; đo lường hiệu năng tiệm cận C |
