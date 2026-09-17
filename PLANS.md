# PLANS.md: Register-based Bytecode VM Technical Plan & Design (Phase 3)

This document specifies the register-based bytecode virtual machine
architecture, the AST-to-bytecode compiler, and the hardware / Java
HotSpot C2 JIT optimization strategies for the Luava project.

---

## I. Architecture Overview & Goals

### 1. Core Goals
1. **100% Lua 5.4 Semantic Compatibility**:
   - Full support for the 83 standard opcodes of Lua 5.4.9.
   - Standard 32-bit instruction formats: `iABC`, `iABx`, `iAsBx`, `iAx`, `isJ`.
   - Variable scope management: Open / Closed Upvalues, to-be-closed
     variables (`<close>`).
   - Tail-call optimization (`OP_TAILCALL`).
   - Varargs handling (`OP_VARARGPREP`, `OP_VARARG`).
   - Optimized loops: numeric for-loop (`OP_FORPREP`, `OP_FORLOOP`) and
     generic for-loop (`OP_TFORPREP`, `OP_TFORCALL`, `OP_TFORLOOP`).
2. **JVM HotSpot C2 Performance Standards**:
   - **Zero-Allocation Hot Paths**: during arithmetic, logic, register
     moves, and branching, no Java heap object is allocated.
   - **HotSpot C2 Inlining Friendly**: keep the dispatch-loop bytecode and
     hot helper methods under the 325-byte inlining threshold
     (`-XX:MaxInlineSize=325`) and below `HugeMethodLimit` (8,000 bytes).
   - **O(1) Branch Table**: use the natural Java `tableswitch` over the 83
     opcodes (HotSpot compiles it to an indirect branch table in assembly).
   - **Call Frame Recycling**: flat-array call stack (`LuaValue[] stack`)
     with primitive-`int` `base`, `top`, `pc` pointers.

---

## II. Lua 5.4 Instruction Set Specification (ISA)

### 1. 32-bit Instruction Layout
Each instruction is an unsigned 32-bit integer (`int` in Java):

```
        31             24 23             16 15        8 7        0
iABC:  [    B: 8 bit    |    C: 8 bit    | k |  A: 8   |  Op: 7  ]
iABx:  [             Bx: 17 bit              |  A: 8   |  Op: 7  ]
iAsBx: [            sBx: 17 bit (signed)     |  A: 8   |  Op: 7  ]
iAx:   [                   Ax: 25 bit                  |  Op: 7  ]
isJ:   [                  sJ: 25 bit (signed)          |  Op: 7  ]
```

- **Bias constants**:
  - `OFFSET_sBx = 65,535` ($2^{16} - 1$)
  - `OFFSET_sJ = 16,777,215` ($2^{24} - 1$)
- **Bit positions**:
  - `POS_OP = 0`, `SIZE_OP = 7`
  - `POS_A = 7`, `SIZE_A = 8`
  - `POS_k = 15`, `SIZE_k = 1`
  - `POS_B = 16`, `SIZE_B = 8`
  - `POS_C = 24`, `SIZE_C = 8`
  - `POS_Bx = 15`, `SIZE_Bx = 17`
  - `POS_Ax = 7`, `SIZE_Ax = 25`
  - `POS_sJ = 7`, `SIZE_sJ = 25`

### 2. The 83 OpCodes & Standard Behavior

