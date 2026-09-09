# AGENTS.md: Quy Chuẩn Kỹ Thuật & Kỷ Luật Phát Triển Luava

Tài liệu này xác lập các nguyên tắc kỹ thuật, đạo đức phát triển, và tiêu chuẩn kiến trúc bắt buộc cho toàn bộ các tác nhân và lập trình viên làm việc trên dự án Luava.

---

## I. Kỷ Luật Kiểm Thử & Chống Gian Lận (Test Integrity)

1. **Bất Khả Xâm Phạm Bộ Test Chuẩn**:
   - Nghiêm cấm tuyệt đối mọi hành vi sửa đổi, can thiệp, xóa bỏ, hoặc bypass bất kỳ dòng mã nào trong thư mục `tests/lua-5.4.9-tests/`.
   - Không được phép hardcode kết quả giả lập (mock returns) hoặc thêm các điều kiện rẽ nhánh chỉ để "lừa" bộ test vượt qua.
2. **Thước Đo Sự Tiến Bộ**:
   - Tiến độ tuân thủ chuẩn Lua 5.4 được đo lường duy nhất thông qua số lượng bài test trong `tests/lua-5.4.9-tests/` vượt qua một cách tự nhiên và chính tắc.
3. **Xử Lý Lỗi Kiểm Thử**:
   - Mọi lỗi phát sinh phải được phân tích đến tận tầng gốc rễ (Root Cause: Lexer, Parser, AST representation, Type conversions, Runtime VM, hoặc Standard Libraries) và sửa chữa triệt để tại tầng đó.

---

## II. Tiêu Chuẩn Hiệu Năng Sát Java Gốc (Near-Native Performance)

1. **Không Lạm Dụng Cơ Chế Chậm Của Máy Ảo**:
   - Hạn chế tối đa việc tạo rác (garbage allocation) trên các đường dẫn thực thi nóng (hot paths: vòng lặp `for`/`while`, phép toán số học, truy cập mảng/bảng).
   - Tận dụng đối tượng dùng chung (flyweight / cached instances) cho các giá trị số nguyên (`LuaInteger` cache), boolean (`LuaBoolean`), nil (`LuaNil.NIL`).
2. **Triệt Tiêu Chi Phí Reflection Trong Java Interop**:
   - Nghiêm cấm gọi `Method.invoke()` lặp đi lặp lại một cách mù quáng không qua bộ đệm.
   - Bắt buộc lưu cache các phương thức, trường dữ liệu và constructor đã được phân giải, hướng tới việc biên dịch liên kết thông qua `java.lang.invoke.MethodHandle` và `CallSite`.
3. **Quản Lý Bảng Lua (Table Architecture)**:
   - Cấu trúc `LuaTable` phải duy trì sự phân tách tối ưu giữa phần mảng số nguyên liên tục (`arrayPart`) và phần băm (`hashPart`) để bảo đảm truy cập $O(1)$ mà không bị overhead của `HashMap`.
   - Chuẩn hóa khóa số: Phải coi khóa số thực có giá trị nguyên (ví dụ `1.0`) và khóa số nguyên (`1`) là cùng một phần tử duy nhất trong bảng, tuân thủ nghiêm ngặt đặc tả Lua 5.4.

---

## III. Tiêu Chuẩn Cho Custom Java Binding & Interoperability

Hệ sinh thái liên tác giữa Java và Lua phải được thiết kế để nhà phát triển Java có thể nhúng và xuất mã một cách tự nhiên, trực quan và không có độ trễ:

1. **Đa Dạng Hóa Cơ Chế Tích Hợp**:
   - **Dynamic Reflection**: Cho phép script Lua tự nạp bất kỳ lớp Java nào thông qua `java.import(className)`, khởi tạo qua `Class.new(...)`, và gọi các phương thức tĩnh / đối tượng.
   - **Annotation-based Binding**: Cho phép gắn kết các lớp dịch vụ có sẵn qua `@LuaModule`, `@LuaMethod`, `@LuaField`.
   - **Fluent Host Registration**: Cho phép ứng dụng máy chủ đăng ký đối tượng và hàm thông qua `state.setLive(name, obj)` hoặc `state.registerFunction(...)`.
2. **Phân Giải Nạp Chồng Phương Thức Chính Xác (Overload Resolution)**:
   - Thuật toán so khớp tham số phải tính toán khoảng cách kiểu (Type Distance) chính xác: Exact Match > Widening Primitive Conversion > SAM Lambda Conversion > Subtype Assignable > Varargs.
3. **Liên Tác Hai Chiều Không Copy (Live Collections & Proxies)**:
   - Truyền `List`, `Map`, `Set`, hoặc Java Array vào Lua phải hỗ trợ chế độ Live Proxy (`LuaUserdata`), cho phép script đọc và sửa đổi trực tiếp trên cấu trúc dữ liệu Java gốc với độ phức tạp tối thiểu.
