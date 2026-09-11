package org.luava.runtime.bytecode;

import org.luava.runtime.LuaValue;
import org.luava.runtime.eval.Upvalue;

/**
 * Loop-carried state of {@link BytecodeVM#runLoop}.
 *
 * <p>The dispatch loop was a single ~9.8 KB method, which HotSpot refuses
 * to JIT-compile (over the 8 KB {@code HugeMethodLimit}), so the
 * interpreter ran uncompiled forever. Moving this state into one holder
 * lets big opcode handlers ({@code OP_CALL}, {@code OP_TAILCALL},
 * {@code OP_RETURN}) move to {@code static} helpers while the loop itself
 * stays small enough for C2/OSR. The object is allocated once per
 * {@code execute()} and never escapes, so C2 can scalar-replace it.
 */
public final class VmContext {
    // Integer loop state
    public int pc;
    public int base;
    public int top;
    public int callDepth;
    public int oldpc;
    public boolean varargPrepRan;
    /** Scratch in/out slot for tiny helpers (e.g. callable resolution). */
    public int scratch0;

    // Frame / teardown state
    public int savedStackTop;
    public int initialDepth;
    public Throwable thrown;

    // Current function view (re-pointed on every call/return)
    public LuaClosure closure;
    public LuaProto proto;
    public int[] code;
    public LuaValue[] k;
    public Upvalue[] upvals;
    public LuaValue[] varargs;
    public CallInfo[] callStack;

    // Value stacks (re-fetched after growth)
    public long[] pStack;
    public byte[] tStack;
    public LuaValue[] oStack;
}
