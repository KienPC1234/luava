package org.luava.runtime.standard;

import org.luava.runtime.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public final class NativeProcess {
    private static final boolean AVAILABLE;
    public static final MethodHandle popen;
    public static final MethodHandle pclose;
    public static final MethodHandle fgetc;
    public static final MethodHandle fread;
    public static final MethodHandle fwrite;
    public static final MethodHandle fflush;
    public static final MethodHandle system;
    public static final MethodHandle mktime;
    public static final MethodHandle localtime_r;
    public static final MethodHandle gmtime_r;
    public static final MethodHandle strftime;
    public static final MethodHandle setlocale;

    public static final long TM_SEC = 0;
    public static final long TM_MIN = 4;
    public static final long TM_HOUR = 8;
    public static final long TM_MDAY = 12;
    public static final long TM_MON = 16;
    public static final long TM_YEAR = 20;
    public static final long TM_WDAY = 24;
    public static final long TM_YDAY = 28;
    public static final long TM_ISDST = 32;
    public static final long TM_SIZE = 64;

    static {
        boolean ok = false;
        MethodHandle mPopen = null, mPclose = null, mFgetc = null, mFread = null, mFwrite = null, mFflush = null, mSystem = null;
        MethodHandle mMktime = null, mLocaltime = null, mGmtime = null, mStrftime = null, mSetlocale = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            var popenSym = lookup.find("popen");
            var pcloseSym = lookup.find("pclose");
            var fgetcSym = lookup.find("fgetc");
            var freadSym = lookup.find("fread");
            var fwriteSym = lookup.find("fwrite");
            var fflushSym = lookup.find("fflush");
            var systemSym = lookup.find("system");
            var mktimeSym = lookup.find("mktime");
            var localtimeSym = lookup.find("localtime_r");
            var gmtimeSym = lookup.find("gmtime_r");
            var strftimeSym = lookup.find("strftime");
            var setlocaleSym = lookup.find("setlocale");
            if (popenSym.isPresent() && pcloseSym.isPresent() && fgetcSym.isPresent()
                    && freadSym.isPresent() && fwriteSym.isPresent() && fflushSym.isPresent() && systemSym.isPresent()) {
                mPopen = linker.downcallHandle(popenSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                mPclose = linker.downcallHandle(pcloseSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mFgetc = linker.downcallHandle(fgetcSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mFread = linker.downcallHandle(freadSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                mFwrite = linker.downcallHandle(fwriteSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                mFflush = linker.downcallHandle(fflushSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                mSystem = linker.downcallHandle(systemSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                if (mktimeSym.isPresent() && localtimeSym.isPresent() && gmtimeSym.isPresent() && strftimeSym.isPresent()) {
                    mMktime = linker.downcallHandle(mktimeSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                    mLocaltime = linker.downcallHandle(localtimeSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    mGmtime = linker.downcallHandle(gmtimeSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    mStrftime = linker.downcallHandle(strftimeSym.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                }
                if (setlocaleSym.isPresent()) {
                    mSetlocale = linker.downcallHandle(setlocaleSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                }
                var signalSym = lookup.find("signal");
                if (signalSym.isPresent()) {
                    try {
                        MethodHandle mSignal = linker.downcallHandle(signalSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                        MemorySegment res = (MemorySegment) mSignal.invokeExact(1, MemorySegment.NULL);
                    } catch (Throwable ignored) {}
                }
                ok = true;
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        AVAILABLE = ok;
        popen = mPopen;
        pclose = mPclose;
        fgetc = mFgetc;
        fread = mFread;
        fwrite = mFwrite;
        fflush = mFflush;
        system = mSystem;
        mktime = mMktime;
        localtime_r = mLocaltime;
        gmtime_r = mGmtime;
        strftime = mStrftime;
        setlocale = mSetlocale;
    }

    private NativeProcess() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static MemorySegment popen(String cmd, String mode) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cmdSeg = arena.allocateUtf8String(cmd);
            MemorySegment modeSeg = arena.allocateUtf8String(mode);
            return (MemorySegment) popen.invokeExact(cmdSeg, modeSeg);
        }
    }

    public static int system(String cmd) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cmdSeg = arena.allocateUtf8String(cmd);
            return (int) system.invokeExact(cmdSeg);
        }
    }

    public static long mktime(MemorySegment tm) throws Throwable {
        return (long) mktime.invokeExact(tm);
    }

    public static boolean localtime(long timeSec, MemorySegment tm) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tVal = arena.allocate(ValueLayout.JAVA_LONG);
            tVal.set(ValueLayout.JAVA_LONG, 0, timeSec);
            MemorySegment res = (MemorySegment) localtime_r.invokeExact(tVal, tm);
            return res.address() != 0;
        }
    }

    public static boolean gmtime(long timeSec, MemorySegment tm) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tVal = arena.allocate(ValueLayout.JAVA_LONG);
            tVal.set(ValueLayout.JAVA_LONG, 0, timeSec);
            MemorySegment res = (MemorySegment) gmtime_r.invokeExact(tVal, tm);
            return res.address() != 0;
        }
    }

    public static String strftime(String format, MemorySegment tm) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fmtSeg = arena.allocateUtf8String(format);
            MemorySegment buf = arena.allocate(256);
            long len = (long) strftime.invokeExact(buf, 256L, fmtSeg, tm);
            return new String(buf.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static String setlocale(int category, String locale) throws Throwable {
        if (setlocale == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment locSeg = locale != null ? arena.allocateUtf8String(locale) : MemorySegment.NULL;
            MemorySegment res = (MemorySegment) setlocale.invokeExact(category, locSeg);
            if (res.address() == 0) return null;
            return res.getUtf8String(0);
        }
    }

    public static int getTmSec(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_SEC); }
    public static void setTmSec(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_SEC, val); }

    public static int getTmMin(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_MIN); }
    public static void setTmMin(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_MIN, val); }

    public static int getTmHour(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_HOUR); }
    public static void setTmHour(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_HOUR, val); }

    public static int getTmMday(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_MDAY); }
    public static void setTmMday(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_MDAY, val); }

    public static int getTmMon(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_MON); }
    public static void setTmMon(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_MON, val); }

    public static int getTmYear(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_YEAR); }
    public static void setTmYear(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_YEAR, val); }

    public static int getTmWday(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_WDAY); }
    public static void setTmWday(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_WDAY, val); }

    public static int getTmYday(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_YDAY); }
    public static void setTmYday(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_YDAY, val); }

    public static int getTmIsdst(MemorySegment tm) { return tm.get(ValueLayout.JAVA_INT, TM_ISDST); }
    public static void setTmIsdst(MemorySegment tm, int val) { tm.set(ValueLayout.JAVA_INT, TM_ISDST, val); }

    public static Varargs execResult(int stat) {
        String what = "exit";
        int code = stat;
        if ((stat & 0x7f) == 0) {
            code = (stat >> 8) & 0xff;
        } else {
            what = "signal";
            code = stat & 0x7f;
        }
        if ("exit".equals(what) && code == 0) {
            return Varargs.of(LuaBoolean.TRUE, LuaString.valueOf("exit"), LuaInteger.valueOf(0));
        } else {
            return Varargs.of(LuaNil.NIL, LuaString.valueOf(what), LuaInteger.valueOf(code));
        }
    }
}
