/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

public final class OsLib {
    private static final long START_NANO = System.nanoTime();
    private static final String STRFTIME_OP1 = "aAbBcCdDeFgGhHIjmMnprRStTuUVwWxXyYzZ%";
    private static final Set<String> STRFTIME_OP2 = Set.of(
        "Ec", "EC", "Ex", "EX", "Ey", "EY",
        "Od", "Oe", "OH", "OI", "Om", "OM", "OS", "Ou", "OU", "OV", "Ow", "OW", "Oy"
    );
    private static final String[] CAT_NAMES = {"all", "collate", "ctype", "monetary", "numeric", "time"};

    private OsLib() {}

    private static LuaInteger toIntegerX(LuaValue val) {
        if (val.isInteger()) return (LuaInteger) val;
        if (val.isFloat()) {
            double d = val.toDouble();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d >= -9223372036854775808.0 && d < 9223372036854775808.0 && Math.floor(d) == d) {
                return LuaInteger.valueOf((long) d);
            }
            return null;
        }
        if (val.isString()) {
            LuaValue parsed = LuaValue.parseNumber(val.toLuaString());
            if (parsed != null) {
                return toIntegerX(parsed);
            }
        }
        return null;
    }

    private static int getField(LuaTable t, String key, int d, int delta) {
        LuaValue val = t.rawget(LuaString.valueOf(key));
        if (val.isNil()) {
            if (d < 0) {
                throw new LuaException("field '" + key + "' missing in date table");
            }
            return d;
        }
        LuaInteger iVal = toIntegerX(val);
        if (iVal == null) {
            throw new LuaException("field '" + key + "' is not an integer");
        }
        long res = iVal.toLong();
        boolean ok;
        if (res >= 0) {
            ok = (res - delta <= Integer.MAX_VALUE);
        } else {
            ok = (Integer.MIN_VALUE + (long) delta <= res);
        }
        if (!ok) {
            throw new LuaException("field '" + key + "' is out-of-bound");
        }
        return (int) (res - delta);
    }

    private static int getBoolField(LuaTable t, String key) {
        LuaValue val = t.rawget(LuaString.valueOf(key));
        if (val.isNil()) {
            return -1;
        }
        return val.toBoolean() ? 1 : 0;
    }

    public static void open(LuaTable globals) {
        LuaTable os = new LuaTable();
        fillInto(os, globals);
        globals.rawset(LuaString.interned("os"), os);
    }

    public static void fillInto(LuaTable os, LuaTable globals) {

        os.rawset(LuaString.interned("clock"), LuaFunction.of(args -> {
            double secs = (System.nanoTime() - START_NANO) / 1_000_000_000.0;
            return LuaFloat.valueOf(secs);
        }));

        os.rawset(LuaString.interned("time"), LuaFunction.of(args -> {
            if (args.length == 0 || args[0].isNil()) {
                return LuaInteger.valueOf(System.currentTimeMillis() / 1000L);
            }
            if (!(args[0] instanceof LuaTable t)) {
                throw new LuaException("bad argument #1 to 'os.time' (table expected, got " + args[0].typeName() + ")");
            }

            int year = getField(t, "year", -1, 1900);
            int month = getField(t, "month", -1, 1);
            int day = getField(t, "day", -1, 0);
            int hour = getField(t, "hour", 12, 0);
            int min = getField(t, "min", 0, 0);
            int sec = getField(t, "sec", 0, 0);
            int isdst = getBoolField(t, "isdst");

            long[] r = OsTime.mktime(ZoneId.systemDefault(), year + 1900L, month,
                    day, hour, min, sec, isdst);
            t.rawset(LuaString.interned("year"), LuaInteger.valueOf(r[1]));
            t.rawset(LuaString.interned("month"), LuaInteger.valueOf(r[2] + 1));
            t.rawset(LuaString.interned("day"), LuaInteger.valueOf(r[3]));
            t.rawset(LuaString.interned("hour"), LuaInteger.valueOf(r[4]));
            t.rawset(LuaString.interned("min"), LuaInteger.valueOf(r[5]));
            t.rawset(LuaString.interned("sec"), LuaInteger.valueOf(r[6]));
            long rdays = OsTime.daysFromCivil(r[1], r[2] + 1, r[3]);
            t.rawset(LuaString.interned("yday"), LuaInteger.valueOf(OsTime.dayOfYear(r[1], r[2] + 1, r[3])));
            t.rawset(LuaString.interned("wday"), LuaInteger.valueOf(OsTime.dayOfWeek(rdays) % 7 + 1));
            t.rawset(LuaString.interned("isdst"), LuaBoolean.valueOf(r[7] > 0));
            return LuaInteger.valueOf(r[0]);
        }));

        os.rawset(LuaString.interned("difftime"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'os.difftime'");
            // l_checktime -> luaL_checkinteger: both operands coerce numeric
            // strings and blame their argument index on failure.
            LuaInteger i1 = args[0].toLuaIntegerCoercingStrings();
            if (i1 == null) {
                throw new LuaException("bad argument #1 to 'difftime' (" + args[0].integerConversionError() + ")");
            }
            LuaInteger i2 = args[1].toLuaIntegerCoercingStrings();
            if (i2 == null) {
                throw new LuaException("bad argument #2 to 'difftime' (" + args[1].integerConversionError() + ")");
            }
            return LuaFloat.valueOf((double) (i1.toLong() - i2.toLong()));
        }));

        os.rawset(LuaString.interned("date"), LuaFunction.of(args -> {
            String fmt = "%c";
            if (args.length > 0 && !args[0].isNil()) {
                // luaL_optlstring: a number is coerced to its Lua string form,
                // so os.date(123) formats the string "123".
                if (!args[0].isString() && !args[0].isNumber()) {
                    throw new LuaException("bad argument #1 to 'os.date' (string expected, got " + args[0].typeName() + ")");
                }
                fmt = args[0].toLuaString();
            }

            long epochSec;
            if (args.length > 1 && !args[1].isNil()) {
                // l_checktime -> luaL_checkinteger: coerces a numeric string
                // and blames argument #2 with the integer-representation text.
                org.luava.runtime.LuaInteger i = args[1].toLuaIntegerCoercingStrings();
                if (i == null) {
                    throw new LuaException("bad argument #2 to 'date' (" + args[1].integerConversionError() + ")");
                }
                epochSec = i.toLong();
            } else {
                epochSec = System.currentTimeMillis() / 1000L;
            }

            boolean isUtc = false;
            int sIdx = 0;
            int slen = fmt.length();
            if (sIdx < slen && fmt.charAt(sIdx) == '!') {
                isUtc = true;
                sIdx++;
            }

            ZoneId zone = isUtc ? ZoneOffset.UTC : ZoneId.systemDefault();
            long[] f = OsTime.localFields(zone, epochSec);
            // glibc localtime fails when tm_year leaves int32 range; when it
            // fits, huge years render int-wrapped like glibc's int fields.
            if (f[0] - 1900 > Integer.MAX_VALUE || f[0] - 1900 < Integer.MIN_VALUE) {
                throw new LuaException("date result cannot be represented in this installation");
            }
            int tmYear = (int) (f[0] - 1900);
            // glibc renders huge years int-wrapped (full-year 32-bit wrap).
            long dispYear = (int) f[0];
            int offSecs = OsTime.zoneOffsetSecs(zone, epochSec);
            boolean dst = OsTime.isDst(zone, epochSec);

            if (fmt.startsWith("*t", sIdx) && fmt.length() == sIdx + 2) {
                LuaTable t = new LuaTable();
                t.rawset(LuaString.interned("year"), LuaInteger.valueOf(dispYear));
                t.rawset(LuaString.interned("month"), LuaInteger.valueOf(f[1]));
                t.rawset(LuaString.interned("day"), LuaInteger.valueOf(f[2]));
                t.rawset(LuaString.interned("hour"), LuaInteger.valueOf(f[3]));
                t.rawset(LuaString.interned("min"), LuaInteger.valueOf(f[4]));
                t.rawset(LuaString.interned("sec"), LuaInteger.valueOf(f[5]));
                t.rawset(LuaString.interned("wday"), LuaInteger.valueOf(OsTime.dayOfWeek(f[6]) % 7 + 1));
                t.rawset(LuaString.interned("yday"), LuaInteger.valueOf(OsTime.dayOfYear(f[0], f[1], f[2])));
                t.rawset(LuaString.interned("isdst"), LuaBoolean.valueOf(dst));
                return t;
            }

            StringBuilder b = new StringBuilder();
            while (sIdx < slen) {
                char ch = fmt.charAt(sIdx);
                if (ch != '%') {
                    b.append(ch);
                    sIdx++;
                } else {
                    sIdx++; // skip '%'
                    int convStart = sIdx;
                    int convLen = slen - convStart;
                    String spec = null;
                    int opLen = 0;
                    if (convLen >= 1) {
                        char c = fmt.charAt(convStart);
                        if (STRFTIME_OP1.indexOf(c) >= 0) {
                            spec = "%" + c;
                            opLen = 1;
                        }
                    }
                    if (spec == null && convLen >= 2) {
                        String sub = fmt.substring(convStart, convStart + 2);
                        if (STRFTIME_OP2.contains(sub)) {
                            spec = "%" + sub;
                            opLen = 2;
                        }
                    }
                    if (spec == null) {
                        throw new LuaException("bad argument #1 to 'os.date' (invalid conversion specifier '%" + fmt.substring(convStart) + "')");
                    }
                    sIdx += opLen;
                    b.append(OsTime.formatSpec(spec, f, tmYear, dispYear, offSecs, zone));
                }
            }
            return LuaString.valueOf(b.toString());
        }));

        os.rawset(LuaString.interned("getenv"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) return LuaNil.NIL;
            String val = System.getenv(args[0].toLuaString());
            return (val != null) ? LuaString.valueOf(val) : LuaNil.NIL;
        }));

        os.rawset(LuaString.interned("execute"), LuaFunction.of(args -> {
            if (args.length == 0 || args[0].isNil()) {
                return LuaBoolean.TRUE;
            }
            String cmd = args[0].toLuaString();
            try {
                OsTime.ShellRun run = OsTime.startShell(cmd, true);
                try {
                    return OsTime.finishShell(run);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Varargs.of(LuaNil.NIL, LuaString.interned("interrupted"));
                }
            } catch (Exception e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()));
            }
        }));

        os.rawset(LuaString.interned("exit"), LuaFunction.of(args -> {
            int code;
            LuaValue first = (args.length > 0) ? args[0] : LuaNil.NIL;
            if (first.isBoolean()) {
                code = first.toBoolean() ? 0 : 1;
            } else if (first.isInteger()) {
                code = (int) first.toLong();
            } else {
                code = 0;
            }
            boolean close = args.length > 1 && args[1].toBoolean();
            if (close) {
                org.luava.runtime.LuaState.runExitFinalizers();
            }
            // Never call System.exit: that would kill an embedding server.
            // Unwind via an Error so pcall cannot swallow the exit request.
            throw new org.luava.runtime.LuaExit(code);
        }));

        os.rawset(LuaString.interned("remove"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'os.remove' (string expected)");
            }
            String filename = args[0].toLuaString();
            try {
                boolean deleted = Files.deleteIfExists(Path.of(filename));
                if (deleted) return LuaBoolean.TRUE;
                return fileResult(false, filename, 2);
            } catch (IOException e) {
                return fileResult(false, filename, errnoOf(e));
            }
        }));

        os.rawset(LuaString.interned("rename"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'os.rename'");
            String oldName = args[0].toLuaString();
            String newName = args[1].toLuaString();
            try {
                Files.move(Path.of(oldName), Path.of(newName), StandardCopyOption.REPLACE_EXISTING);
                return LuaBoolean.TRUE;
            } catch (IOException e) {
                // C's os.rename passes NULL as the file name, so the message
                // is just strerror(errno) with no path prefix.
                return fileResult(false, null, errnoOf(e));
            }
        }));

        os.rawset(LuaString.interned("tmpname"), LuaFunction.of(args -> {
            try {
                Path p = Files.createTempFile("lua_", "");
                return LuaString.valueOf(p.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new LuaException("cannot generate temporary name: " + e.getMessage());
            }
        }));

        os.rawset(LuaString.interned("setlocale"), LuaFunction.of(args -> {
            String loc = (args.length > 0 && !args[0].isNil()) ? args[0].toLuaString() : null;
            String catStr = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "all";
            boolean knownCat = false;
            for (String name : CAT_NAMES) {
                if (name.equals(catStr)) {
                    knownCat = true;
                    break;
                }
            }
            if (!knownCat) {
                throw new LuaException("bad argument #2 to 'os.setlocale' (invalid option '" + catStr + "')");
            }
            if (loc == null || "C".equals(loc) || "".equals(loc) || "POSIX".equalsIgnoreCase(loc)) {
                return LuaString.interned("C");
            }
            return LuaNil.NIL;
        }));
    }

    /**
     * Mirrors {@code luaL_fileresult}: on failure push {@code nil}, the
     * strerror-style message (prefixed with the file name only when one is
     * given) and the errno. Used by {@code os.remove} / {@code os.rename}.
     */
    private static Varargs fileResult(boolean ok, String fname, int errno) {
        if (ok) return Varargs.of(LuaBoolean.TRUE);
        String msg = strerror(errno);
        String text = (fname != null) ? fname + ": " + msg : msg;
        return Varargs.of(LuaNil.NIL, LuaString.valueOf(text), LuaInteger.valueOf(errno));
    }

    /** Best-effort mapping of a Java I/O failure to a POSIX errno. */
    private static int errnoOf(IOException e) {
        if (e instanceof java.nio.file.NoSuchFileException) return 2;   // ENOENT
        if (e instanceof java.nio.file.FileAlreadyExistsException) return 17; // EEXIST
        if (e instanceof java.nio.file.AccessDeniedException) return 13; // EACCES
        if (e instanceof java.nio.file.DirectoryNotEmptyException) return 39; // ENOTEMPTY
        return 2;
    }

    private static String strerror(int errno) {
        return switch (errno) {
            case 2 -> "No such file or directory";
            case 13 -> "Permission denied";
            case 17 -> "File exists";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            case 39 -> "Directory not empty";
            default -> "I/O error";
        };
    }
}
