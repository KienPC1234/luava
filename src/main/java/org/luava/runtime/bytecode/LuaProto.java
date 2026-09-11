/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

import org.luava.runtime.LuaValue;

public final class LuaProto {
    public final String name;
    public final String source;
    public final int lineDefined;
    public final int lastLineDefined;
    public final int numParams;
    public final boolean isVararg;
    public final int maxStackSize;

    public final int[] code;
    public final LuaValue[] constants;
    public final LuaProto[] protos;
    public final UpvalueDesc[] upvalues;
    public final int[] lineInfo;
    public final String[] locVars;

    public record LocVarInfo(String name, int reg, int startPc, int endPc) {}
    public final LocVarInfo[] locVarInfos;
    public String rawSource;
    public org.luava.frontend.ast.Statements.BlockStmt body;

    public String findLocalVarName(int reg, int pc) {
        if (locVarInfos == null) return null;
        for (int i = locVarInfos.length - 1; i >= 0; i--) {
            LocVarInfo info = locVarInfos[i];
            if (info.reg() == reg && info.startPc() <= pc && (info.endPc() == -1 || pc <= info.endPc())) {
                return info.name();
            }
        }
        return null;
    }

    public LuaProto(
            String name,
            String source,
            int lineDefined,
            int lastLineDefined,
            int numParams,
            boolean isVararg,
            int maxStackSize,
            int[] code,
            LuaValue[] constants,
            LuaProto[] protos,
            UpvalueDesc[] upvalues,
            int[] lineInfo,
            String[] locVars,
            LocVarInfo[] locVarInfos
    ) {
        this.name = name;
        this.source = source != null ? source : "=?";
        this.lineDefined = lineDefined;
        this.lastLineDefined = lastLineDefined;
        this.numParams = numParams;
        this.isVararg = isVararg;
        this.maxStackSize = Math.max(2, maxStackSize);
        this.code = code != null ? code : new int[0];
        this.constants = constants != null ? constants : new LuaValue[0];
        this.protos = protos != null ? protos : new LuaProto[0];
        this.upvalues = upvalues != null ? upvalues : new UpvalueDesc[0];
        this.lineInfo = lineInfo != null ? lineInfo : new int[0];
        this.locVars = locVars != null ? locVars : new String[0];
        this.locVarInfos = locVarInfos != null ? locVarInfos : new LocVarInfo[0];
    }

    public LuaProto(
            String name,
            String source,
            int lineDefined,
            int lastLineDefined,
            int numParams,
            boolean isVararg,
            int maxStackSize,
            int[] code,
            LuaValue[] constants,
            LuaProto[] protos,
            UpvalueDesc[] upvalues,
            int[] lineInfo,
            String[] locVars
    ) {
        this(name, source, lineDefined, lastLineDefined, numParams, isVararg, maxStackSize, code, constants, protos, upvalues, lineInfo, locVars, null);
    }

    public LuaProto(
            String source,
            int lineDefined,
            int lastLineDefined,
            int numParams,
            boolean isVararg,
            int maxStackSize,
            int[] code,
            LuaValue[] constants,
            LuaProto[] protos,
            UpvalueDesc[] upvalues,
            int[] lineInfo,
            String[] locVars
    ) {
        this(null, source, lineDefined, lastLineDefined, numParams, isVararg, maxStackSize, code, constants, protos, upvalues, lineInfo, locVars, null);
    }
}