4. **Tự Động Chuyển Đổi Functional Interface (SAM)**:
   - Mọi hàm Lua (`LuaFunction`) khi truyền vào phương thức Java mong đợi interface đơn phương thức (như `Runnable`, `Consumer<T>`, `Function<T, R>`, `Predicate<T>`, `Comparator<T>`) phải được tự động bọc thành Dynamic Proxy tương thích.
   - Tương tự, khi đối tượng Java cài đặt SAM interface được gọi từ Lua (`userdata(...)`), runtime phải tự động chuyển tiếp lời gọi vào phương thức SAM đó.

---

## IV. Đạo Đức Mã Nguồn & Chất Lượng Phần Mềm (Clean Code)

1. **Cấm Lấp Liếm & Cấm Code Rác (Zero Dead/Stub Code)**:
   - Không để lại các file skeleton hoặc stub không được sử dụng masquerading như thể đã hoàn thành. Nếu một tính năng chưa được kích hoạt, phải ghi rõ trong tài liệu kỹ thuật hoặc hoàn thiện đến mức chạy được.
2. **Chuẩn Mực Xử Lý Ngoại Lệ**:
   - Mọi lỗi runtime của Lua phải được gói gọn trong `LuaException` với thông điệp rõ ràng, không để rò rỉ ngoại lệ không kiểm soát của Java (`NullPointerException`, `IndexOutOfBoundsException`) ra ngoài host application.
3. **Bảo Toàn Chuẩn Lua 5.4**:
   - Khi có sự xung đột giữa thói quen Java và đặc tả Lua 5.4 (ví dụ: chỉ số mảng bắt đầu từ 1, chia số thực `/` luôn ra float, chia nguyên `//` làm tròn về âm vô cùng, modulo tuân theo công thức $a - \lfloor a/b \rfloor \times b$), phải tuân theo đặc tả Lua 5.4 tuyệt đối.
4. **Nghiêm Cấm Tạo File Rác & Code Vô Tích Sự (Anti-Vanity & Zero File Pollution)**:
   - Nghiêm cấm tuyệt đối việc tạo các tệp tin tạm, script scratch, file log, tài liệu hoặc class giả lập chỉ nhằm mục đích làm đẹp báo cáo mà không tham gia trực tiếp vào luồng thực thi hay mang lại giá trị kỹ thuật thực tế cho dự án.
   - Mọi thay đổi mã nguồn phải phục vụ trực tiếp cho mục tiêu logic, tính tương thích hoặc tối ưu hóa hiệu năng đo lường được.

---

## V. Lộ Trình Cải Tổ Kiến Trúc & Vượt Qua Test Suite (Roadmap)

### Giai đoạn 1: Ổn Định Test Harness & Sửa Lỗi Logic (HOÀN THÀNH: 31/31 Suites Passed)
1. **Chuẩn hóa Test Harness**:
   - Loại trừ `all.lua` khỏi lượt chạy file lẻ tự động trong `OfficialSuiteEvaluationTest.java`.
   - Bổ sung timeout cho từng suite để ngăn tình trạng toàn bộ test suite bị treo.
   - Cài đặt `GCManager.reset()` cô lập rác giữa các lượt chạy.
2. **Khắc phục lỗi GC & Infinite Loop**:
   - Theo dõi cấp phát byte toàn diện trong `GCManager` (bao gồm chuỗi và phép nối chuỗi).
   - `strings.lua`: Cài đặt String Interning Pool cho chuỗi ngắn ($\le 40$ byte) theo chuẩn Lua 5.4.

### Giai đoạn 2: Tối Ưu Hóa Hot-Paths & Triệt Tiêu Cấp Phát Rác Trong AST Interpreter
1. **Lazy Descriptors & Zero-Allocation Calls**:
   - Chỉ sinh chuỗi mô tả lỗi (`getDescriptor()`) khi có exception thực tế.
   - Truyền đối số qua mảng trực tiếp, loại bỏ `ArrayList` trong `prepareCall` và `AssignmentStmt`.
2. **Mảng Hóa Biến Cục Bộ**:
   - Thay thế `HashMap<String, VariableSlot>` trong `Environment` bằng array index tĩnh.

### Giai đoạn 3: Chuyển Đổi Kiến Trúc Sang Register-based Bytecode VM
1. Xây dựng Bytecode Compiler biên dịch trực tiếp từ AST sang hệ lệnh 32-bit register-based.
2. Cài đặt Virtual Machine loop trực tiếp trên mảng stack, giải phóng triệt để các skeleton class thừa (`CodeSegmenter`, `SlotAllocator`).

