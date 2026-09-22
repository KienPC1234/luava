# AGENTS.md: Luava Technical Standards & Development Discipline

This document establishes the engineering principles, development ethics,
and mandatory architecture standards for every agent and programmer
working on the Luava project.

---

## I. Test Discipline & Anti-Cheating (Test Integrity)

1. **Inviolability of the Standard Test Suite**:
   - Absolutely forbidden: modifying, tampering with, deleting, or
     bypassing any line of code under `tests/lua-5.4.9-tests/`.
   - No hardcoded fake results (mock returns) and no branching added
     solely to "trick" the test suite into passing.
2. **Measure of Progress**:
   - Lua 5.4 compliance progress is measured solely by the number of
     tests under `tests/lua-5.4.9-tests/` passing naturally and legitimately.
3. **Handling Test Failures**:
   - Every failure must be analyzed down to its root layer (Root Cause:
     Lexer, Parser, AST representation, Type conversions, Runtime VM, or
     Standard Libraries) and fixed thoroughly at that layer.

---

## II. Near-Native Performance Standards

1. **No Abuse of Slow VM Mechanisms**:
   - Minimize garbage allocation on hot execution paths (hot paths:
     `for`/`while` loops, arithmetic, array/table access).
   - Use shared objects (flyweight / cached instances) for integers
     (`LuaInteger` cache), booleans (`LuaBoolean`), and nil (`LuaNil.NIL`).
2. **Eliminate Reflection Cost in Java Interop**:
   - Forbidden: calling `Method.invoke()` repeatedly without caching.
   - Resolved methods, fields, and constructors must be cached, moving
     toward linked bindings via `java.lang.invoke.MethodHandle` and
     `CallSite`.
3. **Lua Table Management (Table Architecture)**:
   - `LuaTable` must keep the optimal split between the contiguous
     integer-indexed part (`arrayPart`) and the hash part (`hashPart`)
     to guarantee $O(1)$ access without `HashMap` overhead.
   - Numeric key normalization: a float key with integral value (e.g.
     `1.0`) and the integer key (`1`) must refer to one and the same
     table entry, strictly per the Lua 5.4 specification.

---

## III. Standards for Custom Java Binding & Interoperability

The Java-Lua interop layer must let host developers embed and expose
code naturally, intuitively, and without latency:

1. **Diverse Integration Mechanisms**:
   - **Dynamic Reflection**: Lua scripts can load any Java class via
     `java.import(className)`, instantiate via `Class.new(...)`, and
     call static / instance methods.
   - **Annotation-based Binding**: bind existing service classes via
     `@LuaModule`, `@LuaMethod`, `@LuaField`.
   - **Fluent Host Registration**: host applications register objects
     and functions via `state.setLive(name, obj)` or
     `state.registerFunction(...)`.
2. **Precise Overload Resolution**:
   - Parameter matching must compute exact type distance: Exact Match >
     Widening Primitive Conversion > SAM Lambda Conversion > Subtype
     Assignable > Varargs.
3. **Zero-Copy Bidirectional Interop (Live Collections & Proxies)**:
   - Passing `List`, `Map`, `Set`, or Java arrays into Lua must support
     live-proxy mode (`LuaUserdata`), letting scripts read and mutate
     the original Java data structures directly with minimal overhead.
4. **Automatic Functional Interface (SAM) Conversion**:
   - Every Lua function (`LuaFunction`) passed to a Java method
     expecting a single-method interface (such as `Runnable`,
     `Consumer<T>`, `Function<T, R>`, `Predicate<T>`, `Comparator<T>`)
     must be automatically wrapped as a compatible dynamic proxy.
   - Likewise, when a Java object implementing a SAM interface is
     called from Lua (`userdata(...)`), the runtime must forward the
     call into that SAM method automatically.

---

## IV. Source Ethics & Software Quality (Clean Code)

1. **No Cover-Ups & No Dead Code (Zero Dead/Stub Code)**:
   - No unused skeleton or stub files masquerading as finished work. If
     a feature is not yet enabled, say so in technical documentation or
     finish it to a runnable state.
2. **Exception Handling Standards**:
   - Every Lua runtime error must be wrapped in `LuaException` with a
     clear message; unchecked Java exceptions (`NullPointerException`,
     `IndexOutOfBoundsException`) must never leak to the host application.
3. **Preserve the Lua 5.4 Standard**:
   - On any conflict between Java habits and the Lua 5.4 specification
     (e.g. arrays start at 1, float division `/` always yields float,
     integer division `//` rounds toward negative infinity, modulo
     follows $a - \lfloor a/b \rfloor \times b$), the Lua 5.4
     specification wins absolutely.
4. **No Junk Files & No Vanity Code (Anti-Vanity & Zero File Pollution)**:
   - Absolutely forbidden: temporary files, scratch scripts, log files,
     documents, or mock classes created only to embellish reports
     without directly participating in execution or delivering real
     technical value.
   - Every source change must directly serve logic, compatibility, or
     measurable performance optimization.

---

## V. Architecture Overhaul Roadmap & Test Suite Completion (Roadmap)

### Phase 1: Stabilize Test Harness & Fix Logic Bugs (DONE: 31/31 Suites Passed at the time)
> Historical milestone count. The current harness excludes `all.lua`,
> `main.lua` and `heavy.lua` by design and runs **30/30** runnable PUC
> suites + **213** unit tests (`README.md`, `OfficialSuiteEvaluationTest`).

1. **Standardize the Test Harness**:
   - Exclude `all.lua` from automatic per-file runs in
     `OfficialSuiteEvaluationTest.java`.
   - Add a timeout per suite so one hanging suite cannot stall the
     whole run.
   - Install `GCManager.reset()` to isolate garbage between runs.
2. **Fix GC & Infinite-Loop Bugs**:
   - Track byte allocation comprehensively in `GCManager` (including
     strings and string concatenation).
   - `strings.lua`: install a string-interning pool for short strings
     ($\le 40$ bytes) per Lua 5.4.

### Phase 2: Hot-Path Optimization & Eliminating Garbage Allocation in the AST Interpreter
1. **Lazy Descriptors & Zero-Allocation Calls**:
   - Build error descriptor strings (`getDescriptor()`) only on real
     exceptions.
   - Pass arguments via plain arrays; remove `ArrayList` from
     `prepareCall` and `AssignmentStmt`.
2. **Array-Backed Locals**:
   - Replace `HashMap<String, VariableSlot>` in `Environment` with
     static array indexes.

### Phase 3: Migration to a Register-Based Bytecode VM
1. Build a bytecode compiler translating directly from AST to a 32-bit
   register-based instruction set.
2. Implement a VM loop operating directly on stack arrays, fully
   removing redundant skeleton classes (`CodeSegmenter`, `SlotAllocator`).
