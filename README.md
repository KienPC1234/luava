# Luava: Trình Biên Dịch Và Máy Ảo Lua 5.4 Thuần Java Trên Nền Tảng Java 21

Luava là engine máy ảo và trình biên dịch Lua 5.4 thuần Java (Pure Java), được thiết kế tối ưu cho nền tảng Java 21 LTS. Dự án loại bỏ hoàn toàn sự phụ thuộc vào Java Native Interface (JNI), các thư viện sinh bytecode cũ (ASM, BCEL), và mô hình máy trạng thái (state machine) cồng kềnh nhằm đạt hiệu năng tiệm cận Java nguyên bản.

---

## 1. Tổng Quan Kiến Trúc

Trong môi trường doanh nghiệp quy mô lớn và hệ thống máy chủ thời gian thực (như Minecraft Paper/Bukkit server), việc tích hợp ngôn ngữ kịch bản đòi hỏi tính an toàn bộ nhớ tuyệt đối, khả năng mở rộng đồng thời cao, và tuân thủ nguyên tắc "Build once, run anywhere".

Luava giải quyết bài toán này bằng cách biên dịch trực tiếp mã nguồn Lua 5.4 thành mã byte (bytecode) của Java Virtual Machine (JVM), khai thác trực tiếp các cải tiến phần cứng và các chuẩn JEP mới nhất của JDK 21.

### So Sánh Kiến Trúc Kỹ Thuật

| Thành phần Kiến trúc | LuaJ (Lua 5.2) | Rembulan / Luna (Lua 5.3) | Luava (Lua 5.4 - Java 21) |
| :--- | :--- | :--- | :--- |
| **Nền tảng JDK** | Java 5 / 7 / 8 | Java 8 | Java 21 LTS |
| **Mô hình Quản lý Kiểu** | Ép kiểu `Object` hoàn toàn | Ép kiểu `Object` qua slot chung | Unboxing hoàn toàn: Primitive slots (`long`, `double`) song song `Object` slots |
| **Mô hình Đồng thời** | OS Threads (1:1 với Kernel) | State Machine (Cỗ máy trạng thái giả lập) | Virtual Threads (JEP 444, M:N Scheduler) |
| **Sinh mã Bytecode** | BCEL (Byte Code Engineering Library) | ASM | Class-File API (JEP 457, `java.lang.classfile`) |
| **Cơ chế Gọi Động** | Reflection API chậm chạp | Reflection / Interface Dispatch | `invokedynamic` (Indy) kết hợp Polymorphic Inline Caching (PIC) |
| **Bảo toàn JIT Compiler** | Có (nhưng tốn RAM và GC thrashing) | Bị phá hủy hoàn toàn bởi rẽ nhánh trạng thái | Tối đa (bảo toàn cấu trúc bytecode tuyến tính) |
| **Quản lý Tài nguyên** | Finalizer cũ | Finalizer / Thủ công | Ánh xạ biến `<close>` sang `try-finally` / RAII của JVM |
| **Phụ thuộc Ngoại vi** | Phụ thuộc thư viện ngoài | Phụ thuộc thư viện ngoài | Zero Dependencies (100% JDK 21) |

---

## 2. Đường Ống Biên Dịch (Compilation Pipeline)

Luava tổ chức quá trình chuyển đổi và thực thi mã nguồn Lua thông qua đường ống 4 giai đoạn khép kín:

```
[Mã nguồn Lua 5.4]
       │
       ▼ (Lexer & Parser)
[Record-based AST] (Sealed Interfaces & Records)
       │
       ▼ (Pattern Matching for Switch)
[Mid-end Optimizer] (Type Inference, Unboxing, Constant Folding, Guard Elimination)
       │
       ▼ (Direct Code Generation)
[Class-File API Backend] (java.lang.classfile)
       │
       ▼ (Class Loading & Execution)
[Runtime Engine] (Virtual Threads + invokedynamic PIC + ZGC)
```

### Giai Đoạn 1: Frontend (Record-Based AST & Pattern Matching)