| OpCode (id) | Format | Semantics summary |
| :--- | :--- | :--- |
| `OP_MOVE` (0) | iABC | `R[A] = R[B]` |
| `OP_LOADI` (1) | iAsBx | `R[A] = (lua_Integer)sBx` |
| `OP_LOADF` (2) | iAsBx | `R[A] = (lua_Number)sBx` |
| `OP_LOADK` (3) | iABx | `R[A] = K[Bx]` |
| `OP_LOADKX` (4) | iABx | `R[A] = K[Ax(next instruction)]` |
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
| `OP_NEWTABLE` (19) | iABC | `R[A] = {}` (array size B, hash size C) |
| `OP_SELF` (20) | iABC | `R[A+1] = R[B]; R[A] = R[B][RK(C)]` |
| `OP_ADDI` (21) | iABC | `R[A] = R[B] + sC` (immediate integer) |
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
| `OP_MMBIN` (46) | iABC | C metamethod call via `R[A]` and `R[B]` |
| `OP_MMBINI` (47) | iABC | C metamethod call via `R[A]` and integer `sB` |
| `OP_MMBINK` (48) | iABC | C metamethod call via `R[A]` and constant `K[B]` |
| `OP_UNM` (49) | iABC | `R[A] = -R[B]` |
| `OP_BNOT` (50) | iABC | `R[A] = ~R[B]` |
| `OP_NOT` (51) | iABC | `R[A] = not R[B]` |
| `OP_LEN` (52) | iABC | `R[A] = #R[B]` |
| `OP_CONCAT` (53) | iABC | `R[A] = R[A] .. ... .. R[A + B - 1]` |
| `OP_CLOSE` (54) | iABC | Close open upvalues $\ge R[A]$ |
| `OP_TBC` (55) | iABC | Mark $R[A]$ as to-be-closed |
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
| `OP_CALL` (68) | iABC | Call: $R[A..A+C-2] = R[A](R[A+1..A+B-1])$ |
| `OP_TAILCALL` (69) | iABC | Tail call: overwrite current stack frame |
| `OP_RETURN` (70) | iABC | Return: $R[A..A+B-2]$ |
| `OP_RETURN0` (71) | iABC | Return 0 values |
| `OP_RETURN1` (72) | iABC | Return 1 value $R[A]$ |
| `OP_FORLOOP` (73) | iABx | Numeric step update: `if continues then pc -= Bx` |
| `OP_FORPREP` (74) | iABx | Init and check integer/float loop step |
| `OP_TFORPREP` (75) | iABx | Init generic-loop upvalue; `pc += Bx` |
| `OP_TFORCALL` (76) | iABC | Iterator call: $R[A+4..A+3+C] = R[A](R[A+1], R[A+2])$ |
| `OP_TFORLOOP` (77) | iABx | `if R[A+2] ~= nil then { R[A] = R[A+2]; pc -= Bx }` |
| `OP_SETLIST` (78) | iABC | Batch fill: $R[A][C+i] = R[A+i], 1 \le i \le B$ |
| `OP_CLOSURE` (79) | iABx | $R[A] = \text{new closure}(KPROTO[Bx])$ |
| `OP_VARARG` (80) | iABC | Fetch varargs: $R[A..A+C-2] = \text{vararg}$ |
| `OP_VARARGPREP` (81) | iABC | Adjust the initial vararg parameter stack |
| `OP_EXTRAARG` (82) | iAx | Extended $Ax$ argument for the preceding instruction |

### 3. Three Mandatory Lua 5.4 Semantic Rules
1. **Fused `OP_EXTRAARG` Pair**:
   - When the constant-table index `K` exceeds the 17-bit $Bx$ range in
     `OP_LOADKX`, or the array size exceeds the $C$ range in `OP_SETLIST`,
     the compiler emits `OP_EXTRAARG` immediately after.
   - When the VM handles `OP_LOADKX` or `OP_SETLIST (C == 0)`, it reads
     the next instruction at `pc++` and extracts the 25-bit $Ax$ for the
     real index.
2. **Two-Step Metamethod Fallback (`OP_MMBIN*`)**:
   - In Lua 5.4, compiling arithmetic (`OP_ADD`, `OP_SUB`, …) emits a
     pair: the arithmetic instruction immediately followed by `OP_MMBIN`
     (or `OP_MMBINI`, `OP_MMBINK`).
   - If the operation on integer/float operands succeeds (fast path),
     the VM does `pc++` to skip `OP_MMBIN`.
   - Otherwise the VM falls through to `OP_MMBIN` to dispatch the
     metamethod (`__add`, `__sub`, …).
3. **Vararg Stack Coordination (`OP_VARARGPREP`)**:
   - Every vararg-function prototype starts with `OP_VARARGPREP A`.
   - It moves fixed parameters to $R[0..A-1]$ and the varargs into the
     pre-frame vararg area, so locals start exactly at $R[0]$.

---

## III. Core Data Structures

### 1. Prototype (`LuaProto.java`)
The compiled static binary chunk:
```java
public final class LuaProto {
    public final String source;          // File / chunk name ("@main.lua")
    public final int lineDefined;        // First line
    public final int lastLineDefined;    // Last line
    public final int numParams;          // Fixed parameter count
    public final boolean isVararg;       // Takes varargs (...) or not
    public final int maxStackSize;       // Max registers used

    public final int[] code;             // 32-bit instructions
    public final LuaValue[] constants;   // Constant table (K)
    public final LuaProto[] protos;      // Nested child prototypes
    public final UpvalueDesc[] upvalues; // Upvalue descriptors
    public final int[] lineInfo;         // Source line per instruction
}
```

