/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.luava.frontend.ast.AstPrinter;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.Instruction;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.bytecode.OpCode;
import org.luava.runtime.bytecode.UpvalueDesc;
import org.luava.runtime.eval.Environment;
import org.luava.runtime.eval.Upvalue;

public final class ChunkSerializer {
    public static final String LUA_SIGNATURE = "\u001bLua";

    public static final byte[] HEADER = new byte[] {
        0x1b, 'L', 'u', 'a',                       // 4 bytes: signature
        0x54,                                       // 1 byte: version 5.4
        0x00,                                       // 1 byte: format 0
        0x19, (byte) 0x93, '\r', '\n', 0x1a, '\n',  // 6 bytes: data conversion
        0x04,                                       // 1 byte: sizeof instruction
        0x08,                                       // 1 byte: sizeof lua integer
        0x08                                        // 1 byte: sizeof lua number
    };

    private static final long LUAC_INT = 0x5678L;
    private static final double LUAC_NUM = 370.5;
    private static final byte[] MAGIC_FOOTER = "LUAVA_V1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROTO_MAGIC = new byte[] {'L', 'P', 0x02, 0x00};

    private static final byte CONST_NIL = 0;
    private static final byte CONST_BOOLEAN = 1;
    private static final byte CONST_INT = 2;
    private static final byte CONST_FLOAT = 3;
    private static final byte CONST_STRING = 4;

    private static final int MAX_PARAM_COUNT = 256;
    private static final int MAX_UPVALUE_COUNT = 256;
    private static final int MAX_REGISTERS = 255;
    private static final int MAX_PROTO_DEPTH = 128;
    private static final int MAX_CODE_INSTRUCTIONS = 1_048_576;
    private static final int MAX_CONSTANTS = 262_144;
    private static final int MAX_LOCALS = 65_536;
    private static final int MAX_CHILD_PROTOS = 65_536;
    private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;

    private ChunkSerializer() {}

