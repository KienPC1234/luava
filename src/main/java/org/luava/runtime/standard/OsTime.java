/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;

/**
 * Pure-Java replacement for the C library time calls (mktime, localtime,
 * strftime) previously reached through the incubator FFM API. All date
 * math is 64-bit proleptic-Gregorian civil arithmetic (Howard Hinnant's
 * algorithms), so huge years work; zone offsets come from {@link ZoneRules}
 * with a standard-offset fallback past the rules range.
 */
final class OsTime {
    private OsTime() {}

    static final long SECS_PER_DAY = 86400L;

    // glibc mktime keeps a static "localtime_offset" guess between invocations
    // and starts each probe from it (t0 = wall - off_guess), so results can
    // depend on the previous call. Mirror that per zone to match the C binary.
    private static final java.util.Map<ZoneId, Integer> OFFSET_GUESS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Days since 1970-01-01 (all 64-bit). Month is 1-12. */
    static long daysFromCivil(long y, long m, long d) {
        y -= (m <= 2) ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long mp = (m + 9) % 12;
        long doy = (153 * mp + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Inverse: {year, month 1-12, day}. */
    static long[] civilFromDays(long z) {
        z += 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        return new long[]{m <= 2 ? y + 1 : y, m, d};
    }

    /** Monday=1..Sunday=7 for days since epoch. */
    static int dayOfWeek(long days) {
        return (int) (Math.floorMod(days + 3, 7) + 1);
    }

    static boolean isLeap(long y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    static int dayOfYear(long y, long m, long d) {
        return (int) (daysFromCivil(y, m, d) - daysFromCivil(y, 1, 1) + 1);
    }

    static int daysInMonth(long y, long m) {
        return switch ((int) m) {
            case 2 -> isLeap(y) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    /** Seconds east of UTC for epochSec; near-date standard offset past the rules range. */
    static int zoneOffsetSecs(ZoneId zone, long epochSec) {
        ZoneRules rules = zone.getRules();
        try {
            return rules.getOffset(Instant.ofEpochSecond(epochSec)).getTotalSeconds();
        } catch (Exception e) {
            // Past java.time range: use the standard offset near the date
            // (clamped into Instant range), not the 1970 one.
            long clamped = Math.max(-8640000000000000L, Math.min(8640000000000000L, epochSec));
            try {
                return rules.getStandardOffset(Instant.ofEpochSecond(clamped)).getTotalSeconds();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /** Local civil fields for an epoch: {y, mon, d, h, min, s, days, todSecs}. */
    static long[] localFields(ZoneId zone, long epochSec) {
        int off = zoneOffsetSecs(zone, epochSec);
        long local = epochSec + off;
        long days = Math.floorDiv(local, SECS_PER_DAY);
        long tod = Math.floorMod(local, SECS_PER_DAY);
        long[] ymd = civilFromDays(days);
        return new long[]{ymd[0], ymd[1], ymd[2], tod / 3600, (tod % 3600) / 60, tod % 60, days, tod};
    }

    static boolean isDst(ZoneId zone, long epochSec) {
        ZoneRules rules = zone.getRules();
        try {
            Instant inst = Instant.ofEpochSecond(epochSec);
            // Past the finite transition list the rules go fixed; derive
            // DST-ness from the recurring rules instead.
            int guess = rules.getStandardOffset(inst).getTotalSeconds();
            long local = epochSec + guess;
            long[] ymd = civilFromDays(Math.floorDiv(local, SECS_PER_DAY));
            if (ymd[0] >= -999999999L && ymd[0] <= 999999999L) {
                java.time.LocalDateTime noon = java.time.LocalDateTime.of(
                        (int) ymd[0], (int) ymd[1], (int) ymd[2], 12, 0);
                if (recurringOffset(rules, noon) != null) {
                    ZoneOffset dstOff = dstOffsetFromRules(rules, (int) ymd[0]);
                    if (dstOff != null) {
                        return recurringOffset(rules, noon).getTotalSeconds()
                                == dstOff.getTotalSeconds();
                    }
                }
            }
            return rules.isDaylightSavings(inst);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * C mktime equivalent. Inputs use struct-tm conventions (year = full
     * year, mon 0-based, other fields raw, any int magnitude — overflow
     * normalizes). Returns {epoch, year, mon0, mday, hour, min, sec, isdst}.
     * Throws LuaException "cannot be represented" when the normalized
     * tm_year leaves int32 range (mirrors glibc) or arithmetic overflows.
     */
    static long[] mktime(ZoneId zone, long year, long mon, long day,
                         long hour, long min, long sec, int isdst) {
        long y = year + Math.floorDiv(mon, 12);
        long m0 = Math.floorMod(mon, 12);
        long days;
        try {
            days = Math.addExact(daysFromCivil(y, m0 + 1, 1), day - 1);
        } catch (ArithmeticException e) {
            throw new org.luava.runtime.LuaException(
                "time result cannot be represented in this installation");
        }
        long secReq = sec;
        long secClamped = Math.max(0, Math.min(59, sec));
        long total;
        try {
            total = Math.addExact(Math.multiplyExact(days, SECS_PER_DAY),
                Math.addExact(hour * 3600L + min * 60L, secClamped));
        } catch (ArithmeticException e) {
            throw new org.luava.runtime.LuaException(
                "time result cannot be represented in this installation");
        }
        long[] ymd = civilFromDays(Math.floorDiv(total, SECS_PER_DAY));
        if (ymd[0] - 1900 > Integer.MAX_VALUE || ymd[0] - 1900 < Integer.MIN_VALUE) {
            throw new org.luava.runtime.LuaException(
                "time result cannot be represented in this installation");
        }
        long epoch;
        if (isdst < 0) {
            // Faithful glibc probe (mktime.c): clamp tm_sec to [0,59], start
            // from the per-call offset guess, iterate localtime until the wall
            // time matches (accepting oscillation near gaps), then add back the
            // requested second overflow.
            long t = total - OFFSET_GUESS.getOrDefault(zone, 0);
            long prev = Long.MIN_VALUE;
            long prevPrev = Long.MIN_VALUE;
            long convT = t;
            for (int i = 0; i < 6; i++) {
                long[] f = localFields(zone, t);
                long w = f[6] * SECS_PER_DAY + f[7];
                if (w == total || t == prevPrev) {
                    convT = t;
                    break;
                }
                prevPrev = prev;
                prev = t;
                t += total - w;
            }
            OFFSET_GUESS.put(zone, zoneOffsetSecs(zone, convT));
            epoch = convT;
        } else {
            // Explicit flag: resolve with flag-aware semantics.
            LocalDate wall;
            try {
                wall = LocalDate.of(
                    ymd[0] < -999999999L ? -999999999 : ymd[0] > 999999999L ? 999999999 : (int) ymd[0],
                    (int) ymd[1], (int) ymd[2]);
            } catch (Exception e) {
                wall = null;
            }
            if (wall == null) {
                long clamped = Math.max(-8640000000000000L, Math.min(8640000000000000L, total));
                int stdOff;
                try {
                    stdOff = zone.getRules().getStandardOffset(
                        Instant.ofEpochSecond(clamped)).getTotalSeconds();
                } catch (Exception e) {
                    stdOff = 0;
                }
                epoch = total - stdOff;
            } else {
                long tod = Math.floorMod(total, SECS_PER_DAY);
                java.time.LocalDateTime ldt = wall.atTime(
                        (int) (tod / 3600), (int) ((tod % 3600) / 60), (int) (tod % 60));
                ZoneRules rl = zone.getRules();
                List<ZoneOffset> valid = rl.getValidOffsets(ldt);
                ZoneOffset chosen;
                boolean naturalDst;
                if (valid.isEmpty()) {
                    // Gap.
                    ZoneOffsetTransition tr = gapTransition(rl, ldt);
                    if (isdst > 0) {
                        ldt = ldt.minusSeconds(tr.getDuration().getSeconds());
                        chosen = tr.getOffsetBefore();
                    } else {
                        ldt = ldt.plusSeconds(tr.getDuration().getSeconds());
                        chosen = tr.getOffsetAfter();
                    }
                    naturalDst = dstness(rl, ldt, chosen);
                } else if (valid.size() > 1) {
                    // Overlap.
                    chosen = selectOverlap(rl, ldt, valid, isdst);
                    naturalDst = dstness(rl, ldt, chosen);
                } else {
                    chosen = valid.get(0);
                    ZoneOffset rec = recurringOffset(rl, ldt);
                    naturalDst = (rec != null)
                            ? recIsDst(rl, ldt, rec)
                            : dstness(rl, ldt, chosen);
                }
                epoch = total - chosen.getTotalSeconds();
                // Flag contradiction: shift to the requested side. If the zone
                // has a real DST offset (from its recurring rules, e.g. the
                // 30-minute Lord Howe saving) use it; otherwise assume 1 hour.
                if ((isdst > 0) != naturalDst) {
                    int cur = chosen.getTotalSeconds();
                    int stdOff;
                    try {
                        stdOff = rl.getStandardOffset(java.time.Instant.ofEpochSecond(
                            Math.max(-8640000000000000L, Math.min(8640000000000000L, epoch))))
                            .getTotalSeconds();
                    } catch (Exception e) {
                        stdOff = cur;
                    }
                    int want;
                    if (isdst == 0) {
                        want = stdOff;
                    } else {
                        ZoneOffset dstOff = dstOffsetFromRules(rl, ldt.getYear());
                        want = (dstOff != null && dstOff.getTotalSeconds() != stdOff)
                                ? dstOff.getTotalSeconds() : stdOff + 3600;
                    }
                    epoch += cur - want;
                }
            }
        }
        // glibc restores the requested tm_sec (out of [0,59]) after probing
        // against the clamped second, on both the auto and explicit paths.
        epoch += secReq - secClamped;

        // Normalized backfill from the real offset at the resolved instant
        // (glibc writes back localtime(t)).
        int realOff = zoneOffsetSecs(zone, epoch);
        boolean realDst = isDst(zone, epoch);
        long[] lf = isoFieldsForEpoch(epoch, realOff);
        // glibc requires the normalized tm_year to stay in int32 range; the
        // sec-overflow restore can push it past (e.g. year 2147485547, sec 60).
        if (lf[0] - 1900 > Integer.MAX_VALUE || lf[0] - 1900 < Integer.MIN_VALUE) {
            throw new org.luava.runtime.LuaException(
                "time result cannot be represented in this installation");
        }
        return new long[]{epoch, lf[0], lf[1] - 1, lf[2],
            (int) (lf[7] / 3600), (int) ((lf[7] % 3600) / 60), (int) (lf[7] % 60),
            realDst ? 1 : 0};
    }

    private static long[] isoFieldsForEpoch(long epoch, int off) {
        long local = epoch + off;
        long days = Math.floorDiv(local, SECS_PER_DAY);
        long tod = Math.floorMod(local, SECS_PER_DAY);
        long[] ymd = civilFromDays(days);
        return new long[]{ymd[0], ymd[1], ymd[2], days, tod, 0, 0, tod};
    }

    private static boolean dstness(ZoneRules rules, java.time.LocalDateTime ldt, ZoneOffset off) {
        try {
            return rules.isDaylightSavings(ldt.toInstant(off));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean recIsDst(ZoneRules rules, java.time.LocalDateTime ldt, ZoneOffset rec) {
        ZoneOffset dstOff = dstOffsetFromRules(rules, ldt.getYear());
        return dstOff != null && rec.getTotalSeconds() == dstOff.getTotalSeconds();
    }

    /** The gap transition containing a gap wall time (finite or recurring). */
    private static ZoneOffsetTransition gapTransition(ZoneRules rules, java.time.LocalDateTime ldt) {
        for (var t : rules.getTransitions()) {
            if (!ldt.isBefore(t.getDateTimeBefore()) && ldt.isBefore(t.getDateTimeAfter())) {
                return t;
            }
        }
        for (var rule : rules.getTransitionRules()) {
            for (int y = ldt.getYear() - 1; y <= ldt.getYear() + 1; y++) {
                ZoneOffsetTransition t;
                try {
                    t = rule.createTransition(y);
                } catch (Exception e) {
                    continue;
                }
                if (!t.isGap()) continue;
                if (!ldt.isBefore(t.getDateTimeBefore()) && ldt.isBefore(t.getDateTimeAfter())) {
                    return t;
                }
            }
        }
        return rules.getTransition(ldt);
    }

    /**
     * The zone's daylight-saving offset from its recurring transition rules
     * (works for any year, including past the finite transition list).
     * Returns null for fixed-offset zones.
     */
    static ZoneOffset dstOffsetFromRules(ZoneRules rules, int year) {
        ZoneOffset best = null;
        for (var rule : rules.getTransitionRules()) {
            ZoneOffsetTransition t;
            try {
                t = rule.createTransition(year);
            } catch (Exception e) {
                continue;
            }
            for (ZoneOffset o : new ZoneOffset[]{t.getOffsetBefore(), t.getOffsetAfter()}) {
                if (best == null || o.getTotalSeconds() > best.getTotalSeconds()) best = o;
            }
        }
        return best;
    }

    /**
     * Offset at a wall time using recurring rules (for dates past the finite
     * transition list, where getValidOffsets goes fixed). Null if no recurring
     * rules apply.
     */
    static ZoneOffset recurringOffset(ZoneRules rules, java.time.LocalDateTime wall) {
        if (rules.getTransitionRules().isEmpty()) return null;
        var finite = rules.getTransitions();
        if (!finite.isEmpty()) {
            var last = finite.get(finite.size() - 1);
            if (wall.isBefore(last.getDateTimeBefore())) return null; // finite data covers it
        }
        java.util.List<ZoneOffsetTransition> ts = new java.util.ArrayList<>();
        for (var rule : rules.getTransitionRules()) {
            try {
                ts.add(rule.createTransition(wall.getYear()));
                if (wall.getMonthValue() == 1) ts.add(rule.createTransition(wall.getYear() - 1));
            } catch (Exception ignored) {
            }
        }
        if (ts.isEmpty()) return null;
        ts.sort(java.util.Comparator.comparing(ZoneOffsetTransition::getDateTimeBefore));
        ZoneOffset off = ts.get(0).getOffsetBefore();
        for (var t : ts) {
            if (!wall.isBefore(t.getDateTimeBefore())) off = t.getOffsetAfter();
            else break;
        }
        return off;
    }

    private static ZoneOffset selectOverlap(ZoneRules rules, java.time.LocalDateTime ldt,
                                            List<ZoneOffset> valid, int isdst) {
        if (isdst < 0) {
            // C picks the first occurrence (earliest instant).
            ZoneOffset best = valid.get(0);
            long bestEpoch = ldt.toEpochSecond(best);
            for (int i = 1; i < valid.size(); i++) {
                long e = ldt.toEpochSecond(valid.get(i));
                if (e < bestEpoch) {
                    bestEpoch = e;
                    best = valid.get(i);
                }
            }
            return best;
        }
        for (ZoneOffset o : valid) {
            try {
                if (rules.isDaylightSavings(ldt.toInstant(o)) == (isdst > 0)) return o;
            } catch (Exception ignored) {
            }
        }
        return valid.get(0);
    }

    /** ISO week date: {isoYear, isoWeek 1-53, isoWeekday Mon=1..Sun=7}. */
    static long[] isoWeekDate(long days) {
        long dowMon0 = (dayOfWeek(days) + 6) % 7; // Mon=0
        long thursday = days + (3 - dowMon0);
        long isoYear = civilFromDays(thursday)[0];
        long week1Mon = daysFromCivil(isoYear, 1, 4)
                - ((dayOfWeek(daysFromCivil(isoYear, 1, 4)) + 6) % 7);
        long week = (days - week1Mon) / 7 + 1;
        return new long[]{isoYear, week, dayOfWeek(days)};
    }

    private static final String[] MONTHS = {"January", "February", "March", "April",
        "May", "June", "July", "August", "September", "October", "November", "December"};
    private static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday",
        "Thursday", "Friday", "Saturday", "Sunday"};

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    /**
     * Renders one validated strftime spec (with optional E/O modifier, e.g.
     * "%Y" or "%Oy"). Fields f are OsTime.localFields output; tmYear is the
     * int32 struct-tm year, dispYear its int-wrapped display year (glibc
     * renders huge years wrapped); offSecs feeds %z; zone feeds %Z.
     */
    static String formatSpec(String spec, long[] f, int tmYear, long dispYear,
                             int offSecs, ZoneId zone, boolean isUtc, boolean dst) {
        long y = f[0];
        long mon = f[1];
        long d = f[2];
        long hh = f[3];
        long mm = f[4];
        long ss = f[5];
        long days = f[6];
        char mod = (spec.length() == 3) ? spec.charAt(1) : 0;
        char c = spec.charAt(spec.length() - 1);
        // E/O alternative representations match the base one here (glibc
        // renders them identically in the C locale for these conversions).
        int dow = dayOfWeek(days); // Mon=1..Sun=7
        return switch (c) {
            case 'Y' -> Long.toString(dispYear);
            case 'y' -> pad2(Math.floorMod(tmYear, 100));
            case 'C' -> Long.toString(Math.floorDiv(dispYear, 100));
            case 'm' -> pad2(mon);
            case 'd' -> pad2(d);
            case 'e' -> (d < 10 ? " " + d : Long.toString(d));
            case 'j' -> String.format("%03d", dayOfYear(y, mon, d));
            case 'H' -> pad2(hh);
            case 'I' -> pad2((hh % 12 == 0) ? 12 : hh % 12);
            case 'M' -> pad2(mm);
            case 'S' -> pad2(ss);
            case 'p' -> (hh < 12 ? "AM" : "PM");
            case 'A' -> WEEKDAYS[dow - 1];
            case 'a' -> WEEKDAYS[dow - 1].substring(0, 3);
            case 'B' -> MONTHS[(int) mon - 1];
            case 'b', 'h' -> MONTHS[(int) mon - 1].substring(0, 3);
            case 'w' -> Long.toString(dow % 7);
            case 'u' -> Long.toString(dow);
            case 'U' -> pad2(weekNumber(y, days, true));
            case 'W' -> pad2(weekNumber(y, days, false));
            case 'V' -> pad2(isoWeekDate(days)[1]);
            case 'G' -> Long.toString((int) isoWeekDate(days)[0]);
            case 'g' -> pad2(Math.floorMod(isoWeekDate(days)[0], 100));
            case 'R' -> pad2(hh) + ":" + pad2(mm);
            case 'T' -> pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss);
            case 'r' -> pad2((hh % 12 == 0) ? 12 : hh % 12) + ":" + pad2(mm)
                    + ":" + pad2(ss) + (hh < 12 ? " AM" : " PM");
            case 'D' -> pad2(mon) + "/" + pad2(d) + "/" + pad2(Math.floorMod(tmYear, 100));
            case 'F' -> Long.toString(dispYear) + "-" + pad2(mon) + "-" + pad2(d);
            case 'x' -> pad2(mon) + "/" + pad2(d) + "/" + pad2(Math.floorMod(y, 100));
            case 'X' -> pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss);
            case 'c' -> WEEKDAYS[dow - 1].substring(0, 3) + " "
                    + MONTHS[(int) mon - 1].substring(0, 3) + " "
                    + (d < 10 ? " " + d : Long.toString(d)) + " "
                    + pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss) + " " + dispYear;
            case 'n' -> "\n";
            case 't' -> "\t";
            case '%' -> "%";
            case 'z' -> {
                int a = Math.abs(offSecs);
                yield String.format("%s%02d%02d", offSecs < 0 ? "-" : "+",
                        a / 3600, (a % 3600) / 60);
            }
            case 'Z' -> {
                // glibc strftime %Z: the DST-aware abbreviation from the
                // active timezone. Lua's `!` prefix formats with gmtime,
                // whose %Z is always "GMT" (not the ZoneOffset id "Z"), and
                // whose zone abbreviation comes from the *system* zone for
                // local time. Java's SHORT display name matches glibc for
                // the common zones (EST/EDT, CET/CEST, GMT/BST, IST, ...).
                if (isUtc) {
                    yield "GMT";
                }
                java.util.TimeZone tz = java.util.TimeZone.getTimeZone(zone);
                yield tz.getDisplayName(dst, java.util.TimeZone.SHORT);
            }
            default -> throw new IllegalStateException("unhandled spec " + spec + " mod=" + mod);
        };
    }

    /** Week number like strftime %U (Sunday start) / %W (Monday start). */
    static long weekNumber(long y, long days, boolean sundayStart) {
        long jan1 = daysFromCivil(y, 1, 1);
        int jan1DowSun0 = (dayOfWeek(jan1) % 7); // Sun=0
        int firstBoundary = sundayStart ? (7 - jan1DowSun0) % 7 : (8 - (jan1DowSun0 == 0 ? 7 : jan1DowSun0)) % 7;
        long doy0 = days - jan1; // 0-based
        if (doy0 < firstBoundary) return 0;
        return (doy0 - firstBoundary) / 7 + 1;
    }

    // ---- Shell execution with signal-death detection (replaces system()/pclose) ----
    //
    // Java's Process.exitValue cannot distinguish "exited with code 129"
    // from "killed by signal 1" (both report 129). The wrapper appends a
    // sentinel write: a process that dies by signal never writes it, while
    // one that exits normally always does. The sentinel lives in a temp
    // file so neither stdout nor stderr is polluted.

    record ShellRun(Process process, java.nio.file.Path sentinel) {}

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static ShellRun startShell(String cmd, boolean inheritIO) throws java.io.IOException {
        if (isWindows()) {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
            if (inheritIO) pb.inheritIO();
            return new ShellRun(pb.start(), null);
        }
        java.nio.file.Path sentinel = java.nio.file.Files.createTempFile("luava-sh", ".exit");
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", wrap(cmd, sentinel));
        if (inheritIO) pb.inheritIO();
        return new ShellRun(pb.start(), sentinel);
    }

    /**
     * Like {@link #startShell} but redirects the command's stdout to {@code out}.
     * Used by io.popen("r") with a FIFO so EOF follows POSIX popen semantics
     * (all writers closed) instead of the JDK pipe's early process-exit EOF.
     */
    static ShellRun startShellRedirect(String cmd, java.io.File out) throws java.io.IOException {
        java.nio.file.Path sentinel = java.nio.file.Files.createTempFile("luava-sh", ".exit");
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", wrap(cmd, sentinel));
        pb.redirectOutput(out);
        return new ShellRun(pb.start(), sentinel);
    }

    private static String wrap(String cmd, java.nio.file.Path sentinel) {
        // Run the command in a subshell so an inner `exit N` does not kill
        // our wrapper; the wrapper writes the real exit code to a temp file
        // (sentinel). `$$`/`exec`/signal deaths skip the sentinel: those
        // decode from the raw status, matching C system()/pclose.
        String body = (cmd == null || cmd.isEmpty()) ? ":" : cmd;
        return "( { " + body + "; }; );c=$?;echo $c > '" + sentinel + "';exit $c";
    }

    static void deleteSentinel(java.nio.file.Path sentinel) {
        if (sentinel != null) {
            try {
                java.nio.file.Files.deleteIfExists(sentinel);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Waits for the process and decodes the result like C system()/pclose:
     * {true,"exit",0} / {nil,"exit",code} / {nil,"signal",signo}.
     */
    static org.luava.runtime.Varargs finishShell(ShellRun run)
            throws InterruptedException {
        int code = run.process().waitFor();
        Integer sentinelCode = null;
        if (run.sentinel() != null) {
            try {
                String s = java.nio.file.Files.readString(run.sentinel()).trim();
                sentinelCode = Integer.parseInt(s);
            } catch (Exception ignored) {
            } finally {
                deleteSentinel(run.sentinel());
            }
        }
        if (sentinelCode != null) {
            if (sentinelCode == 0) {
                return org.luava.runtime.Varargs.of(org.luava.runtime.LuaBoolean.TRUE,
                        org.luava.runtime.LuaString.interned("exit"),
                        org.luava.runtime.LuaInteger.valueOf(0));
            }
            return org.luava.runtime.Varargs.of(org.luava.runtime.LuaNil.NIL,
                    org.luava.runtime.LuaString.interned("exit"),
                    org.luava.runtime.LuaInteger.valueOf(sentinelCode));
        }
        // No sentinel: died by signal (or exec'd away). Windows has no wrapper.
        if (code == 0) {
            return org.luava.runtime.Varargs.of(org.luava.runtime.LuaBoolean.TRUE,
                    org.luava.runtime.LuaString.interned("exit"),
                    org.luava.runtime.LuaInteger.valueOf(0));
        }
        if (!isWindows() && code > 128 && code - 128 <= 64) {
            return org.luava.runtime.Varargs.of(org.luava.runtime.LuaNil.NIL,
                    org.luava.runtime.LuaString.interned("signal"),
                    org.luava.runtime.LuaInteger.valueOf(code - 128));
        }
        return org.luava.runtime.Varargs.of(org.luava.runtime.LuaNil.NIL,
                org.luava.runtime.LuaString.interned("exit"),
                org.luava.runtime.LuaInteger.valueOf(code));
    }
}
