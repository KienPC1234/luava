/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.frontend.ast.AstPrinter;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.*;
import org.luava.runtime.eval.Environment;
import org.luava.runtime.eval.Upvalue;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ChunkSerializer {
    public static final String LUA_SIGNATURE = "\u001bLua";

    public static final byte[] HEADER = new byte[]{
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

    private ChunkSerializer() {}

    private static byte[] dumpPayload(LuaFunction fn, boolean effectiveStrip) throws IOException {
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
            // Compile no longer pretty-prints every function (cold-start
            // cost); render source lazily here — string.dump is rare.
            org.luava.frontend.ast.Statements.BlockStmt body = fn.getBody();
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
            byte[] payload = dumpPayload(fn, effectiveStrip);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
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

    private static LuaFunction decodePayload(byte[] bytes, int offset, int length,
                                             String chunkName, LuaValue envVal, LuaTable globals, Environment rootEnv) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes, offset, length);
        DataInputStream in = new DataInputStream(bais);
        boolean isVararg = in.readBoolean();
        boolean isStripped = in.readBoolean();
        String source = in.readUTF();
        int lineDefined = in.readInt();
        int lastLineDefined = in.readInt();

        int paramCount = in.readInt();
        List<String> params = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            params.add(in.readUTF());
        }

        int upCount = in.readInt();
        List<String> upvalueNames = new ArrayList<>(upCount);
        for (int i = 0; i < upCount; i++) {
            upvalueNames.add(in.readUTF());
        }

        int codeLen = in.readInt();
        if (bais.available() < codeLen) {
            throw new LuaException("truncated binary chunk");
        }
        byte[] codeBytes = new byte[codeLen];
        in.readFully(codeBytes);
        String code = new String(codeBytes, StandardCharsets.UTF_8);

        Lexer lexer = new Lexer(code);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens);
        Statements.BlockStmt body = parser.parse();

        LuaTable envTable = (envVal instanceof LuaTable t) ? t : globals;

        if (LuaState.USE_BYTECODE_VM) {
            String resolvedSource = chunkName != null ? chunkName : (isStripped ? "=?" : source);
            org.luava.runtime.bytecode.LuaProto proto = org.luava.runtime.bytecode.BytecodeCompiler.compile(body, resolvedSource, params, isVararg, lineDefined, lastLineDefined, upvalueNames);
            proto.rawSource = code;
            proto.body = body;

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
            org.luava.runtime.bytecode.LuaClosure cl = new org.luava.runtime.bytecode.LuaClosure(proto, upvals, envTable);
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

    public static LuaFunction undump(byte[] bytes, String chunkName, LuaValue envVal, LuaTable globals, Environment rootEnv) {
        // PUC's luaU_undump derives the name used in every "bad binary format"
        // message from the chunk name: '@'/'=' prefix stripped, a chunk that
        // starts with the signature byte becomes "binary string", anything
        // else is used as written.
        String name;
        if (chunkName != null && !chunkName.isEmpty()
                && (chunkName.charAt(0) == '@' || chunkName.charAt(0) == '=')) {
            name = chunkName.substring(1);
        } else if (chunkName != null && !chunkName.isEmpty()
                && chunkName.charAt(0) == HEADER[0]) {
            name = "binary string";
        } else {
            name = "binary string";
        }

        // Header validation in the same order and with the same wording as
        // lundump.c's checkHeader. Each field is first read (a short read is
        // "truncated chunk") and only then compared (a wrong value gets its own
        // message), which is what checkliteral / checksize / loadByte do.
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

        // Validate LUAC_INT and LUAC_NUM
        ByteBuffer bb = ByteBuffer.wrap(bytes, HEADER.length, 16).order(ByteOrder.LITTLE_ENDIAN);
        long intCheck = bb.getLong();
        double numCheck = bb.getDouble();
        if (intCheck != LUAC_INT) {
            throw new LuaException(name + ": bad binary format (integer format mismatch)");
        }
        if (numCheck != LUAC_NUM) {
            throw new LuaException(name + ": bad binary format (float format mismatch)");
        }

        // Check for LUAVA_V1 footer
        if (hasMagicFooter(bytes)) {
            ByteBuffer footBb = ByteBuffer.wrap(bytes, bytes.length - 12, 4).order(ByteOrder.LITTLE_ENDIAN);
            int payloadLen = footBb.getInt();
            if (payloadLen > 0 && bytes.length >= 12 + payloadLen) {
                int offset = bytes.length - 12 - payloadLen;
                try {
                    return decodePayload(bytes, offset, payloadLen, chunkName, envVal, globals, rootEnv);
                } catch (LuaException le) {
                    throw le;
                } catch (EOFException e) {
                    throw new LuaException("truncated binary chunk");
                } catch (Throwable e) {
                    throw new LuaException("corrupted chunk: " + e.getMessage());
                }
            }
        }

        try {
            int offset = HEADER.length + 16;
            return decodePayload(bytes, offset, bytes.length - offset, chunkName, envVal, globals, rootEnv);
        } catch (LuaException le) {
            throw le;
        } catch (EOFException e) {
            throw new LuaException("truncated binary chunk");
        } catch (Throwable e) {
            throw new LuaException("corrupted chunk: " + e.getMessage());
        }
    }
}