Frontend loại bỏ thiết kế Visitor Pattern truyền thống vốn gây rác bộ nhớ và khó bảo trì. AST của Luava được xây dựng dựa trên:
* **Records (JEP 395)**: Đảm bảo tính bất biến (immutability) của các nút cú pháp, tối ưu cho việc biên dịch song song đa luồng.
* **Sealed Interfaces (JEP 409)**: Định nghĩa tập hợp các nút cú pháp hợp lệ của Lua 5.4, ngăn chặn sự mở rộng không kiểm soát.
* **Pattern Matching for Switch (JEP 441)**: Cho phép trích xuất cấu trúc và kiểm tra kiểu của nút cú pháp trong một biểu thức rẽ nhánh duy nhất với chi phí chỉ lệnh tối thiểu.

### Giai Đoạn 2: Mid-End Optimizer (Suy Diễn Kiểu & Hủy Đóng Gói)

Bộ tối ưu hóa trung tâm thực hiện phân tích luồng dữ liệu tĩnh (Static Dataflow Analysis):
* **Type Provenance**: Dò tìm kiểu tĩnh của biến. Lua 5.4 phân định rõ ràng giữa `integer` (64-bit) và `float` (64-bit). Trình biên dịch ánh xạ trực tiếp sang `long` và `double` nguyên thủy của Java.
* **Guard Elimination**: Nếu các toán hạng được chứng minh tĩnh là số nguyên, hệ thống triệt tiêu hoàn toàn bước kiểm tra metamethod (`__add`, `__sub`, v.v.) tại runtime, phát lệnh trực tiếp (`ladd`, `lmul`).
* **Dual Slot Allocation**: Khác với Rembulan dùng chung khe `Object`, Luava cấp phát tách biệt khe biến nguyên thủy (`long`/`double`) và khe biến tham chiếu (`Object`).
* **Constant Folding & Branch Inlining**: Tính toán các biểu thức hằng số tại thời điểm biên dịch và loại bỏ các nhánh lệnh chết dựa trên phân tích liveness.

### Giai Đoạn 3: Backend Code Generator (Class-File API)

Luava không sử dụng ASM hay BCEL, mà tích hợp trực tiếp **Class-File API (JEP 457)** nằm trong gói `java.lang.classfile`:
* **Zero External Dependencies**: Trình sinh mã tương thích trực tiếp với các bản cập nhật JDK mà không gặp bài toán trễ phiên bản của thư viện ngoài.
* **Mô hình Ba Tầng**: Tách bạch giữa `Models` (cấu trúc lớp/phương thức), `Elements` (chỉ lệnh bytecode), và `Builders` (trình kết dính luồng lệnh).
* **Code Segmenter**: Tự động nhận diện các hàm Lua vượt quá giới hạn 64KB bytecode của phương thức JVM. Mô-đun này phân mảnh logic thành các phương thức tĩnh nhỏ liên kết tuần hoàn, tránh lỗi biên dịch runtime.

### Giai Đoạn 4: Runtime Đồng Thời Với Virtual Threads

Luava giải quyết triệt để bài toán coroutine của Lua:
* **Mô hình M:N**: Coroutine của Lua được thực thi trên Virtual Threads (JEP 444), được quản lý bởi ForkJoinPool của JVM thay vì cấp phát OS Thread.
* **Cơ chế Yield/Resume**: Khi `coroutine.yield()` được gọi, luồng ảo tự động thực hiện thao tác tháo gỡ (unmount). Dữ liệu stack frame được lưu xuống Heap, giải phóng Carrier Thread cho tác vụ khác. Khi `coroutine.resume()` được gọi, JVM nạp lại frame vào Carrier Thread rảnh rỗi.
* **Bảo tồn Tuyến Tính**: Bytecode của coroutine được giữ nguyên dạng tuyến tính (linear flow), cho phép JIT Compiler của JVM thực hiện Method Inlining và Loop Unrolling tối đa.

### Giai Đoạn 5: Lời Gọi Động (Invokedynamic) & Bộ Nhớ Đệm Nội Tuyến

Thao tác truy cập bảng (table) và gọi hàm động trong Lua được biên dịch thành chỉ lệnh `invokedynamic`:
* **Bootstrap Method (BSM)**: Khởi tạo liên kết tại lần chạy đầu tiên, trả về `CallSite` chứa `MethodHandle`.
* **Polymorphic Inline Caching (PIC)**: Duy trì bộ nhớ đệm đơn hình (Monomorphic) hoặc đa hình (Polymorphic) tại điểm gọi. Nếu kiểu dữ liệu ổn định, lệnh gọi được thực thi trực tiếp với tốc độ tương đương mã Java tĩnh.

---

## 3. Đặc Tả Tính Năng Lua 5.4