    private static void writeUtf8Bytes(DataOutputStream out, String s) throws IOException {
        if (s == null || s.isEmpty()) {
            out.writeInt(0);
            return;
        }
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf.length);
        out.write(utf);
    }

    private static String readUtf8Bytes(DataInputStream in, ByteArrayInputStream bais, long maxBytes)
            throws IOException {
        int len = in.readInt();
        if (len < 0 || len > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        if (len > maxBytes) {
            throw new LuaException("corrupt binary chunk (allocation limit exceeded)");
        }
        if (len == 0) {
            return "";
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static byte[] dumpProtoPayload(LuaClosure cl, boolean effectiveStrip) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(baos);
        out.write(PROTO_MAGIC);
        out.writeBoolean(effectiveStrip);
        String rootSource = effectiveStrip ? "" : (cl.getSource() != null ? cl.getSource() : cl.proto.source);
        writeUtf8Bytes(out, rootSource);
        writeProtoTree(out, cl.proto, effectiveStrip, true, 0);
        out.flush();
        return baos.toByteArray();
    }

    private static void writeProtoTree(
            DataOutputStream out, LuaProto proto, boolean strip, boolean isRoot, int depth)
            throws IOException {
        if (depth > MAX_PROTO_DEPTH) {
            throw new LuaException("function nesting too deep to dump");
        }
        boolean hasName = !strip && !isRoot && proto.name != null && !proto.name.isEmpty();
        out.writeBoolean(hasName);
        if (hasName) {
            writeUtf8Bytes(out, proto.name);
        }
        out.writeInt(proto.lineDefined);
        out.writeInt(strip ? proto.lineDefined : proto.lastLineDefined);
        out.writeInt(proto.numParams);
        out.writeBoolean(proto.isVararg);
        out.writeInt(proto.maxStackSize);

        out.writeInt(proto.code.length);
        for (int inst : proto.code) {
            out.writeInt(inst);
        }

        out.writeInt(proto.constants.length);
        for (LuaValue c : proto.constants) {
            if (c == null || c.isNil()) {
                out.writeByte(CONST_NIL);
            } else if (c instanceof LuaBoolean lb) {
                out.writeByte(CONST_BOOLEAN);
                out.writeBoolean(lb.toBoolean());
            } else if (c instanceof LuaInteger li) {
                out.writeByte(CONST_INT);
                out.writeLong(li.toLong());
            } else if (c instanceof LuaFloat lf) {
                out.writeByte(CONST_FLOAT);
                out.writeDouble(lf.toDouble());
            } else if (c instanceof LuaString ls) {
                out.writeByte(CONST_STRING);
                int len = ls.rawLength();
                out.writeInt(len);
                if (len > 0) {
                    out.write(ls.rawBytesUnsafe(), ls.rawOffset(), len);
                }
            } else {
                throw new LuaException("unable to dump constant of type " + c.typeName());
            }
        }

        out.writeInt(proto.upvalues.length);
        for (UpvalueDesc u : proto.upvalues) {
            out.writeBoolean(u.inStack);
            out.writeInt(u.index);
            out.writeBoolean("_ENV".equals(u.name));
            if (strip || u.name == null) {
                out.writeInt(0);
            } else {
                writeUtf8Bytes(out, u.name);
            }
        }

        if (!strip) {
            out.writeInt(proto.lineInfo.length);
            for (int ln : proto.lineInfo) {
                out.writeInt(ln);
            }
            out.writeInt(proto.locVars.length);
            for (String lv : proto.locVars) {
                writeUtf8Bytes(out, lv);
            }
            out.writeInt(proto.locVarInfos.length);
            for (LuaProto.LocVarInfo lvi : proto.locVarInfos) {
                writeUtf8Bytes(out, lvi.name());
                out.writeInt(lvi.reg());
                out.writeInt(lvi.startPc());
                out.writeInt(lvi.endPc());
            }
        }

        out.writeInt(proto.protos.length);
        for (LuaProto child : proto.protos) {
            writeProtoTree(out, child, strip, false, depth + 1);
        }
    }

    private static byte[] dumpLegacyPayload(LuaFunction fn, boolean effectiveStrip) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeBoolean(fn.isVararg());
        out.writeBoolean(effectiveStrip);
        out.writeUTF(effectiveStrip ? "=?" : (fn.getSource() != null ? fn.getSource() : "=(dump)"));
        out.writeInt(fn.getLineDefined());
        out.writeInt(effectiveStrip ? fn.getLineDefined() : fn.getLastLineDefined());

        List<String> params = fn.getParams();
        out.writeInt(params.size());
        for (String p : params) {
            out.writeUTF(p);
        }

        List<Upvalue> ups = fn.getUpvalues();
        out.writeInt(ups.size());
        for (Upvalue up : ups) {
            out.writeUTF(up.getName());
        }

        String code = fn.getRawSource();
        if (code == null) {
            Statements.BlockStmt body = fn.getBody();
            code = (body != null) ? AstPrinter.print(body) : "";
        }
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        out.writeInt(codeBytes.length);
        out.write(codeBytes);
        out.flush();
        return baos.toByteArray();
    }

    public static byte[] dump(LuaFunction fn, boolean strip) {
        if ("C".equals(fn.getWhat())) {
            throw new LuaException("unable to dump given function");
        }

        boolean effectiveStrip = strip || fn.isStripped();
        try {
            byte[] payload = (fn instanceof LuaClosure cl && cl.proto != null)
                    ? dumpProtoPayload(cl, effectiveStrip)
                    : dumpLegacyPayload(fn, effectiveStrip);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(HEADER.length + 16 + payload.length);
            baos.write(HEADER);
            ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(LUAC_INT);
            bb.putDouble(LUAC_NUM);
            baos.write(bb.array());
            baos.write(payload);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new LuaException("dump failed: " + e.getMessage());
        }
    }

    private static boolean hasMagicFooter(byte[] bytes) {
        int flen = MAGIC_FOOTER.length;
        if (bytes.length < flen + 4) return false;
        int start = bytes.length - flen;
        for (int i = 0; i < flen; i++) {
            if (bytes[start + i] != MAGIC_FOOTER[i]) return false;
        }
        return true;
    }

    private static boolean hasProtoMagicAt(byte[] bytes, int offset, int length) {
        if (length < PROTO_MAGIC.length || offset + PROTO_MAGIC.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < PROTO_MAGIC.length; i++) {
            if (bytes[offset + i] != PROTO_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private record DecodedProto(LuaProto proto, boolean[] envFlags) {}

    private static LuaFunction decodeProtoPayload(
            byte[] bytes, int offset, int length, String chunkName, LuaValue envVal, LuaTable globals)
            throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes, offset + PROTO_MAGIC.length, length - PROTO_MAGIC.length);
        DataInputStream in = new DataInputStream(bais);
        long maxAllowedBytes = Math.min(MAX_CHUNK_BYTES, LuaState.allocationLimit());
        long[] budget = new long[] {maxAllowedBytes};

        boolean isStripped = in.readBoolean();
        String embeddedSource = readUtf8Bytes(in, bais, budget[0]);
        chargeBudget(budget, embeddedSource.length());
        String resolvedSource = chunkName != null
                ? chunkName
                : (isStripped || embeddedSource.isEmpty() ? "=?" : embeddedSource);

        DecodedProto root = readProtoTree(in, bais, resolvedSource, isStripped, null, 0, budget);
        if (bais.available() != 0) {
            throw new LuaException("corrupted chunk: trailing bytes");
        }

        LuaProto proto = root.proto();
        boolean[] envFlags = root.envFlags();
        LuaTable envTable = (envVal instanceof LuaTable t) ? t : globals;
        Upvalue[] upvals = new Upvalue[proto.upvalues.length];
        for (int i = 0; i < proto.upvalues.length; i++) {
            String upName = proto.upvalues[i].name;
            Environment.VariableSlot slot;
            if (envFlags[i]) {
                slot = new Environment.VariableSlot(envVal != null ? envVal : globals, false, false);
            } else {
                slot = new Environment.VariableSlot(LuaNil.NIL, false, false);
            }
            upvals[i] = new Upvalue(upName, slot);
        }
        LuaClosure cl = new LuaClosure(proto, upvals, envTable);
        cl.setStripped(isStripped);
        cl.setSource(resolvedSource);
        cl.setLineDefined(proto.lineDefined);
        cl.setLastLineDefined(proto.lastLineDefined);
        cl.setNparams(proto.numParams);
        cl.setVararg(proto.isVararg);
        return cl;
    }

    private static void chargeBudget(long[] budget, long bytes) {
        if (bytes < 0 || bytes > budget[0]) {
            throw new LuaException("corrupt binary chunk (allocation limit exceeded)");
        }
        budget[0] -= bytes;
    }

    private static DecodedProto readProtoTree(
            DataInputStream in,
            ByteArrayInputStream bais,
            String source,
            boolean stripped,
            LuaProto parent,
            int depth,
            long[] budget)
            throws IOException {
        LuaState.checkGuard();
        if (depth > MAX_PROTO_DEPTH) {
            throw new LuaException("corrupt binary chunk: nesting depth exceeded");
        }
        boolean hasName = in.readBoolean();
        String name = null;
        if (hasName) {
            name = readUtf8Bytes(in, bais, budget[0]);
            chargeBudget(budget, name.length());
        }
        int lineDefined = in.readInt();
        int lastLineDefined = in.readInt();
        int numParams = in.readInt();
        if (numParams < 0 || numParams > MAX_PARAM_COUNT) {
            throw new LuaException("corrupt binary chunk");
        }
        boolean isVararg = in.readBoolean();
        int declaredMaxStack = in.readInt();
        if (declaredMaxStack < 2 || declaredMaxStack > MAX_REGISTERS || numParams > declaredMaxStack) {
            throw new LuaException("corrupt binary chunk");
        }

        int codeLen = in.readInt();
        if (codeLen < 1 || codeLen > MAX_CODE_INSTRUCTIONS) {
            throw new LuaException("corrupt binary chunk");
        }
        if ((long) codeLen * 4L > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, (long) codeLen * 4L);
        int[] code = new int[codeLen];
        for (int i = 0; i < codeLen; i++) {
            if ((i & 0xFF) == 0) {
                LuaState.checkGuard();
            }
            code[i] = in.readInt();
        }

        int constCount = in.readInt();
        if (constCount < 0 || constCount > MAX_CONSTANTS) {
            throw new LuaException("corrupt binary chunk");
        }
        if (constCount > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, (long) constCount * 8L);
        LuaValue[] constants = new LuaValue[constCount];
        for (int i = 0; i < constCount; i++) {
            if ((i & 0xFF) == 0) {
                LuaState.checkGuard();
            }
            byte tag = in.readByte();
            switch (tag) {
                case CONST_NIL -> constants[i] = LuaNil.NIL;
                case CONST_BOOLEAN -> constants[i] = LuaBoolean.valueOf(in.readBoolean());
                case CONST_INT -> constants[i] = LuaInteger.valueOf(in.readLong());
                case CONST_FLOAT -> constants[i] = LuaFloat.valueOf(in.readDouble());
                case CONST_STRING -> {
                    int sLen = in.readInt();
                    if (sLen < 0 || sLen > bais.available()) {
                        throw new LuaException("truncated binary chunk");
                    }
                    chargeBudget(budget, sLen);
                    if (sLen == 0) {
                        constants[i] = LuaString.EMPTY;
                    } else {
                        byte[] raw = new byte[sLen];
                        in.readFully(raw);
                        constants[i] = LuaString.probeCanonicalOrOwned(raw);
                    }
                }
                default -> throw new LuaException("corrupt binary chunk: invalid constant tag");
            }
        }

        int upCount = in.readInt();
        if (upCount < 0 || upCount > MAX_UPVALUE_COUNT) {
            throw new LuaException("corrupt binary chunk");
        }
        if ((long) upCount * 6L > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, (long) upCount * 24L);
        UpvalueDesc[] upvalues = new UpvalueDesc[upCount];
        boolean[] envFlags = (depth == 0) ? new boolean[upCount] : null;
        for (int i = 0; i < upCount; i++) {
            LuaState.checkGuard();
            boolean inStack = in.readBoolean();
            int idx = in.readInt();
            boolean isEnv = in.readBoolean();
            String uName = readUtf8Bytes(in, bais, budget[0]);
            chargeBudget(budget, uName.length());
            if (idx < 0 || idx > MAX_REGISTERS) {
                throw new LuaException("corrupt binary chunk");
            }
            if (parent != null) {
                int bound = inStack ? parent.maxStackSize : parent.upvalues.length;
                if (idx >= bound) {
                    throw new LuaException("corrupt binary chunk");
                }
            }
            String effectiveName = uName.isEmpty() ? (isEnv && !stripped ? "_ENV" : null) : uName;
            upvalues[i] = new UpvalueDesc(effectiveName, inStack, idx);
            if (envFlags != null) {
                envFlags[i] = isEnv;
            }
        }

        int[] lineInfo;
        String[] locVars;
        LuaProto.LocVarInfo[] locVarInfos;
        if (stripped) {
            lineInfo = new int[0];
            locVars = new String[0];
            locVarInfos = new LuaProto.LocVarInfo[0];
        } else {
            int lineLen = in.readInt();
            if (lineLen != 0 && lineLen != codeLen) {
                throw new LuaException("corrupt binary chunk");
            }
            if ((long) lineLen * 4L > bais.available()) {
                throw new LuaException("truncated binary chunk");
            }
            chargeBudget(budget, (long) lineLen * 4L);
            lineInfo = new int[lineLen];
            for (int i = 0; i < lineLen; i++) {
                lineInfo[i] = in.readInt();
            }

            int locCount = in.readInt();
            if (locCount < 0 || locCount > MAX_LOCALS) {
                throw new LuaException("corrupt binary chunk");
            }
            if ((long) locCount * 4L > bais.available()) {
                throw new LuaException("truncated binary chunk");
            }
            chargeBudget(budget, (long) locCount * 16L);
            locVars = new String[locCount];
            for (int i = 0; i < locCount; i++) {
                LuaState.checkGuard();
                locVars[i] = readUtf8Bytes(in, bais, budget[0]);
                chargeBudget(budget, locVars[i].length());
            }

            int infoCount = in.readInt();
            if (infoCount < 0 || infoCount > MAX_LOCALS) {
                throw new LuaException("corrupt binary chunk");
            }
            if ((long) infoCount * 16L > bais.available()) {
                throw new LuaException("truncated binary chunk");
            }
            chargeBudget(budget, (long) infoCount * 24L);
            locVarInfos = new LuaProto.LocVarInfo[infoCount];
            for (int i = 0; i < infoCount; i++) {
                LuaState.checkGuard();
                String lvName = readUtf8Bytes(in, bais, budget[0]);
                chargeBudget(budget, lvName.length());
                int reg = in.readInt();
                int startPc = in.readInt();
                int endPc = in.readInt();
                if (reg < 0 || reg >= MAX_REGISTERS
                        || startPc < 0 || startPc > codeLen
                        || endPc < -1 || endPc > codeLen) {
                    throw new LuaException("corrupt binary chunk");
                }
                locVarInfos[i] = new LuaProto.LocVarInfo(lvName, reg, startPc, endPc);
            }
        }

        int childCount = in.readInt();
        if (childCount < 0 || childCount > MAX_CHILD_PROTOS) {
            throw new LuaException("corrupt binary chunk");
        }
        if (childCount > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        int verifiedMaxStack = verifyCodeAndComputeStack(
                code, declaredMaxStack, constants.length, upCount, childCount, locVarInfos);

        LuaProto[] children = new LuaProto[childCount];
        LuaProto selfHolder = new LuaProto(
                name,
                source,
                lineDefined,
                stripped ? lineDefined : lastLineDefined,
                numParams,
                isVararg,
                verifiedMaxStack,
                code,
                constants,
                children,
                upvalues,
                lineInfo,
                locVars,
                locVarInfos);
        for (int i = 0; i < childCount; i++) {
            children[i] = readProtoTree(in, bais, source, stripped, selfHolder, depth + 1, budget).proto();
        }
        return new DecodedProto(selfHolder, envFlags != null ? envFlags : new boolean[0]);
    }

    private static int verifyCodeAndComputeStack(
            int[] code,
            int declaredMaxStack,
            int constLen,
            int upLen,
            int childLen,
            LuaProto.LocVarInfo[] locVarInfos) {
        int maxReg = declaredMaxStack - 1;
        for (LuaProto.LocVarInfo lvi : locVarInfos) {
            if (lvi.reg() > maxReg) {
                maxReg = lvi.reg();
            }
        }
        int len = code.length;
        int lastOp = Instruction.getOp(code[len - 1]);
        if (lastOp != OpCode.OP_RETURN
                && lastOp != OpCode.OP_RETURN0
                && lastOp != OpCode.OP_RETURN1
                && lastOp != OpCode.OP_TAILCALL
                && lastOp != OpCode.OP_JMP) {
            throw new LuaException("corrupt binary chunk");
        }
        for (int pc = 0; pc < len; pc++) {
            int inst = code[pc];
            int op = Instruction.getOp(inst);
            if (op < 0 || op >= OpCode.NUM_OPCODES) {
                throw new LuaException("corrupt binary chunk");
            }
            int a = Instruction.getA(inst);
            int b = Instruction.getB(inst);
            int c = Instruction.getC(inst);
            int bx = Instruction.getBx(inst);
            int k = Instruction.getk(inst);
            switch (op) {
                case OpCode.OP_MOVE, OpCode.OP_UNM, OpCode.OP_BNOT, OpCode.OP_NOT, OpCode.OP_LEN, OpCode.OP_MMBIN ->
                    maxReg = Math.max(maxReg, checkRegs2(a, b));
                case OpCode.OP_LOADI,
                        OpCode.OP_LOADF,
                        OpCode.OP_LOADFALSE,
                        OpCode.OP_LOADTRUE,
                        OpCode.OP_CLOSE,
                        OpCode.OP_TBC,
                        OpCode.OP_RETURN1,
                        OpCode.OP_MMBINI -> maxReg = Math.max(maxReg, checkReg(a));
                case OpCode.OP_LFALSESKIP -> {
                    if (pc + 2 >= len) throw new LuaException("corrupt binary chunk");
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_LOADK -> {
                    checkIndex(bx, constLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_LOADKX -> {
                    if (pc + 1 >= len || Instruction.getOp(code[pc + 1]) != OpCode.OP_EXTRAARG) {
                        throw new LuaException("corrupt binary chunk");
                    }
                    checkIndex(Instruction.getAx(code[pc + 1]), constLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_LOADNIL, OpCode.OP_CLEANUP -> maxReg = Math.max(maxReg, checkRegs2(a, a + b));
                case OpCode.OP_GETUPVAL, OpCode.OP_SETUPVAL -> {
                    checkIndex(b, upLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_GETTABUP -> {
                    checkIndex(b, upLen);
                    checkIndex(c, constLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_GETTABLE,
                        OpCode.OP_ADD,
                        OpCode.OP_SUB,
                        OpCode.OP_MUL,
                        OpCode.OP_MOD,
                        OpCode.OP_POW,
                        OpCode.OP_DIV,
                        OpCode.OP_IDIV,
                        OpCode.OP_BAND,
                        OpCode.OP_BOR,
                        OpCode.OP_BXOR,
                        OpCode.OP_SHL,
                        OpCode.OP_SHR -> maxReg = Math.max(maxReg, Math.max(checkReg(a), checkRegs2(b, c)));
                case OpCode.OP_GETI, OpCode.OP_ADDI, OpCode.OP_SHRI, OpCode.OP_SHLI ->
                    maxReg = Math.max(maxReg, checkRegs2(a, b));
                case OpCode.OP_GETFIELD,
                        OpCode.OP_ADDK,
                        OpCode.OP_SUBK,
                        OpCode.OP_MULK,
                        OpCode.OP_MODK,
                        OpCode.OP_POWK,
                        OpCode.OP_DIVK,
                        OpCode.OP_IDIVK,
                        OpCode.OP_BANDK,
                        OpCode.OP_BORK,
                        OpCode.OP_BXORK -> {
                    checkIndex(c, constLen);
                    maxReg = Math.max(maxReg, checkRegs2(a, b));
                }
                case OpCode.OP_MMBINK -> {
                    checkIndex(b, constLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_SETTABUP -> {
                    checkIndex(a, upLen);
                    checkIndex(b, constLen);
                    if (k == 1) {
                        checkIndex(c, constLen);
                    } else {
                        maxReg = Math.max(maxReg, checkReg(c));
                    }
                }
                case OpCode.OP_SETTABLE -> {
                    maxReg = Math.max(maxReg, checkRegs2(a, b));
                    if (k == 1) {
                        checkIndex(c, constLen);
                    } else {
                        maxReg = Math.max(maxReg, checkReg(c));
                    }
                }
                case OpCode.OP_SETI -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    if (k == 1) {
                        checkIndex(c, constLen);
                    } else {
                        maxReg = Math.max(maxReg, checkReg(c));
                    }
                }
                case OpCode.OP_SETFIELD -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    checkIndex(b, constLen);
                    if (k == 1) {
                        checkIndex(c, constLen);
                    } else {
                        maxReg = Math.max(maxReg, checkReg(c));
                    }
                }
                case OpCode.OP_NEWTABLE -> {
                    if (k == 1 && (pc + 1 >= len || Instruction.getOp(code[pc + 1]) != OpCode.OP_EXTRAARG)) {
                        throw new LuaException("corrupt binary chunk");
                    }
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_SELF -> {
                    maxReg = Math.max(maxReg, checkRegs2(a + 1, b));
                    if (k == 1) {
                        checkIndex(c, constLen);
                    } else {
                        maxReg = Math.max(maxReg, checkReg(c));
                    }
                }
                case OpCode.OP_CONCAT -> {
                    if (b < 2) {
                        throw new LuaException("corrupt binary chunk");
                    }
                    maxReg = Math.max(maxReg, checkRegs2(a, a + b - 1));
                }
                case OpCode.OP_JMP -> checkJumpTarget(pc + 1 + Instruction.getsJ(inst), len);
                case OpCode.OP_EQ, OpCode.OP_LT, OpCode.OP_LE, OpCode.OP_TESTSET -> {
                    if (pc + 2 >= len) throw new LuaException("corrupt binary chunk");
                    maxReg = Math.max(maxReg, checkRegs2(a, b));
                }
                case OpCode.OP_EQK -> {
                    if (pc + 2 >= len) throw new LuaException("corrupt binary chunk");
                    checkIndex(b, constLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_EQI,
                        OpCode.OP_LTI,
                        OpCode.OP_LEI,
                        OpCode.OP_GTI,
                        OpCode.OP_GEI,
                        OpCode.OP_TEST -> {
                    if (pc + 2 >= len) throw new LuaException("corrupt binary chunk");
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    if (b > 0) {
                        maxReg = Math.max(maxReg, checkReg(a + b - 1));
                    }
                    if (op == OpCode.OP_CALL && c > 1) {
                        maxReg = Math.max(maxReg, checkReg(a + c - 2));
                    }
                }
                case OpCode.OP_RETURN -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    if (b > 1) {
                        maxReg = Math.max(maxReg, checkReg(a + b - 2));
                    }
                }
                case OpCode.OP_RETURN0, OpCode.OP_VARARGPREP, OpCode.OP_EXTRAARG -> {}
                case OpCode.OP_VARARG -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    if (c > 1) {
                        maxReg = Math.max(maxReg, checkReg(a + c - 2));
                    }
                }
                case OpCode.OP_FORPREP -> {
                    maxReg = Math.max(maxReg, checkReg(a + 3));
                    checkJumpTarget(pc + 1 + bx, len);
                }
                case OpCode.OP_FORLOOP -> {
                    maxReg = Math.max(maxReg, checkReg(a + 3));
                    checkJumpTarget(pc + 1 - bx, len);
                }
                case OpCode.OP_TFORPREP -> {
                    maxReg = Math.max(maxReg, checkReg(a + 3));
                    checkJumpTarget(pc + 1 + bx, len);
                }
                case OpCode.OP_TFORCALL -> {
                    if (pc + 1 >= len) throw new LuaException("corrupt binary chunk");
                    maxReg = Math.max(maxReg, checkReg(a + 3 + Math.max(1, c)));
                }
                case OpCode.OP_TFORLOOP -> {
                    maxReg = Math.max(maxReg, checkReg(a + 4));
                    checkJumpTarget(pc + 1 - bx, len);
                }
                case OpCode.OP_SETLIST -> {
                    maxReg = Math.max(maxReg, checkReg(a));
                    if (b > 0) {
                        maxReg = Math.max(maxReg, checkReg(a + b));
                    }
                    if (k == 1 && (pc + 1 >= len || Instruction.getOp(code[pc + 1]) != OpCode.OP_EXTRAARG)) {
                        throw new LuaException("corrupt binary chunk");
                    }
                }
                case OpCode.OP_CLOSURE -> {
                    checkIndex(bx, childLen);
                    maxReg = Math.max(maxReg, checkReg(a));
                }
                default -> throw new LuaException("corrupt binary chunk");
            }
        }
        return Math.max(2, maxReg + 1);
    }

    private static int checkReg(int r) {
        if (r < 0 || r >= MAX_REGISTERS) {
            throw new LuaException("corrupt binary chunk");
        }
        return r;
    }

    private static int checkRegs2(int r1, int r2) {
        return Math.max(checkReg(r1), checkReg(r2));
    }

    private static void checkIndex(int idx, int bound) {
        if (idx < 0 || idx >= bound) {
            throw new LuaException("corrupt binary chunk");
        }
    }

    private static void checkJumpTarget(int target, int codeLen) {
        if (target < 0 || target >= codeLen) {
            throw new LuaException("corrupt binary chunk");
        }
    }

    private static LuaFunction decodeLegacyPayload(
            byte[] bytes, int offset, int length, String chunkName, LuaValue envVal, LuaTable globals)
            throws IOException {
        if (length < 22) {
            throw new LuaException("truncated binary chunk");
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes, offset, length);
        DataInputStream in = new DataInputStream(bais);
        long maxAllowedBytes = Math.min(MAX_CHUNK_BYTES, LuaState.allocationLimit());
        long[] budget = new long[] {maxAllowedBytes};

        int rawVararg = in.readUnsignedByte();
        int rawStripped = in.readUnsignedByte();
        if ((rawVararg & ~1) != 0 || (rawStripped & ~1) != 0) {
            throw new LuaException(
                    "incompatible binary chunk (PUC Lua C bytecode is not supported; Luava requires internal LP format)");
        }
        boolean isVararg = rawVararg == 1;
        boolean isStripped = rawStripped == 1;
        String source = in.readUTF();
        chargeBudget(budget, source.length());
        int lineDefined = in.readInt();
        int lastLineDefined = in.readInt();

        int paramCount = in.readInt();
        if (paramCount < 0 || paramCount > MAX_PARAM_COUNT) {
            throw new LuaException("corrupt binary chunk");
        }
        if (paramCount * 2L > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, paramCount * 16L);
        List<String> params = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            LuaState.checkGuard();
            String p = in.readUTF();
            chargeBudget(budget, p.length());
            params.add(p);
        }

        int upCount = in.readInt();
        if (upCount < 0 || upCount > MAX_UPVALUE_COUNT) {
            throw new LuaException("corrupt binary chunk");
        }
        if (upCount * 2L > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, upCount * 16L);
        List<String> upvalueNames = new ArrayList<>(upCount);
        for (int i = 0; i < upCount; i++) {
            LuaState.checkGuard();
            String u = in.readUTF();
            chargeBudget(budget, u.length());
            upvalueNames.add(u);
        }

        int codeLen = in.readInt();
        if (codeLen < 0 || codeLen > bais.available()) {
            throw new LuaException("truncated binary chunk");
        }
        chargeBudget(budget, codeLen);
        byte[] codeBytes = new byte[codeLen];
        in.readFully(codeBytes);
        if (bais.available() != 0) {
            throw new LuaException("corrupted chunk: trailing bytes");
        }
        String code = new String(codeBytes, StandardCharsets.UTF_8);

        LuaState.checkGuard();
        Lexer lexer = new Lexer(code);
        org.luava.frontend.lexer.TokenStream tokens = lexer.scanStream();
        Parser parser = new Parser(tokens);
        Statements.BlockStmt body;
        try {
            body = parser.parse();
        } catch (org.luava.frontend.parser.ParseException pe) {
            throw new LuaException("corrupt binary chunk: " + pe.getMessage());
        }

        LuaTable envTable = (envVal instanceof LuaTable t) ? t : globals;
        if (LuaState.USE_BYTECODE_VM) {
            String resolvedSource = chunkName != null ? chunkName : (isStripped ? "=?" : source);
            LuaProto proto = org.luava.runtime.bytecode.BytecodeCompiler.compile(
                    body, resolvedSource, params, isVararg, lineDefined, lastLineDefined, upvalueNames);

            Upvalue[] upvals = new Upvalue[proto.upvalues.length];
            for (int i = 0; i < proto.upvalues.length; i++) {
                String upName = proto.upvalues[i].name;
                Environment.VariableSlot slot;
                if ("_ENV".equals(upName)) {
                    slot = new Environment.VariableSlot(envVal != null ? envVal : globals, false, false);
                } else {
                    slot = new Environment.VariableSlot(LuaNil.NIL, false, false);
                }
                upvals[i] = new Upvalue(upName, slot);
            }
            LuaClosure cl = new LuaClosure(proto, upvals, envTable);
            cl.setStripped(isStripped);
            cl.setSource(resolvedSource);
            cl.setLineDefined(lineDefined);
            cl.setLastLineDefined(lastLineDefined);
            cl.setParams(params);
            cl.setNparams(params.size());
            cl.setVararg(isVararg);
            return cl;
        }

        throw new LuaException("AST interpreter retired; binary chunks load via bytecode VM only");
    }

    public static LuaFunction undump(
            byte[] bytes, String chunkName, LuaValue envVal, LuaTable globals, Environment rootEnv) {
        String name;
        if (chunkName != null && !chunkName.isEmpty()
                && (chunkName.charAt(0) == '@' || chunkName.charAt(0) == '=')) {
            name = chunkName.substring(1);
        } else {
            name = "binary string";
        }

        if (bytes == null || bytes.length < 4) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }
        for (int i = 1; i < 4; i++) {
            if (bytes[i] != HEADER[i]) {
                throw new LuaException(name + ": bad binary format (not a binary chunk)");
            }
        }
        if (bytes.length < 5) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }
        if (bytes[4] != HEADER[4]) {
            throw new LuaException(name + ": bad binary format (version mismatch)");
        }
        if (bytes.length < 6) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }
        if (bytes[5] != HEADER[5]) {
            throw new LuaException(name + ": bad binary format (format mismatch)");
        }
        if (bytes.length < 12) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }
        for (int i = 0; i < 6; i++) {
            if (bytes[6 + i] != HEADER[6 + i]) {
                throw new LuaException(name + ": bad binary format (corrupted chunk)");
            }
        }
        if (bytes.length < 15) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }
        if (bytes[12] != HEADER[12]) {
            throw new LuaException(name + ": bad binary format (Instruction size mismatch)");
        }
        if (bytes[13] != HEADER[13]) {
            throw new LuaException(name + ": bad binary format (lua_Integer size mismatch)");
        }
        if (bytes[14] != HEADER[14]) {
            throw new LuaException(name + ": bad binary format (lua_Number size mismatch)");
        }
        if (bytes.length < HEADER.length + 16) {
            throw new LuaException(name + ": bad binary format (truncated chunk)");
        }

        ByteBuffer bb = ByteBuffer.wrap(bytes, HEADER.length, 16).order(ByteOrder.LITTLE_ENDIAN);
        long intCheck = bb.getLong();
        double numCheck = bb.getDouble();
        if (intCheck != LUAC_INT) {
            throw new LuaException(name + ": bad binary format (integer format mismatch)");
        }
        if (numCheck != LUAC_NUM) {
            throw new LuaException(name + ": bad binary format (float format mismatch)");
        }

        if (hasMagicFooter(bytes)) {
            ByteBuffer footBb = ByteBuffer.wrap(bytes, bytes.length - 12, 4).order(ByteOrder.LITTLE_ENDIAN);
            int payloadLen = footBb.getInt();
            int headerEnd = HEADER.length + 16;
            if (payloadLen <= 0 || payloadLen > bytes.length - 12 - headerEnd) {
                throw new LuaException("corrupted chunk: invalid footer bounds");
            }
            int offset = bytes.length - 12 - payloadLen;
            try {
                return hasProtoMagicAt(bytes, offset, payloadLen)
                        ? decodeProtoPayload(bytes, offset, payloadLen, chunkName, envVal, globals)
                        : decodeLegacyPayload(bytes, offset, payloadLen, chunkName, envVal, globals);
            } catch (LuaException le) {
                throw le;
            } catch (EOFException e) {
                throw new LuaException("truncated binary chunk");
            } catch (IOException | RuntimeException e) {
                throw new LuaException("corrupted chunk: " + e.getMessage());
            }
        }

        try {
            int offset = HEADER.length + 16;
            int length = bytes.length - offset;
            return hasProtoMagicAt(bytes, offset, length)
                    ? decodeProtoPayload(bytes, offset, length, chunkName, envVal, globals)
                    : decodeLegacyPayload(bytes, offset, length, chunkName, envVal, globals);
        } catch (LuaException le) {
            throw le;
        } catch (EOFException e) {
            throw new LuaException("truncated binary chunk");
        } catch (IOException | RuntimeException e) {
            throw new LuaException("corrupted chunk: " + e.getMessage());
        }
    }
}
