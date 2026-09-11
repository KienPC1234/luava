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
        globals.rawset(LuaString.valueOf("os"), os);
    }

    public static void fillInto(LuaTable os, LuaTable globals) {

        os.rawset(LuaString.valueOf("clock"), LuaFunction.of(args -> {
            double secs = (System.nanoTime() - START_NANO) / 1_000_000_000.0;
            return LuaFloat.valueOf(secs);
        }));

        os.rawset(LuaString.valueOf("time"), LuaFunction.of(args -> {
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
            t.rawset(LuaString.valueOf("year"), LuaInteger.valueOf(r[1]));
            t.rawset(LuaString.valueOf("month"), LuaInteger.valueOf(r[2] + 1));
            t.rawset(LuaString.valueOf("day"), LuaInteger.valueOf(r[3]));
            t.rawset(LuaString.valueOf("hour"), LuaInteger.valueOf(r[4]));
            t.rawset(LuaString.valueOf("min"), LuaInteger.valueOf(r[5]));
            t.rawset(LuaString.valueOf("sec"), LuaInteger.valueOf(r[6]));
            long rdays = OsTime.daysFromCivil(r[1], r[2] + 1, r[3]);
            t.rawset(LuaString.valueOf("yday"), LuaInteger.valueOf(OsTime.dayOfYear(r[1], r[2] + 1, r[3])));
            t.rawset(LuaString.valueOf("wday"), LuaInteger.valueOf(OsTime.dayOfWeek(rdays) % 7 + 1));
            t.rawset(LuaString.valueOf("isdst"), LuaBoolean.valueOf(r[7] > 0));
            return LuaInteger.valueOf(r[0]);
        }));

        os.rawset(LuaString.valueOf("difftime"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'os.difftime'");
            double t1 = args[0].toDouble();
            double t2 = args[1].toDouble();
            return LuaFloat.valueOf(t1 - t2);
        }));

        os.rawset(LuaString.valueOf("date"), LuaFunction.of(args -> {
            String fmt = "%c";
            if (args.length > 0 && !args[0].isNil()) {
                if (!args[0].isString()) {
                    throw new LuaException("bad argument #1 to 'os.date' (string expected, got " + args[0].typeName() + ")");
                }
                fmt = args[0].toLuaString();
            }

            long epochSec;
            if (args.length > 1 && !args[1].isNil()) {
                if (!args[1].isInteger() && !args[1].isFloat()) {
                    throw new LuaException("bad argument #2 to 'os.date' (number expected, got " + args[1].typeName() + ")");
                }
                epochSec = args[1].toLong();
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
                t.rawset(LuaString.valueOf("year"), LuaInteger.valueOf(dispYear));
                t.rawset(LuaString.valueOf("month"), LuaInteger.valueOf(f[1]));
                t.rawset(LuaString.valueOf("day"), LuaInteger.valueOf(f[2]));
                t.rawset(LuaString.valueOf("hour"), LuaInteger.valueOf(f[3]));
                t.rawset(LuaString.valueOf("min"), LuaInteger.valueOf(f[4]));
                t.rawset(LuaString.valueOf("sec"), LuaInteger.valueOf(f[5]));
                t.rawset(LuaString.valueOf("wday"), LuaInteger.valueOf(OsTime.dayOfWeek(f[6]) % 7 + 1));
                t.rawset(LuaString.valueOf("yday"), LuaInteger.valueOf(OsTime.dayOfYear(f[0], f[1], f[2])));
                t.rawset(LuaString.valueOf("isdst"), LuaBoolean.valueOf(dst));
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

        os.rawset(LuaString.valueOf("getenv"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) return LuaNil.NIL;
            String val = System.getenv(args[0].toLuaString());
            return (val != null) ? LuaString.valueOf(val) : LuaNil.NIL;
        }));

        os.rawset(LuaString.valueOf("execute"), LuaFunction.of(args -> {
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
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf("interrupted"));
                }
            } catch (Exception e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()));
            }
        }));

        os.rawset(LuaString.valueOf("exit"), LuaFunction.of(args -> {
            int code = (args.length > 0 && args[0].isInteger()) ? (int) args[0].toLong() : 0;
            System.exit(code);
            return LuaNil.NIL;
        }));

        os.rawset(LuaString.valueOf("remove"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'os.remove' (string expected)");
            }
            String filename = args[0].toLuaString();
            try {
                boolean deleted = Files.deleteIfExists(Path.of(filename));
                if (deleted) return LuaBoolean.TRUE;
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(filename + ": No such file or directory"), LuaInteger.valueOf(2));
            } catch (IOException e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()));
            }
        }));

        os.rawset(LuaString.valueOf("rename"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'os.rename'");
            String oldName = args[0].toLuaString();
            String newName = args[1].toLuaString();
            try {
                Files.move(Path.of(oldName), Path.of(newName), StandardCopyOption.REPLACE_EXISTING);
                return LuaBoolean.TRUE;
            } catch (IOException e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()));
            }
        }));

        os.rawset(LuaString.valueOf("tmpname"), LuaFunction.of(args -> {
            try {
                Path p = Files.createTempFile("lua_", "");
                return LuaString.valueOf(p.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new LuaException("cannot generate temporary name: " + e.getMessage());
            }
        }));

        os.rawset(LuaString.valueOf("setlocale"), LuaFunction.of(args -> {
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
                return LuaString.valueOf("C");
            }
            return LuaNil.NIL;
        }));
    }
}