### Biến Cần Đóng (To-Be-Closed Variables `<close>`)

Cú pháp biến `<close>` trong Lua 5.4 được ánh xạ trực tiếp thành cấu trúc `try-finally` của JVM. Khi biến rời khỏi phạm vi khối lệnh (kể cả khi xảy ra lỗi runtime hoặc nhảy lệnh qua `goto`), phương thức siêu dữ liệu `__close` của đối tượng được bảo đảm kích hoạt an toàn, tương đương nguyên lý RAII trong C++ hoặc `AutoCloseable` trong Java.

### Tương Tác Hai Chiều Thuần Java (Pure Java Interoperability)

* **JavaUserdata**: Đóng gói đối tượng Java mà không cần mã C/JNI trung gian.
* **MethodHandle Invocation**: Quyền truy cập thuộc tính và phương thức Java được lưu cache qua `MethodHandle`.
* **Dynamic Proxies**: Bảng Lua (Lua Table) có thể triển khai trực tiếp các Java Interface, cho phép các framework Java (như Spring, Netty, Bukkit) gọi ngược (callback) vào mã Lua trong suốt.

### Bộ Thu Gom Rác ZGC & G1GC

Toàn bộ vòng đời đối tượng Lua được ủy quyền cho Garbage Collector của Java 21. Khi kết hợp với ZGC, hệ thống duy trì thời gian tạm dừng (pause time) dưới 1 mili-giây trên các vùng nhớ Heap dung lượng hàng chục Gigabyte.

---

## 4. Tích Hợp Ứng Dụng (Host Integration) & Gắn Code Java

Luava cung cấp hệ sinh thái API phong phú cho phép ứng dụng Java nhúng máy ảo, xuất các lớp/phương thức Java sang Lua, và tự động sinh tài liệu EmmyDoc/LuaLS cho IDE.

### 4.1 Khởi Tạo Máy Ảo & Thao Tác Dữ Liệu Hai Chiều

```java
LuaState state = new LuaState();

// Truyền dữ liệu hai chiều tự động (Map, List, Primitive, POJO)
state.set("config", Map.of("port", 8080, "debug", true));
state.eval("print('Port: ' .. config.port)");

// Lấy dữ liệu từ Lua về kiểu Java tự nhiên
Map<?, ?> result = state.get("config", Map.class);

// Gọi trực tiếp hàm Lua từ Java
state.eval("function add(a, b) return a + b end");
LuaValue sum = state.call("add", 10, 20); // 30
```

### 4.2 Đăng Ký Hàm Java Linh Hoạt (Fluent API)

Hỗ trợ gắn trực tiếp Java Lambdas với tự động chuyển đổi kiểu dữ liệu (Unboxing/Boxing):

```java
// Lambda nhận 2 tham số và trả về kết quả
state.registerFunction("multiply", Long.class, Long.class, (a, b) -> a * b);

// Lambda nhận 1 chuỗi ký tự
state.registerFunction("shout", String.class, s -> s.toUpperCase() + "!");

// Consumer (hàm void)
state.registerFunction("logInfo", String.class, msg -> logger.info(msg));
```

### 4.3 Đăng Ký Module Qua Annotation

Định nghĩa thư viện hoàn chỉnh bằng Java Annotation:

```java
@LuaModule(name = "PlayerSystem", description = "Quản lý dữ liệu người chơi")
public class PlayerService {
    @LuaField(name = "MAX_LEVEL", description = "Cấp độ tối đa", readOnly = true)
    public int maxLevel = 100;

    @LuaMethod(description = "Tính toán sát thương sau khi giảm trừ giáp")
    @LuaReturn(type = "number", description = "Sát thương thực nhận")
    public double calculateDamage(
        @LuaParam(name = "rawDamage", type = "number", description = "Sát thương gốc") double rawDamage,
        @LuaParam(name = "armor", type = "number", description = "Chỉ số giáp") double armor
    ) {
        return rawDamage * (100.0 / (100.0 + armor));
    }

    @LuaMethod(description = "Lấy danh sách vật phẩm")
    @LuaReturn(type = "table", description = "Mảng tên vật phẩm")
    public List<String> getInventory(@LuaParam(name = "playerId", type = "integer") long id) {
        return List.of("Sword", "Shield", "Potion");
    }
}

// Đăng ký vào LuaState (tự động có mặt trong _G và package.loaded)
state.registerModule(new PlayerService());
```

