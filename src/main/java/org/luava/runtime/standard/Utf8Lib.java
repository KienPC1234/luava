package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class Utf8Lib {
    private Utf8Lib() {}

    public static void open(LuaTable globals) {
        LuaTable utf8 = new LuaTable();

        utf8.rawset(LuaString.valueOf("charpattern"), LuaString.valueOf("[\0-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"));

        utf8.rawset(LuaString.valueOf("char"), LuaFunction.of(args -> {
            StringBuilder sb = new StringBuilder();
            for (LuaValue arg : args) {
                int codePoint = (int) arg.toLong();
                if (!Character.isValidCodePoint(codePoint)) {
                    throw new LuaException("value out of range in 'utf8.char'");
                }
                sb.append(Character.toChars(codePoint));
            }
            return LuaString.valueOf(sb.toString());
        }));

        utf8.rawset(LuaString.valueOf("len"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'utf8.len'");
            String s = args[0].toLuaString();
            return LuaInteger.valueOf(s.codePointCount(0, s.length()));
        }));

        utf8.rawset(LuaString.valueOf("codepoint"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'utf8.codepoint'");
            String s = args[0].toLuaString();
            int len = s.length();
            long start = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : start;

            if (start < 1) start = 1;
            if (end > len) end = len;
            if (start > end) return Varargs.EMPTY;

            List<LuaValue> cps = new ArrayList<>();
            for (int i = (int) start - 1; i < (int) end; ) {
                int cp = s.codePointAt(i);
                cps.add(LuaInteger.valueOf(cp));
                i += Character.charCount(cp);
            }
            return Varargs.of(cps.toArray(new LuaValue[0]));
        }));

        globals.rawset(LuaString.valueOf("utf8"), utf8);
    }
}
