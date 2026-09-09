package org.luava.runtime.standard;

import org.luava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public final class OsLib {
    private static final long START_NANO = System.nanoTime();
    private static final String STRFTIME_OP1 = "aAbBcCdDeFgGhHIjmMnprRStTuUVwWxXyYzZ%";
    private static final Set<String> STRFTIME_OP2 = Set.of(
        "Ec", "EC", "Ex", "EX", "Ey", "EY",
        "Od", "Oe", "OH", "OI", "Om", "OM", "OS", "Ou", "OU", "OV", "Ow", "OW", "Oy"
    );
    private static final int[] LC_CATS = {6, 3, 0, 4, 1, 2}; // ALL, COLLATE, CTYPE, MONETARY, NUMERIC, TIME
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

            if (NativeProcess.isAvailable() && NativeProcess.mktime != null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment tm = arena.allocate(NativeProcess.TM_SIZE);
                    NativeProcess.setTmYear(tm, year);
                    NativeProcess.setTmMon(tm, month);
                    NativeProcess.setTmMday(tm, day);
                    NativeProcess.setTmHour(tm, hour);
                    NativeProcess.setTmMin(tm, min);
                    NativeProcess.setTmSec(tm, sec);
                    NativeProcess.setTmIsdst(tm, isdst);

                    long res = NativeProcess.mktime(tm);
                    if (res == -1) {
                        throw new LuaException("time result cannot be represented in this installation");
                    }

                    t.rawset(LuaString.valueOf("year"), LuaInteger.valueOf(NativeProcess.getTmYear(tm) + 1900L));
                    t.rawset(LuaString.valueOf("month"), LuaInteger.valueOf(NativeProcess.getTmMon(tm) + 1L));
                    t.rawset(LuaString.valueOf("day"), LuaInteger.valueOf(NativeProcess.getTmMday(tm)));
                    t.rawset(LuaString.valueOf("hour"), LuaInteger.valueOf(NativeProcess.getTmHour(tm)));
                    t.rawset(LuaString.valueOf("min"), LuaInteger.valueOf(NativeProcess.getTmMin(tm)));
                    t.rawset(LuaString.valueOf("sec"), LuaInteger.valueOf(NativeProcess.getTmSec(tm)));
                    t.rawset(LuaString.valueOf("yday"), LuaInteger.valueOf(NativeProcess.getTmYday(tm) + 1L));
                    t.rawset(LuaString.valueOf("wday"), LuaInteger.valueOf(NativeProcess.getTmWday(tm) + 1L));
                    int normIsdst = NativeProcess.getTmIsdst(tm);
                    if (normIsdst >= 0) {
                        t.rawset(LuaString.valueOf("isdst"), LuaBoolean.valueOf(normIsdst > 0));
                    }

                    return LuaInteger.valueOf(res);
                } catch (LuaException le) {
                    throw le;
                } catch (Throwable th) {
                    throw new LuaException("time error: " + th.getMessage());
                }
            }

            LocalDateTime ldt = LocalDateTime.of(year + 1900, month + 1, day, hour, min, sec);
            long epochSec = ldt.atZone(ZoneId.systemDefault()).toEpochSecond();
            return LuaInteger.valueOf(epochSec);
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

            if (NativeProcess.isAvailable() && NativeProcess.localtime_r != null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment tm = arena.allocate(NativeProcess.TM_SIZE);
                    boolean ok = isUtc ? NativeProcess.gmtime(epochSec, tm) : NativeProcess.localtime(epochSec, tm);
                    if (!ok) {
                        throw new LuaException("date result cannot be represented in this installation");
                    }

                    if (fmt.startsWith("*t", sIdx) && fmt.length() == sIdx + 2) {
                        LuaTable t = new LuaTable();
                        t.rawset(LuaString.valueOf("year"), LuaInteger.valueOf(NativeProcess.getTmYear(tm) + 1900L));
                        t.rawset(LuaString.valueOf("month"), LuaInteger.valueOf(NativeProcess.getTmMon(tm) + 1L));
                        t.rawset(LuaString.valueOf("day"), LuaInteger.valueOf(NativeProcess.getTmMday(tm)));
                        t.rawset(LuaString.valueOf("hour"), LuaInteger.valueOf(NativeProcess.getTmHour(tm)));
                        t.rawset(LuaString.valueOf("min"), LuaInteger.valueOf(NativeProcess.getTmMin(tm)));
                        t.rawset(LuaString.valueOf("sec"), LuaInteger.valueOf(NativeProcess.getTmSec(tm)));
                        t.rawset(LuaString.valueOf("yday"), LuaInteger.valueOf(NativeProcess.getTmYday(tm) + 1L));
                        t.rawset(LuaString.valueOf("wday"), LuaInteger.valueOf(NativeProcess.getTmWday(tm) + 1L));
                        int isdst = NativeProcess.getTmIsdst(tm);
                        if (isdst >= 0) {
                            t.rawset(LuaString.valueOf("isdst"), LuaBoolean.valueOf(isdst > 0));
                        }
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
                            String formatted = NativeProcess.strftime(spec, tm);
                            b.append(formatted);
                        }
                    }
                    return LuaString.valueOf(b.toString());
                } catch (LuaException le) {
                    throw le;
                } catch (Throwable th) {
                    throw new LuaException("date error: " + th.getMessage());
                }
            }

            ZoneId zone = isUtc ? ZoneOffset.UTC : ZoneId.systemDefault();
            Instant instant;
            try {
                instant = Instant.ofEpochSecond(epochSec);
            } catch (Exception e) {
                throw new LuaException("date result cannot be represented in this installation");
            }
            LocalDateTime ldt = LocalDateTime.ofInstant(instant, zone);

            if (fmt.startsWith("*t", sIdx) && fmt.length() == sIdx + 2) {
                LuaTable t = new LuaTable();
                t.rawset(LuaString.valueOf("year"), LuaInteger.valueOf(ldt.getYear()));
                t.rawset(LuaString.valueOf("month"), LuaInteger.valueOf(ldt.getMonthValue()));
                t.rawset(LuaString.valueOf("day"), LuaInteger.valueOf(ldt.getDayOfMonth()));
                t.rawset(LuaString.valueOf("hour"), LuaInteger.valueOf(ldt.getHour()));
                t.rawset(LuaString.valueOf("min"), LuaInteger.valueOf(ldt.getMinute()));
                t.rawset(LuaString.valueOf("sec"), LuaInteger.valueOf(ldt.getSecond()));
                int wday = ldt.getDayOfWeek().getValue() % 7 + 1; // Lua: Sunday = 1
                t.rawset(LuaString.valueOf("wday"), LuaInteger.valueOf(wday));
                t.rawset(LuaString.valueOf("yday"), LuaInteger.valueOf(ldt.getDayOfYear()));
                t.rawset(LuaString.valueOf("isdst"), LuaBoolean.FALSE);
                return t;
            }

            String formatted = fmt
                    .replace("%Y", String.format("%04d", ldt.getYear()))
                    .replace("%y", String.format("%02d", ldt.getYear() % 100))
                    .replace("%m", String.format("%02d", ldt.getMonthValue()))
                    .replace("%d", String.format("%02d", ldt.getDayOfMonth()))
                    .replace("%H", String.format("%02d", ldt.getHour()))
                    .replace("%M", String.format("%02d", ldt.getMinute()))
                    .replace("%S", String.format("%02d", ldt.getSecond()))
                    .replace("%c", ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return LuaString.valueOf(formatted);
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
            if (NativeProcess.isAvailable()) {
                try {
                    int stat = NativeProcess.system(cmd);
                    return NativeProcess.execResult(stat);
                } catch (Throwable t) {
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf(t.getMessage()));
                }
            }
            try {
                Process proc = new ProcessBuilder("/bin/sh", "-c", cmd).inheritIO().start();
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    return Varargs.of(LuaBoolean.TRUE, LuaString.valueOf("exit"), LuaInteger.valueOf(0));
                } else {
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf("exit"), LuaInteger.valueOf(exitCode));
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
            int op = -1;
            for (int i = 0; i < CAT_NAMES.length; i++) {
                if (CAT_NAMES[i].equals(catStr)) {
                    op = i;
                    break;
                }
            }
            if (op < 0) {
                throw new LuaException("bad argument #2 to 'os.setlocale' (invalid option '" + catStr + "')");
            }
            if (NativeProcess.isAvailable() && NativeProcess.setlocale != null) {
                try {
                    String res = NativeProcess.setlocale(LC_CATS[op], loc);
                    return (res != null) ? LuaString.valueOf(res) : LuaNil.NIL;
                } catch (Throwable ignored) {
                }
            }
            if (loc == null || "C".equals(loc) || "".equals(loc) || "POSIX".equalsIgnoreCase(loc)) {
                return LuaString.valueOf("C");
            }
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("os"), os);
    }
}
