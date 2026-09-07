package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class StringLib {
    private StringLib() {}

    public static void open(LuaTable globals) {
        LuaTable stringTable = new LuaTable();

        stringTable.rawset(LuaString.valueOf("len"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.len'");
            return LuaInteger.valueOf(args[0].toLuaString().length());
        }));

        stringTable.rawset(LuaString.valueOf("lower"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.lower'");
            return LuaString.valueOf(args[0].toLuaString().toLowerCase());
        }));

        stringTable.rawset(LuaString.valueOf("upper"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.upper'");
            return LuaString.valueOf(args[0].toLuaString().toUpperCase());
        }));

        stringTable.rawset(LuaString.valueOf("reverse"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.reverse'");
            return LuaString.valueOf(new StringBuilder(args[0].toLuaString()).reverse().toString());
        }));

        stringTable.rawset(LuaString.valueOf("rep"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.rep'");
            String s = args[0].toLuaString();
            long n = args[1].toLong();
            if (n <= 0) return LuaString.valueOf("");
            return LuaString.valueOf(s.repeat((int) n));
        }));

        stringTable.rawset(LuaString.valueOf("sub"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.sub'");
            String s = args[0].toLuaString();
            int len = s.length();
            long start = args[1].toLong();
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : -1;

            if (start < 0) start = len + start + 1;
            if (end < 0) end = len + end + 1;

            if (start < 1) start = 1;
            if (end > len) end = len;

            if (start > end) return LuaString.valueOf("");
            return LuaString.valueOf(s.substring((int) start - 1, (int) end));
        }));

        stringTable.rawset(LuaString.valueOf("byte"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.byte'");
            String s = args[0].toLuaString();
            int len = s.length();
            long start = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : start;

            if (start < 0) start = len + start + 1;
            if (end < 0) end = len + end + 1;
            if (start < 1) start = 1;
            if (end > len) end = len;
            if (start > end) return Varargs.EMPTY;

            List<LuaValue> bytes = new ArrayList<>();
            for (int i = (int) start - 1; i < (int) end; i++) {
                bytes.add(LuaInteger.valueOf((int) s.charAt(i)));
            }
            return Varargs.of(bytes.toArray(new LuaValue[0]));
        }));

        stringTable.rawset(LuaString.valueOf("char"), LuaFunction.of(args -> {
            StringBuilder sb = new StringBuilder();
            for (LuaValue arg : args) {
                sb.append((char) arg.toLong());
            }
            return LuaString.valueOf(sb.toString());
        }));

        stringTable.rawset(LuaString.valueOf("format"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.format'");
            String fmt = args[0].toLuaString();
            Object[] javaArgs = new Object[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                LuaValue v = args[i];
                if (v.isInteger()) javaArgs[i - 1] = v.toLong();
                else if (v.isFloat()) javaArgs[i - 1] = v.toDouble();
                else if (v.isBoolean()) javaArgs[i - 1] = v.toBoolean();
                else javaArgs[i - 1] = v.toLuaString();
            }
            return LuaString.valueOf(String.format(fmt, javaArgs));
        }));

        globals.rawset(LuaString.valueOf("string"), stringTable);
    }
}