### 2. Runtime Closure (`LuaClosure.java`)
Extends `LuaFunction`:
```java
public final class LuaClosure extends LuaFunction {
    public final LuaProto proto;
    public final Upvalue[] upvals;
    public final LuaTable env;           // Bound _ENV

    public LuaClosure(LuaProto proto, Upvalue[] upvalues, LuaTable env, LuaState state) {
        super(state);
        this.proto = proto;
        this.upvals = upvalues;
        this.env = env;
    }
}
```

#### 3. Dual/Triple Flat Stack & Read/Write Invariant (Lazy Materialization)
To eliminate boxing overhead on HotSpot (avoid allocating millions of
`LuaInteger` / `LuaFloat` objects in loops):
- **`long[] primitiveStack`**: raw 64-bit values (`long` integers,
  bit-cast `double` via `Double.doubleToRawLongBits`, booleans as `1L`/`0L`).
- **`byte[] typeStack`**: byte type tags (`TYPE_NIL = 0`, `TYPE_BOOLEAN = 1`,
  `TYPE_INT = 2`, `TYPE_FLOAT = 3`, `TYPE_OBJECT = 4`).
- **`LuaValue[] objectStack`**: real heap references only (`LuaTable`,
  `LuaClosure`, `LuaString`, `LuaUserdata`).

**Read/Write invariant:**
```text
typeStack[reg] == TYPE_INT     --> read raw long from primitiveStack[reg]
typeStack[reg] == TYPE_FLOAT   --> read Double.longBitsToDouble(primitiveStack[reg])
typeStack[reg] == TYPE_BOOLEAN --> read boolean from (primitiveStack[reg] != 0)
typeStack[reg] == TYPE_NIL     --> return LuaNil.NIL (no data read)
typeStack[reg] >= TYPE_OBJECT  --> read object reference from objectStack[reg]
```
- **Writing a primitive**: store `rawValue` in `primitiveStack[reg]`, set
  `typeStack[reg] = TYPE_*`, and always set `objectStack[reg] = null` so
  the JVM GC can immediately reclaim any object previously in that slot.
- **Writing a heap object**: store it in `objectStack[reg]`, set
  `typeStack[reg] = TYPE_OBJECT`; the old `primitiveStack[reg]` value
  needs no attention.