### 4.4 Tự Động Sinh Định Nghĩa EmmyDoc / LuaLS

Luava tự động phân tích metadata của các module Java đã đăng ký để xuất ra tệp định nghĩa EmmyLua (`.lua`) phục vụ tính năng gợi ý cú pháp (Autocomplete), kiểm tra kiểu (Type-Checking), và hiển thị tooltip trên VS Code hoặc IntelliJ IDEA:

```java
// Lấy chuỗi định nghĩa EmmyDoc
String emmyDoc = state.generateEmmyDocs();

// Hoặc xuất toàn bộ ra thư mục định nghĩa
state.exportEmmyDocs(Path.of("./types"));
```

Tệp định nghĩa sinh ra có dạng chuẩn:

```lua
---@meta

--- Quản lý dữ liệu người chơi
---@class PlayerSystem
---@field readonly MAX_LEVEL integer # Cấp độ tối đa
local PlayerSystem = {}

--- Tính toán sát thương sau khi giảm trừ giáp
---@param rawDamage number # Sát thương gốc
---@param armor number # Chỉ số giáp
---@return number # Sát thương thực nhận
function PlayerSystem.calculateDamage(rawDamage, armor) end

--- Lấy danh sách vật phẩm
---@param playerId integer
---@return table # Mảng tên vật phẩm
function PlayerSystem.getInventory(playerId) end

return PlayerSystem
```

### 4.5 Kiểm Soát Thực Thi & Giới Hạn Thời Gian (Sandboxing)

Nhờ mô hình Virtual Threads của Java 21, ứng dụng máy chủ có thể áp đặt hạn mức thời gian thực thi (execution timeout) mà không làm tắc nghẽn luồng xử lý của hệ điều hành:

```java
try {
    // Ngắt lệnh sau 500ms nếu script chạy vô hạn (while true do end)
    LuaValue val = state.evalWithTimeout(scriptCode, Duration.ofMillis(500));
} catch (TimeoutException e) {
    // Xử lý timeout an toàn
}
```

---

## 5. Cấu Trúc Mã Nguồn

```
luava
├── pom.xml
├── README.md
├── tests
│   └── lua-5.4.9-tests         # Bộ test suite chính thức từ Lua.org
└── src
    ├── main
    │   └── java
    │       └── org
    │           └── luava
    │               ├── binding         # Annotation (@LuaModule, @LuaMethod), ModuleBinder, DataConverter
    │               ├── emmydoc         # Trình sinh định nghĩa EmmyDoc/LuaLS stubs
    │               ├── frontend
    │               │   ├── ast         # Record-based AST nodes & sealed interfaces
    │               │   ├── lexer       # Token, TokenType, State-free Lexer
    │               │   └── parser      # Pattern-matching parser
    │               ├── midend
    │               │   └── optimizer   # Typer, ConstantFolder, SlotAllocator
    │               ├── backend
    │               │   └── codegen     # Class-File API Bytecode generator, CodeSegmenter
    │               └── runtime
    │                   ├── LuaState.java
    │                   ├── LuaValue.java
    │                   ├── LuaTable.java
    │                   ├── LuaFunction.java
    │                   ├── concurrency # Virtual Threads Coroutine Manager
    │                   ├── indy        # invokedynamic bootstraps & inline caches
    │                   ├── interop     # JavaUserdata & DynamicProxyBridge
    │                   └── standard    # BaseLib, MathLib, StringLib, TableLib, CoroutineLib, Utf8Lib, PackageLib
    └── test
        └── java
            └── org
                └── luava
                    └── frontend
                        └── LuavaTest.java
```

---

## 6. Yêu Cầu Môi Trường Và Biên Dịch

### Yêu Cầu Hệ Thống

* **JDK**: OpenJDK 21 trở lên.
* **Build Tool**: Apache Maven 3.9+.

### Lệnh Biên Dịch Và Kiểm Thử

```bash
# Biên dịch dự án với cờ Preview Features cho Class-File API
mvn clean compile

# Chạy toàn bộ kiểm thử tự động
mvn test

# Đóng gói thư viện JAR
mvn package
```

### Tùy Chọn Khởi Chạy JVM Khuyến Nghị

Để đạt hiệu năng tối ưu và độ trễ thấp nhất cho các ứng dụng tải cao:

```bash
java --enable-preview -XX:+UseZGC -XX:+ZGenerational -jar target/luava-1.0.0-SNAPSHOT.jar
```
