package org.luava.runtime.bytecode;

import org.luava.runtime.LuaValue;

public final class LuaProto {
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
    }
}