### 4. Unboxed Open & Closed Upvalues (`Upvalue.java`)
`Upvalue` avoids boxing primitives even after being closed:
```java
public final class Upvalue {
    // When open: links directly to the LuaState stack
    private LuaState state;
    private int stackIndex;
    private boolean open;

    // When closed: unboxed storage for primitives
    private long rawValue;
    private byte typeTag;
    private LuaValue objectValue; // Only used when typeTag == TYPE_OBJECT

    // Singly-linked open-upvalue list pointer
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

### 5. Open Upvalue List (Linked List & Instance Deduplication)
Lua 5.4 requires every closure capturing the same $R[A]$ on the same
frame to share exactly one `Upvalue` instance.
- `LuaState` keeps a head pointer `Upvalue openUpvaluesHead`, sorted by
  descending `stackIndex`.
- **Creating an open upvalue (`findOrCreateOpenUpvalue(stackIndex)`)**:
  walk the list; return the existing instance on a `stackIndex` hit,
  otherwise create one and insert it to keep descending order.
- **Closing upvalues (`closeUpvalues(fromIndex)`)**:
  called from `OP_CLOSE`, `OP_RETURN`, `OP_TAILCALL`, and to-be-closed
  `OP_TBC`. Walk from the head, close every node with
  `stackIndex >= fromIndex`, and unlink them.

### 6. Call Stack Frame (`CallInfo.java`)
Pooled for reuse, never freshly allocated:
```java
public final class CallInfo {
    public LuaClosure closure;
    public int funcIndex;        // Function position on the stack
    public int baseIndex;        // R(0) = stack[baseIndex]
    public int topIndex;         // Available stack top
    public int savedPc;          // Next instruction when the callee returns
    public int expectedResults;  // Expected result count (C - 1 in OP_CALL)
}
```

---

## IV. Bytecode Compiler Design (`BytecodeCompiler.java`)

The compiler takes the AST (`Statement` / `Block`) from the current
`Parser` and emits a `LuaProto`.

### 1. Register Allocation
- Maintains a `ScopeContext`:
  - `int numLocals`: active locals in scope.
  - `int freereg`: next free register ($R[\text{freereg}]$).
  - Nested scopes via a linked list or stack of `ScopeContext`.
- Temporaries allocate at `freereg++` and free as soon as the expression
  ends for register reuse, minimizing `maxStackSize`.

### 2. Jump Backpatching
- Control structures (`if`, `while`, `repeat`, `for`, `goto`, `break`)
  emit `OP_JMP` with an unknown offset ($sJ = 0$).
- Keeps a linked list of unpatched jumps (`labelList`, `pendingJumps`).
- At the block-end label, walks the list and patches the real offset
  into `sJ` / `sBx` via `Instruction.setsJ(code, pc, offset)`.

---

## V. Execution VM Design (`BytecodeVM.java`)

### 1. Splitting Bytecode to Respect the C2 JIT `HugeMethodLimit` (8,000 bytes)
Per the HotSpot spec, a method whose bytecode exceeds 8,000 bytes is
rejected by the C2 JIT. The mitigation:
- **Hot opcodes (kept inline in the `execute` `tableswitch`)**:
  `OP_MOVE`, `OP_LOADI`, `OP_LOADF`, `OP_LOADK`, `OP_LOADFALSE`,
  `OP_LOADTRUE`, `OP_LOADNIL`, `OP_GETUPVAL`, `OP_SETUPVAL`, `OP_ADD`,
  `OP_ADDI`, `OP_SUB`, `OP_MUL`, `OP_EQ`, `OP_EQI`, `OP_LT`, `OP_LE`,
  `OP_TEST`, `OP_TESTSET`, `OP_JMP`, `OP_FORPREP`, `OP_FORLOOP`.
- **Heavy / cold opcodes (delegated to `static` helpers)**:
  - `executeNewTable(state, inst, code, pc, stack, base, a)` for tables
    and `OP_EXTRAARG`.
  - `executeSetList(state, inst, code, pc, stack, base, a)` for batch
    fills and `OP_EXTRAARG`.
  - `executeClosure(state, inst, proto, closure, upvals, stack, base, a)`
    for closure creation.
  - `executeCall(state, inst, stack, callStack, callDepth, ...)` for call
    frames.
  - `executeTailCall(state, inst, stack, callStack, callDepth, ...)` for
    TCO frame reuse.
  - `executeReturn(state, inst, stack, callStack, callDepth, ...)` for
    frame teardown and results.
  - `executeConcat(stack, base, a, b)` for multi-operand concatenation.
  - `executeMetamethodBin(state, inst, op, stack, base, k, a)` for the
    `OP_MMBIN*` fallback.

> NOTE (2026-09-11): the "under 2,500 bytes" goal in the original plan
> no longer holds — `execute()` has grown to ~9.8 KB of bytecode
> (verified with `javap`), so C2 rejects it entirely (confirmed with
> `-XX:+PrintCompilation`: the method is never compiled, not even OSR).
> This is the measured root cause of the LuaJ gap (LuaJ's 3982-byte
> `execute` does get OSR/C2-compiled). Splitting `execute()` back under
> the 8 KB limit is the highest-leverage next step; see README.

### 2. Flat-Array Tail-Call Optimization (`OP_TAILCALL`)
On `OP_TAILCALL`:
1. Compute the new function address $R[A]$ and arguments $R[A+1..A+B-1]$.
2. Close all open upvalues of the current frame:
   `state.closeUpvalues(base)`.
3. Shift the new arguments across all 3 flat arrays:
    ```java
    System.arraycopy(primitiveStack, base + a + 1, primitiveStack, base, nActualArgs);
    System.arraycopy(typeStack,      base + a + 1, typeStack,      base, nActualArgs);
    System.arraycopy(objectStack,    base + a + 1, objectStack,    base, nActualArgs);
    // Null the objectStack tail to prevent reference leaks for the GC
    Arrays.fill(objectStack, base + nActualArgs, oldTop, null);
    ```
4. Update `closure = targetClosure`, `proto = closure.proto`,
   `code = proto.code`, `k = proto.constants`, `upvals = closure.upvals`.
5. Reset `pc = 0` and continue the loop with no new Java call frame.

---

## VI. Step-by-Step Implementation Roadmap

| Step | Work item | Files involved | Output |
| :--- | :--- | :--- | :--- |
| **Step 1** | ISA, instruction model, prototype | `OpCode.java`<br>`Instruction.java`<br>`LuaProto.java`<br>`UpvalueDesc.java` | 83 opcodes ready; 32-bit pack/unpack unit-tested 100% |
| **Step 2** | Core runtime structures | `LuaClosure.java`<br>`CallInfo.java`<br>`Upvalue.java` | Runnable closures; complete open/close upvalue mechanics |
| **Step 3** | `BytecodeCompiler` (AST to bytecode) | `BytecodeCompiler.java` | Valid `LuaProto` for statements & expressions |
| **Step 4** | `BytecodeVM` (83-opcode loop) | `BytecodeVM.java` | Complete `tableswitch` loop: arithmetic, tables, branches, calls |
| **Step 5** | `LuaState` integration | `LuaState.java`<br>`ChunkSerializer.java` | `LuaState.USE_BYTECODE_VM = true`; side-by-side verification runs |
| **Step 6** | Standard suite + performance | `OfficialSuiteEvaluationTest.java`<br>`PerformanceBenchmarkTest.java` | Pass the runnable suites; measure against C |

> NOTE (2026-09-11): `RegisterAllocator.java`, `JumpPatcher.java`, and
> `VMExtensions.java` named in the original plan were never created as
> separate files (their logic lives inside `BytecodeCompiler` /
> `BytecodeVM`); `all.lua` has never been run (it needs C test libs and
> an interactive harness).

---

## VII. Thread Isolation & Multi-VM Coroutine Architecture

### 1. Coroutine Architecture in Lua C
In standard Lua C:
- Each coroutine created by `lua_newthread(L)` is a separate
  `lua_State *L1` object.
- Each `lua_State` owns:
  - Its own stack array (`L1->stack`, `L1->top`, `L1->base`).
  - Its own open-upvalue chain (`L1->openupval`).
  - Its own to-be-closed list (`L1->tbclist`).
- All `lua_State`s share one `global_State *G` (string table, GC
  manager, `_G`, registry).

### 2. Stack Isolation Design for Luava (Virtual Threads & Coroutines)
Previously the `primitiveStack`, `typeStack`, `objectStack` arrays lived
on the `LuaState` instance. A child coroutine on a Java virtual thread
called `BytecodeVM.execute` at `base = 0` and stomped the parent's
registers.

**Completed, standardized fix**:
1. **Move stack ownership to `LuaCoroutine`**:
   - Each `LuaCoroutine` independently owns:
     - `long[] primitiveStack` (256 slots default, auto-grown by
       `ensureStackCapacity`).
     - `byte[] typeStack`.
     - `LuaValue[] objectStack`.
     - `Upvalue openUpvaluesHead` (its open-upvalue chain).
     - `TbcEntry tbcHead` (its pending `<close>` variables).
2. **Thread-context delegation in `LuaState`**:
   - `LuaState.getCurrentThread()` prefers `LuaCoroutine.running()` from
     the `ThreadLocal`, falling back to `mainThread`.
   - `getPrimitiveStack()`, `getTypeStack()`, `findOrCreateOpenUpvalue()`,
     `closeUpvalues()`, `pushTbc()`, `closeTbc()` all delegate to
     `getCurrentThread()`.
3. **Thread-accurate upvalue links (`LuaCoroutine`)**:
   - `Upvalue` keeps a direct reference to its creating `LuaCoroutine`.
   - When a coroutine passes a closure with an open upvalue to another
     coroutine, reads (`getValue`), writes (`setValue`), and closes
     (`close`) always hit the origin coroutine's stack arrays —
     eliminating races and stack collisions.

---

## VIII. Standard Library Tuning (StringLib & CoroutineLib)

### 1. `StringLib` (Zero Intermediate Allocation)
- **`string.len` fast path**:
  - Direct check `if (args[0] instanceof LuaString ls) return
    LuaInteger.valueOf(ls.value().length());`, no intermediate conversion.
- **`string.byte` direct array (no `ArrayList`)**:
  - Compute the exact byte count `int count = (int)(end - start + 1);`.
  - If `count == 1`, return immediately from the `ASCII_CACHE` array or
    `LuaInteger.valueOf`.
  - If `count > 1`, allocate `LuaValue[count]` directly — no
    `ArrayList<LuaValue>` boxing plus `.toArray()`.
- **`string.char` single allocation**:
  - One argument maps straight to
    `LuaString.valueOf(String.valueOf((char) val))` (`ASCII_CACHE` hit,
    0 allocations).
  - Multiple chars allocate one `char[n]` plus a single
    `new String(chars)` — no repeated `StringBuilder` growth.

### 2. `CoroutineLib` & `LuaCoroutine`
- **Lean handoff args & results**:
  - Recognize natural `Varargs` via `va.getValuesUnsafe()`, no copy
    unless needed.
  - Coordinate control flow and virtual threads via
    `LockSupport.park()` / `unpark()` to minimize switch latency.

---

## IX. Deep Dive: Register Bytecode vs Direct JVM Bytecode (ASM / .class)

Some developers ask: *should we compile Lua straight to Java bytecode
(.class) via ASM or ByteBuddy and run it on the JVM?*

### 1. Technical Comparison

| Criterion | Register Interpreter (BytecodeVM + C2) | Direct JVM Bytecode (ASM / .class) |
| :--- | :--- | :--- |
| **Cold start** | Very fast (< 0.5 ms): AST -> Lua bytecode is a flat walk. | Very slow (tens of ms): emit JVM bytecode, verify class, load via `ClassLoader.defineClass`. |
| **Memory leak (Metaspace)** | None. Lua bytecode lives in ordinary heap arrays, GC'd naturally. | **Very dangerous**: each closure/chunk is a Java class in Metaspace. Dynamically loaded scripts OOM it. |
| **Coroutine (`yield`) support** | Natural: BytecodeVM stores `pc`, `base`, `stack` easily, pairs with virtual threads. | **Dead end**: the JVM call stack cannot yield mid-method without extremely complex bytecode weaving. |
| **Function size limit** | No 64 KB JVM limit. Lua bytecode can be arbitrarily long. | Hits `MethodTooLargeException` from the 65,535-byte per-method JVM limit. |
| **Peak performance** | **Bounded by dispatch + JIT**: C2 optimizes the `tableswitch` loop, register hoisting, escape analysis — but only if the method stays compilable (see NOTE in section V). | Barely better, since dynamic Lua types still need runtime checks (`invokevirtual`). |

### 2. Architectural Conclusion
Compiling the runtime to Java `.class` is an **anti-pattern** for dynamic,
coroutine-supporting languages like Lua. The **register-based bytecode VM
plus HotSpot C2 JIT** remains the standard, safe, optimal path for Luava.

Emitting JVM `.class` should only be considered as an offline AOT tool
(`luava-aot` CLI) for packaging static bundles for Android or GraalVM
Native Image.

> UPDATE (2026-09-14): this verdict is now **reopened and superseded by
> `plan.md`** (Hybrid Tiered JIT). A translator spike
> (`/tmp/opencode/jitspike`) that emits one JVM method per Lua proto took
> fib(35) from ~5300 ms (interpreter) to ~185 ms — 13x faster than LuaJ and
> 4x faster than C Lua. The three §IX objections are addressed in `plan.md`
> §3: hidden classes + LRU code cache for Metaspace, a hard rule that
> yield-capable functions never JIT (preserving the 16x coroutine win), and
> an opcode-count ceiling so huge protos stay interpreted. The interpreter
> remains the default engine and fallback; JIT is an opt-in accelerator.

---

## X. Phased AST Retirement (Replace the AST Interpreter Entirely)

To free the codebase, remove the legacy layers, and make the bytecode VM
the sole Luava engine, this 4-step plan was established:

### Phase 1: Expand Bytecode Tests & Default On
1. **Full Bytecode Coverage**:
   - Default `LuaState.USE_BYTECODE_VM = true`.
   - Run all 31 PUC-Rio Lua 5.4.9 suites (`OfficialSuiteEvaluationTest`)
     under the bytecode VM.
   - Fix edge cases in error message format, traceback inspection
     (`debug.getinfo`), and hook line events vs the AST engine.

### Phase 2: Sync Debugger & Profiler to Bytecode
1. **Line Mapping & Debug Info**:
   - `LuaProto.lineInfo`: map each `pc` to the real source line.
   - Finish `DebugLib.java` to read frame info from the BytecodeVM
     `CallInfo` instead of the old interpreter `CallStack`.

### Phase 3: `@Deprecated` and Isolate the AST Interpreter
1. Mark `@Deprecated(forRemoval = true)`:
   - `org.luava.runtime.eval.Interpreter`
   - `org.luava.runtime.eval.Environment`
   - `org.luava.runtime.eval.VariableSlot`
2. Forward `LuaState.eval(...)` / `LuaState.compile(...)` to
   `BytecodeCompiler.compile` and `LuaClosure`.

### Phase 4: Remove the AST Execution Engine (Clean Slate)
1. Delete leftover files:
   - `Interpreter.java` (~1,500 lines of AST visitor logic).
   - `Environment.java` (~200 lines of variable-map management).
   - `VariableSlot.java`.
2. Simplify `Upvalue.java`: drop all `VariableSlot` / `Environment`
   fields and constructors, keep only the unboxed primitive path for
   `BytecodeVM`.
3. Re-run the full Maven build and keep 100% of the suite green.

> NOTE (2026-09-11): phases 1–4 are DONE (VM-only execution). The
> AST-walking `Interpreter.java` / `VariableSlot.java` are gone;
> `Environment.java` remains as the call-frame environment structure
> (used by `CallStack` and `DebugLib`), not as an execution engine.

---

## XI. Plan: Beat LuaJ (Get the Dispatch Loop JIT-Compiled)

### 1. Why This Is the Whole Game
Measured on one machine, warmed 1M-iteration integer loop, 30 reps:

| Engine | Per run | Dispatch loop size | JIT status (`-XX:+PrintCompilation`) |
| :--- | :--- | :--- | :--- |
| PUC Lua (C) | ~0.8 ms | n/a | n/a |
| LuaJ (`LuaClosure.execute`) | ~70 ms | 3982 bytes | OSR + C2 compiled |
| Luava (`BytecodeVM.execute`) | ~378 ms | **9799 bytes** | **never compiled, not even OSR** |

HotSpot refuses any method over 8,000 bytes (`DontCompileHugeMethods =
true` default): Luava's loop runs interpreted forever, LuaJ's does not.
That single fact explains the 5.4x gap — no amount of micro-opts on an
uncompiled loop can close it. The project goal is therefore mechanical
and verifiable: **get the loop under 8 KB (target ≤ 7 KB for margin) so
C2 compiles it, then confirm Luava's unboxed triple-stack beats LuaJ's
boxed `LuaInteger`/`LuaDouble` model.** If the compiled loop still
trails LuaJ, the plan's gates force a stop-and-rethink instead of blind
tuning.

Per-case bytecode sizes (via `javap -c -l`, LineNumberTable mapping):

| Region (`BytecodeVM.java`) | Source lines | ~Bytes | Verdict |
| :--- | :--- | :--- | :--- |
| `OP_TAILCALL` (715–888) | 173 | ~1521 | EXTRACT (biggest, coldest) |
| `OP_CALL` (598–715) | 117 | ~952 | Keep hot Lua path inline; extract cold arms |
| Loop head (266–326: fetch, mirror, hooks, decode) | 60 | ~415 | Extract hook polling (armed-only anyway) |
| `OP_ADD`/`SUB`/`MUL` int+float fast paths | ~22 each | ~259 each | KEEP inline (the benchmark loop lives here) |
| Comparisons (`EQ`/`LT`/`LE` …) | ~20 each | ~230 each | Keep unless still over budget |
| `OP_RETURN`/`RETURN0`/`RETURN1` (888–1025) | ~137 | ~1000 est. | EXTRACT second wave if needed |
| `catch` fault decoration + `finally` | — | ~500 est. | Extract decoration to helper |

Budget: 9799 → must remove ≥ 2300 bytes → target `runLoop` ≤ 7000.

### 2. Design: `VmContext` (Loop-Carried State Object)
`OP_CALL` / `OP_TAILCALL` / `OP_RETURN` mutate ~15 loop locals (`pc`,
`base`, `top`, `callDepth`, `closure`, `proto`, `code`, `k`, `upvals`,
`varargs`, stack arrays, `oldpc`, `varargPrepRan`), so they cannot move
to helpers that only take values. The fix is one small mutable holder:

```java
final class VmContext {
    int pc, base, top, callDepth, oldpc;
    boolean varargPrepRan;
    int scratch0;                       // in/out int for tiny helpers
    LuaClosure closure;
    LuaProto proto;
    int[] code;
    LuaValue[] k;
    Upvalue[] upvals;
    LuaValue[] varargs;
    CallInfo[] callStack;
    long[] pStack; byte[] tStack; LuaValue[] oStack;
}
```

- `execute()` keeps setup/teardown (`try`/`catch`/`finally`, initial
  frame push) and delegates to `runLoop(state, ctx)`.
- Big cases become `static` helpers taking `(state, ctx, a, inst)` and
  mutating `ctx` fields directly. Per-case temporaries (`funcIdx`,
  `nActualArgs`, `func`) stay helper-locals; only loop-carried state
  moves into `ctx`.
- The duplicated `__call`-resolution loop in `OP_CALL`/`OP_TAILCALL`
  becomes one helper: `resolveCallable(state, ctx, funcIdx, nArgs)`
  returning the `LuaFunction`, with the adjusted arg count via
  `ctx.scratch0`.
- Hook polling becomes `pollHooks(ctx, co, instPc, op)` — computes
  `curLine`/`curFrame` and stamps frames only when armed, so the hot
  path keeps exactly one predictable `HOOKS_ARMED` boolean check.
- `catch` fault decoration (`attachBytecodeDesc` + message rewrite)
  becomes `decorateFault(le, ctx, faultPc)`; the `finally` (pop to
  `initialDepth`, close upvalues, error mapping) stays in `execute()`.
- Why this stays fast: `ctx` is allocated once per `execute()` and
  never escapes. After the split every method is C2-compilable, and C2
  scalar-replaces (or at worst keeps as cheap field accesses) a
  non-escaping context. Even imperfect scalar replacement beats running
  interpreted by an order of magnitude. The zero-call arith loop is
  unaffected by helper-call overhead by construction.

### 3. Phases and Gates
Every phase must pass ALL FOUR gates or be reverted:

- **G1 – size**: `javap -c -p BytecodeVM | max offset of runLoop ≤ 7000`.
- **G2 – compiled**: `-XX:+PrintCompilation` shows `runLoop` with `%`
  (OSR) and `!`/plain (C2), i.e. no longer silently interpreted.
- **G3 – correct**: 31/31 official suites + 54 unit tests green
  (assertions active since `3706b5b`), plus byte-identical output on the
  3-engine complex script (`/tmp/opencode/complex.lua`).
  *(Historical gate. The current harness excludes `all.lua`, `main.lua`
  and `heavy.lua` by design: **30/30** runnable suites + **111** unit
  tests — see `README.md` and `OfficialSuiteEvaluationTest`.)*
- **G4 – faster**: warmed `bench1m.lua` ×30 vs the LuaJ mark (~70 ms).

Phases:

- **Phase 1a – context + cold extraction, no behavior change.**
  Introduce `VmContext`, move the loop to `runLoop`, extract
  `resolveCallable` + `pollHooks` + `decorateFault`. Expect ~1500–2000
  bytes saved. Gates G1–G4 (G4 may only partially improve here).
- **Phase 1b – extract `OP_TAILCALL` (~1500 B).**
  Biggest single chunk and the coldest of the big three (typical code
  does orders of magnitude fewer tailcalls than calls/returns).
  Re-measure; if G1 passes and G4 shows Luava < 70 ms, STOP — do not
  extract further.
- **Phase 1c – only if still over budget: extract the `OP_RETURN` trio
  (~1000 B) and/or `OP_CALL` cold arms (`__call` now shared;
  error paths).** Keep the Lua-closure fast path of `OP_CALL` inline —
  call-heavy workloads (fib) are sensitive to it.
- **Phase 2 – only if compiled yet still slower than LuaJ.**
  Profile the *compiled* loop with JFR and attack the top frame (likely
  call path or table access), one change + A/B at a time. No speculative
  rewrites.

### 4. Explicit Non-Goals and Risks
- NOT a JIT backend, NOT JVM-bytecode emission (rejected in section IX).
- NOT splitting by opcode range into two loops (duplicates dispatch).
- Risk: C2 fails to scalar-replace `ctx` → field-access overhead.
  Accepted: still compiled, still far faster than interpreted; G4 is the
  judge.
- Risk: semantic drift during the move. Mitigated by G3 plus the
  negative-tested harness (empty-run and sabotage guards) — any behavior
  change reddens the build.
- Stop rule: if Phase 1c passes G1–G3 but G4 still trails LuaJ,
  do NOT keep extracting; escalate to Phase 2 profiling data first.
