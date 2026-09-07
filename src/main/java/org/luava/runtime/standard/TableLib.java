package org.luava.runtime.standard;

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

public final class TableLib {
    private TableLib() {}

    public static void open(LuaTable globals) {
        LuaTable tableMod = new LuaTable();

        tableMod.rawset(LuaString.valueOf("insert"), LuaFunction.of(args -> {
            if (args.length < 2 || !args[0].isTable()) {
                throw new LuaException("bad argument to 'table.insert'");
            }
            LuaTable t = (LuaTable) args[0];
            int len = t.rawlen();
            if (args.length == 2) {
                t.rawset(LuaInteger.valueOf(len + 1), args[1]);
            } else {
                long pos = args[1].toLong();
                LuaValue val = args[2];
                for (long i = len; i >= pos; i--) {
                    t.rawset(LuaInteger.valueOf(i + 1), t.rawget(LuaInteger.valueOf(i)));
                }
                t.rawset(LuaInteger.valueOf(pos), val);
            }
            return LuaNil.NIL;
        }));

        tableMod.rawset(LuaString.valueOf("remove"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument to 'table.remove'");
            }
            LuaTable t = (LuaTable) args[0];
            int len = t.rawlen();
            long pos = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : len;
            if (pos < 1 || pos > len) return LuaNil.NIL;
            LuaValue removed = t.rawget(LuaInteger.valueOf(pos));
            for (long i = pos; i < len; i++) {
                t.rawset(LuaInteger.valueOf(i), t.rawget(LuaInteger.valueOf(i + 1)));
            }
            t.rawset(LuaInteger.valueOf(len), LuaNil.NIL);
            return removed;
        }));

        tableMod.rawset(LuaString.valueOf("concat"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument to 'table.concat'");
            }
            LuaTable t = (LuaTable) args[0];
            String sep = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "";
            long start = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : 1;
            long end = (args.length > 3 && !args[3].isNil()) ? args[3].toLong() : t.rawlen();

            StringBuilder sb = new StringBuilder();
            for (long i = start; i <= end; i++) {
                if (i > start) sb.append(sep);
                LuaValue val = t.rawget(LuaInteger.valueOf(i));
                if (val.isNil()) {
                    throw new LuaException("invalid value (nil) at index " + i + " in table.concat");
                }
                sb.append(val.toLuaString());
            }
            return LuaString.valueOf(sb.toString());
        }));

        tableMod.rawset(LuaString.valueOf("unpack"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument to 'table.unpack'");
            }
            LuaTable t = (LuaTable) args[0];
            long start = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : t.rawlen();

            if (start > end) return Varargs.EMPTY;
            int count = (int) (end - start + 1);
            LuaValue[] result = new LuaValue[count];
            for (int i = 0; i < count; i++) {
                result[i] = t.rawget(LuaInteger.valueOf(start + i));
            }
            return Varargs.of(result);
        }));

        tableMod.rawset(LuaString.valueOf("pack"), LuaFunction.of(args -> {
            LuaTable t = new LuaTable();
            for (int i = 0; i < args.length; i++) {
                t.rawset(LuaInteger.valueOf(i + 1), args[i]);
            }
            t.rawset(LuaString.valueOf("n"), LuaInteger.valueOf(args.length));
            return t;
        }));

        tableMod.rawset(LuaString.valueOf("sort"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument to 'table.sort'");
            }
            LuaTable t = (LuaTable) args[0];
            LuaFunction comp = (args.length > 1 && !args[1].isNil() && args[1].isFunction()) ? (LuaFunction) args[1] : null;
            int len = t.rawlen();
            List<LuaValue> items = new ArrayList<>(len);
            for (int i = 1; i <= len; i++) {
                items.add(t.rawget(LuaInteger.valueOf(i)));
            }

            items.sort((a, b) -> {
                if (comp != null) {
                    return comp.call(a, b).toBoolean() ? -1 : 1;
                }
                return a.luaLessThan(b) ? -1 : 1;
            });

            for (int i = 0; i < len; i++) {
                t.rawset(LuaInteger.valueOf(i + 1), items.get(i));
            }
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("table"), tableMod);
    }
}
