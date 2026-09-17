/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class LuaPattern {
    private LuaPattern() {}

    private static final int LUA_MAXCAPTURES = 32;
    private static final int MAXCCALLS = 200;
    private static final int CAP_UNFINISHED = -1;
    private static final int CAP_POSITION = -2;
    private static final byte L_ESC = '%';

    private static final class Capture {
        int init;
        int len;
    }

    private static final class MatchState {
        final byte[] src;
        final int srcLen;
        final byte[] p;
        final int pLen;
        int matchdepth;
        int level;
        int matchTicks;
        // Allocated on first capture use: capture-less patterns (the common
        // case, e.g. gmatch("%a+")) never pay for the 32-slot array.
        Capture[] capture;

        MatchState(byte[] src, byte[] p) {
            this.src = src;
            this.srcLen = src.length;
            this.p = p;
            this.pLen = p.length;
            this.matchdepth = MAXCCALLS;
            this.level = 0;
            // capture[] slots stay null until first capture use (capAt);
            // capture-less patterns never allocate them.
        }

        void reprepstate() {
            this.level = 0;
            this.matchdepth = MAXCCALLS;
        }
    }

    private static int classend(MatchState ms, int p) {
        byte c = ms.p[p++];
        switch (c) {
            case L_ESC -> {
                if (p == ms.pLen) {
                    throw new LuaException("malformed pattern (ends with '%')");
                }
                return p + 1;
            }
            case '[' -> {
                if (p < ms.pLen && ms.p[p] == '^') p++;
                do {
                    if (p == ms.pLen) {
                        throw new LuaException("malformed pattern (missing ']')");
                    }
                    if (ms.p[p++] == L_ESC && p < ms.pLen) {
                        p++;
                    }
                } while (p == ms.pLen || ms.p[p] != ']');
                return p + 1;
            }
            default -> {
                return p;
            }
        }
    }

    private static boolean match_class(int c, int cl) {
        // ASCII fast lane: the 12 magic classes over ASCII input resolve to
        // plain range tests. Character.toLowerCase/isLowerCase cost ~10ns per
        // char and dominate gmatch loops (millions of singlematch calls).
        // For ASCII, toLowerCase == ASCII-lower and isLowerCase == a-z
        // exactly; anything else keeps the original semantics below.
        if (c >= 0 && c < 128) {
            // Only the 24 class letters take the fast lane; anything else
            // (e.g. "%.") falls through to the original early-return below.
            boolean isClass = switch (cl | 32) {
                case 'a', 'c', 'd', 'g', 'l', 'p', 's', 'u', 'w', 'x', 'z' -> true;
                default -> false;
            };
            if (isClass) {
                boolean res = switch (cl | 32) {
                    case 'a' -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                    case 'c' -> (c < 32 || c == 127);
                    case 'd' -> (c >= '0' && c <= '9');
                    case 'g' -> (c >= 33 && c <= 126);
                    case 'l' -> (c >= 'a' && c <= 'z');
                    case 'p' -> (c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
                    case 's' -> (c == ' ' || (c >= 9 && c <= 13));
                    case 'u' -> (c >= 'A' && c <= 'Z');
                    case 'w' -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                    case 'x' -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                    default -> (c == 0);
                };
                // Uppercase class letters are complements; for ASCII letters
                // Character.isLowerCase(cl) == (cl is a-z) exactly.
                return (cl >= 'a' && cl <= 'z') ? res : !res;
            }
        }
        boolean res;
        int lowerCl = Character.toLowerCase(cl);
        switch (lowerCl) {
            case 'a' -> res = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            case 'c' -> res = (c < 32 || c == 127);
            case 'd' -> res = (c >= '0' && c <= '9');
            case 'g' -> res = (c >= 33 && c <= 126);
            case 'l' -> res = (c >= 'a' && c <= 'z');
            case 'p' -> res = (c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
            case 's' -> res = (c == ' ' || (c >= 9 && c <= 13));
            case 'u' -> res = (c >= 'A' && c <= 'Z');
            case 'w' -> res = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            case 'x' -> res = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            case 'z' -> res = (c == 0);
            default -> { return cl == c; }
        }
        return Character.isLowerCase(cl) ? res : !res;
    }

    private static boolean matchbracketclass(int c, byte[] p, int pi, int ec) {
        boolean sig = true;
        if (p[pi + 1] == '^') {
            sig = false;
            pi++;
        }
        while (++pi < ec) {
            if (p[pi] == L_ESC) {
                pi++;
                if (match_class(c, p[pi] & 0xFF)) {
                    return sig;
                }
            } else if (pi + 2 < ec && p[pi + 1] == '-') {
                pi += 2;
                if ((p[pi - 2] & 0xFF) <= c && c <= (p[pi] & 0xFF)) {
                    return sig;
                }
            } else if ((p[pi] & 0xFF) == c) {
                return sig;
            }
        }
        return !sig;
    }

    private static boolean singlematch(MatchState ms, int s, int p, int ep) {
        if (s >= ms.srcLen) {
            return false;
        }
        int c = ms.src[s] & 0xFF;
        byte pc = ms.p[p];
        return switch (pc) {
            case '.' -> true;
            case L_ESC -> match_class(c, ms.p[p + 1] & 0xFF);
            case '[' -> matchbracketclass(c, ms.p, p, ep - 1);
            default -> (pc & 0xFF) == c;
        };
    }

    private static int matchbalance(MatchState ms, int s, int p) {
        if (p >= ms.pLen - 1) {
            throw new LuaException("malformed pattern (missing arguments to '%b')");
        }
        if (s >= ms.srcLen || ms.src[s] != ms.p[p]) {
            return -1;
        }
        int b = ms.p[p] & 0xFF;
        int e = ms.p[p + 1] & 0xFF;
        int cont = 1;
        while (++s < ms.srcLen) {
            int c = ms.src[s] & 0xFF;
            if (c == e) {
                if (--cont == 0) return s + 1;
            } else if (c == b) {
                cont++;
            }
        }
        return -1;
    }

    private static int max_expand(MatchState ms, int s, int p, int ep) {
        int i = 0;
        while (singlematch(ms, s + i, p, ep)) {
            if ((++i & 0xFF) == 0) org.luava.runtime.LuaState.checkGuard();
        }
        while (i >= 0) {
            int res = match(ms, s + i, ep + 1);
            if (res != -1) return res;
            i--;
        }
        return -1;
    }

    private static int min_expand(MatchState ms, int s, int p, int ep) {
        int ticks = 0;
        for (;;) {
            if ((++ticks & 0xFF) == 0) org.luava.runtime.LuaState.checkGuard();
            int res = match(ms, s, ep + 1);
            if (res != -1) return res;
            if (singlematch(ms, s, p, ep)) {
                s++;
            } else {
                return -1;
            }
        }
    }

    private static int start_capture(MatchState ms, int s, int p, int what) {
        int level = ms.level;
        if (level >= LUA_MAXCAPTURES) throw new LuaException("too many captures");
        Capture cap = capAt(ms, level);
        cap.init = s;
        cap.len = what;
        ms.level = level + 1;
        int res = match(ms, s, p);
        if (res == -1) {
            ms.level--;
        }
        return res;
    }

    private static int capture_to_close(MatchState ms) {
        for (int level = ms.level - 1; level >= 0; level--) {
            if (capAt(ms, level).len == CAP_UNFINISHED) return level;
        }
        throw new LuaException("invalid pattern capture");
    }

    private static int end_capture(MatchState ms, int s, int p) {
        int l = capture_to_close(ms);
        Capture cl = capAt(ms, l);
        cl.len = s - cl.init;
        int res = match(ms, s, p);
        if (res == -1) {
            cl.len = CAP_UNFINISHED;
        }
        return res;
    }

    private static int check_capture(MatchState ms, int l) {
        l -= '1';
        if (l < 0 || l >= ms.level || capAt(ms, l).len == CAP_UNFINISHED) {
            throw new LuaException("invalid capture index %" + (l + 1));
        }
        return l;
    }

    private static int match_capture(MatchState ms, int s, int l) {
        int idx = check_capture(ms, l);
        Capture ci = capAt(ms, idx);
        int len = ci.len;
        if (ms.srcLen - s >= len && Arrays.equals(ms.src, ci.init, ci.init + len, ms.src, s, s + len)) {
            return s + len;
        }
        return -1;
    }

    private static int match(MatchState ms, int s, int p) {
        if ((++ms.matchTicks & 0x3FF) == 0) {
            org.luava.runtime.LuaState.checkGuard();
        }
        if (ms.matchdepth-- == 0) {
            throw new LuaException("pattern too complex");
        }
        try {
            init: while (p < ms.pLen) {
                byte pc = ms.p[p];
                boolean isDflt = false;
                switch (pc) {
                    case '(' -> {
                        if (p + 1 < ms.pLen && ms.p[p + 1] == ')') {
                            return start_capture(ms, s, p + 2, CAP_POSITION);
                        } else {
                            return start_capture(ms, s, p + 1, CAP_UNFINISHED);
                        }
                    }
                    case ')' -> {
                        return end_capture(ms, s, p + 1);
                    }
                    case '$' -> {
                        if (p + 1 != ms.pLen) {
                            isDflt = true;
                        } else {
                            return (s == ms.srcLen) ? s : -1;
                        }
                    }
                    case L_ESC -> {
                        if (p + 1 >= ms.pLen) {
                            throw new LuaException("malformed pattern (ends with '%')");
                        }
                        byte next = ms.p[p + 1];
                        switch (next) {
                            case 'b' -> {
                                s = matchbalance(ms, s, p + 2);
                                if (s != -1) {
                                    p += 4;
                                    continue init;
                                }
                                return -1;
                            }
                            case 'f' -> {
                                p += 2;
                                if (p >= ms.pLen || ms.p[p] != '[') {
                                    throw new LuaException("missing '[' after '%f' in pattern");
                                }
                                int ep = classend(ms, p);
                                int previous = (s == 0) ? 0 : (ms.src[s - 1] & 0xFF);
                                int current = (s == ms.srcLen) ? 0 : (ms.src[s] & 0xFF);
                                if (!matchbracketclass(previous, ms.p, p, ep - 1) &&
                                     matchbracketclass(current, ms.p, p, ep - 1)) {
                                    p = ep;
                                    continue init;
                                }
                                return -1;
                            }
                            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                                s = match_capture(ms, s, next);
                                if (s != -1) {
                                    p += 2;
                                    continue init;
                                }
                                return -1;
                            }
                            default -> {
                                isDflt = true;
                            }
                        }
                    }
                    default -> {
                        isDflt = true;
                    }
                }
                if (isDflt) {
                    int ep = classend(ms, p);
                    byte suffix = (ep < ms.pLen) ? ms.p[ep] : 0;
                    if (!singlematch(ms, s, p, ep)) {
                        if (suffix == '*' || suffix == '?' || suffix == '-') {
                            p = ep + 1;
                            continue init;
                        } else {
                            return -1;
                        }
                    } else {
                        switch (suffix) {
                            case '?' -> {
                                int res = match(ms, s + 1, ep + 1);
                                if (res != -1) {
                                    return res;
                                } else {
                                    p = ep + 1;
                                    continue init;
                                }
                            }
                            case '+' -> {
                                return max_expand(ms, s + 1, p, ep);
                            }
                            case '*' -> {
                                return max_expand(ms, s, p, ep);
                            }
                            case '-' -> {
                                return min_expand(ms, s, p, ep);
                            }
                            default -> {
                                s++;
                                p = ep;
                                continue init;
                            }
                        }
                    }
                }
            }
            return s;
        } finally {
            ms.matchdepth++;
        }
    }


    /** Lazy capture slot: capture-less patterns (the common case) never pay
     * for the 32 Capture objects a MatchState would otherwise allocate. */
    private static Capture capAt(MatchState ms, int i) {
        Capture[] slots = ms.capture;
        if (slots == null) {
            slots = new Capture[LUA_MAXCAPTURES];
            ms.capture = slots;
        }
        Capture c = slots[i];
        if (c == null) {
            c = new Capture();
            slots[i] = c;
        }
        return c;
    }

    private static LuaValue pushOneCapture(MatchState ms, int i, int s, int e) {
        if (i >= ms.level) {
            if (i != 0) throw new LuaException("invalid capture index %" + (i + 1));
            byte[] bytes = Arrays.copyOfRange(ms.src, s, e);
            return LuaString.valueOf(new String(bytes, StandardCharsets.ISO_8859_1));
        } else {
            Capture c = capAt(ms, i);
            int len = c.len;
            if (len == CAP_UNFINISHED) {
                throw new LuaException("unfinished capture");
            } else if (len == CAP_POSITION) {
                return LuaInteger.valueOf(c.init + 1);
            } else {
                byte[] bytes = Arrays.copyOfRange(ms.src, c.init, c.init + len);
                return LuaString.valueOf(new String(bytes, StandardCharsets.ISO_8859_1));
            }
        }
    }

    private static LuaValue[] pushCaptures(MatchState ms, int s, int e) {
        int nlevels = (ms.level == 0) ? 1 : ms.level;
        LuaValue[] out = new LuaValue[nlevels];
        for (int i = 0; i < nlevels; i++) {
            out[i] = pushOneCapture(ms, i, s, e);
        }
        return out;
    }

    // C posrelatI uses lua_Integer: huge positions must saturate past the
    // end (find nothing), never wrap around via (int) cast.
    private static int posrelat(long pos, int len) {
        if (pos > 0) return (int) Math.min(pos - 1, (long) len + 1);
        else if (pos == 0) return 0;
        else if (pos + len >= 0) return (int) (pos + len);
        else return 0;
    }

    private static boolean nospecials(byte[] p) {
        for (byte b : p) {
            switch (b) {
                case '^', '$', '*', '+', '?', '.', '(', ')', '[', '%', '-' -> {
                    return false;
                }
            }
        }
        return true;
    }

    private static int indexOf(byte[] src, int init, byte[] target) {
        if (target.length == 0) return init <= src.length ? init : -1;
        int max = src.length - target.length;
        for (int i = init; i <= max; i++) {
            boolean found = true;
            for (int j = 0; j < target.length; j++) {
                if (src[i + j] != target[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    public static Varargs find(LuaValue sVal, LuaValue pVal, LuaValue initVal, boolean plain) {
        byte[] src = bytes(sVal);
        byte[] p = bytes(pVal);
        long initArg = (initVal != null && !initVal.isNil()) ? initVal.toLong() : 1;
        int init = posrelat(initArg, src.length);

        if (plain || nospecials(p)) {
            int idx = indexOf(src, init, p);
            if (idx >= 0) {
                return Varargs.of(LuaInteger.valueOf(idx + 1), LuaInteger.valueOf(idx + p.length));
            }
            return Varargs.of(LuaNil.NIL);
        }

        boolean anchor = p.length > 0 && p[0] == '^';
        byte[] actP = anchor ? Arrays.copyOfRange(p, 1, p.length) : p;

        MatchState ms = new MatchState(src, actP);
        for (int s = init; s <= src.length; s++) {
            ms.reprepstate();
            int res = match(ms, s, 0);
            if (res != -1) {
                LuaValue[] out = new LuaValue[2 + ms.level];
                out[0] = LuaInteger.valueOf(s + 1);
                out[1] = LuaInteger.valueOf(res);
                for (int i = 0; i < ms.level; i++) {
                    out[2 + i] = pushOneCapture(ms, i, s, res);
                }
                return Varargs.of(out);
            }
            if (anchor) break;
        }
        return Varargs.of(LuaNil.NIL);
    }

    public static Varargs match(LuaValue sVal, LuaValue pVal, LuaValue initVal) {
        byte[] src = bytes(sVal);
        byte[] p = bytes(pVal);
        long initArg = (initVal != null && !initVal.isNil()) ? initVal.toLong() : 1;
        int init = posrelat(initArg, src.length);

        boolean anchor = p.length > 0 && p[0] == '^';
        byte[] actP = anchor ? Arrays.copyOfRange(p, 1, p.length) : p;

        MatchState ms = new MatchState(src, actP);
        for (int s = init; s <= src.length; s++) {
            ms.reprepstate();
            int res = match(ms, s, 0);
            if (res != -1) {
                return Varargs.of(pushCaptures(ms, s, res));
            }
            if (anchor) break;
        }
        return Varargs.of(LuaNil.NIL);
    }

    public static Varargs gsub(LuaValue sVal, LuaValue pVal, LuaValue replVal, LuaValue maxVal) {
        byte[] src = bytes(sVal);
        byte[] p = bytes(pVal);
        long max_s = (maxVal != null && !maxVal.isNil()) ? maxVal.toLong() : Long.MAX_VALUE;

        boolean anchor = p.length > 0 && p[0] == '^';
        byte[] actP = anchor ? Arrays.copyOfRange(p, 1, p.length) : p;

        MatchState ms = new MatchState(src, actP);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = 0;
        int lastmatch = -1;
        int s = 0;
        boolean changed = false;

        while (n < max_s) {
            ms.reprepstate();
            int e = match(ms, s, 0);
            if (e != -1 && e != lastmatch) {
                n++;
                changed = add_value(ms, b, s, e, replVal) || changed;
                s = lastmatch = e;
            } else if (s < src.length) {
                b.write(src[s++]);
            } else {
                break;
            }
            if (anchor) break;
        }
        if (!changed) {
            return Varargs.of(sVal, LuaInteger.valueOf(n));
        }
        if (s < src.length) {
            b.write(src, s, src.length - s);
        }
        String res = new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        return Varargs.of(LuaString.valueOf(res), LuaInteger.valueOf(n));
    }

    private static boolean add_value(MatchState ms, ByteArrayOutputStream b, int s, int e, LuaValue tr) {
        if (tr.isFunction()) {
            LuaValue[] args = pushCaptures(ms, s, e);
            org.luava.runtime.concurrency.LuaCoroutine curCoro = org.luava.runtime.concurrency.LuaCoroutine.running();
            if (curCoro != null) curCoro.enterNonYieldable();
            LuaValue res;
            try {
                res = tr.call(args);
            } finally {
                if (curCoro != null) curCoro.exitNonYieldable();
            }
            if (res.isNil() || (res.isBoolean() && !res.toBoolean())) {
                b.write(ms.src, s, e - s);
                return false;
            } else if (!res.isString() && !res.isNumber()) {
                throw new LuaException("invalid replacement value (a " + res.typeName() + ")");
            } else {
                byte[] bytes = bytes(res);
                b.write(bytes, 0, bytes.length);
                return true;
            }
        } else if (tr.isTable()) {
            LuaValue key = pushOneCapture(ms, 0, s, e);
            org.luava.runtime.concurrency.LuaCoroutine curCoro = org.luava.runtime.concurrency.LuaCoroutine.running();
            if (curCoro != null) curCoro.enterNonYieldable();
            LuaValue res;
            try {
                res = tr.get(key);
            } finally {
                if (curCoro != null) curCoro.exitNonYieldable();
            }
            if (res.isNil() || (res.isBoolean() && !res.toBoolean())) {
                b.write(ms.src, s, e - s);
                return false;
            } else if (!res.isString() && !res.isNumber()) {
                throw new LuaException("invalid replacement value (a " + res.typeName() + ")");
            } else {
                byte[] bytes = bytes(res);
                b.write(bytes, 0, bytes.length);
                return true;
            }
        } else if (tr.isString() || tr.isNumber()) {
            byte[] repl = bytes(tr);
            add_s(ms, b, s, e, repl);
            return true;
        } else {
            throw new LuaException("string/function/table expected");
        }
    }

    private static void add_s(MatchState ms, ByteArrayOutputStream b, int s, int e, byte[] repl) {
        int len = repl.length;
        int i = 0;
        while (i < len) {
            byte c = repl[i++];
            if (c != L_ESC) {
                b.write(c);
            } else {
                if (i >= len) {
                    throw new LuaException("invalid use of '%' in replacement string");
                }
                byte next = repl[i++];
                if (next == L_ESC) {
                    b.write(L_ESC);
                } else if (next == '0') {
                    b.write(ms.src, s, e - s);
                } else if (next >= '1' && next <= '9') {
                    byte[] bytes = bytes(pushOneCapture(ms, next - '1', s, e));
                    b.write(bytes, 0, bytes.length);
                } else {
                    throw new LuaException("invalid use of '%' in replacement string");
                }
            }
        }
    }

    /**
     * Raw pattern-matching bytes for a value. Lua strings carry a cached
     * Latin-1 encoding, so repeated matches over the same subject/pattern
     * (the classic {@code for w in s:gmatch(p)} loop) never re-copy.
     */
    static byte[] bytes(LuaValue v) {
        if (v instanceof LuaString ls) {
            return ls.latin1Bytes();
        }
        return v.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Dedicated {@code gmatch} iterator: holds the match state inline
     * instead of a lambda closing over a {@code MatchState} plus two
     * upvalues. Saves ~7 small allocations per {@code gmatch} call and
     * returns single captures without a {@code Varargs} wrapper.
     */
    public static final class GmatchIterator extends LuaFunction {
        private final byte[] src;
        private final int srcLen;
        private final byte[] pat;
        private final LuaValue srcVal;
        private final LuaValue patVal;
        private MatchState ms;
        private int pos;
        private int lastmatch;
        private boolean upvaluesBuilt;
        /**
         * Simple-pattern fast lane: when the pattern is exactly
         * {@code %<class><quant>} ({@code %a+}, {@code %d*}, ...), the
         * general backtracking engine is replaced by a direct scan that
         * produces the identical (s, e) sequence (verified against
         * {@code match}/{@code max_expand}/{@code min_expand} for the
         * four quantifiers). 0 = general engine.
         */
        private final char simpleQuant;
        private final int simpleClass;

        private static boolean isSimpleClass(int cl) {
            return switch (cl | 32) {
                case 'a', 'c', 'd', 'g', 'l', 'p', 's', 'u', 'w', 'x', 'z' -> true;
                default -> false;
            };
        }

        GmatchIterator(LuaValue sVal, LuaValue pVal, byte[] src, byte[] p, int init) {
            this.src = src;
            this.srcLen = src.length;
            this.pat = p;
            this.srcVal = sVal;
            this.patVal = pVal;
            // The general engine state is allocated lazily: simple patterns
            // (%a+, %d*, ...) scan directly and never need it.
            this.ms = null;
            this.pos = init;
            this.lastmatch = -1;
            this.upvaluesBuilt = false;
            // Upvalues are materialized lazily (see getUpvalues): the hot path
            // never pays for them, while debug.getupvalue/upvalueid keep
            // working (PUC closure.lua/db.lua assert on them).
            if (p.length == 3 && p[0] == '%' && isSimpleClass(p[1] & 0xFF)
                    && (p[2] == '+' || p[2] == '*' || p[2] == '-' || p[2] == '?')) {
                this.simpleQuant = (char) p[2];
                this.simpleClass = p[1] & 0xFF;
            } else {
                this.simpleQuant = 0;
                this.simpleClass = 0;
            }
            setWhat("C");
            setSource("=[C]");
            setLineDefined(-1);
            setLastLineDefined(-1);
        }

        /** Direct step without varargs boxing (VM fast path). */
        public LuaValue next() {
            if (simpleQuant != 0) {
                return nextSimple();
            }
            MatchState ms = this.ms;
            if (ms == null) {
                ms = new MatchState(src, pat);
                this.ms = ms;
            }
            int s = pos;
            int last = lastmatch;
            int srcLen = ms.srcLen;
            for (; s <= srcLen; s++) {
                ms.reprepstate();
                int e = match(ms, s, 0);
                if (e != -1 && e != last) {
                    pos = e;
                    lastmatch = e;
                    LuaValue[] out = pushCaptures(ms, s, e);
                    return out.length == 1 ? out[0] : Varargs.of(out);
                }
            }
            pos = srcLen + 1;
            return LuaNil.NIL;
        }

        private LuaValue nextSimple() {
            byte[] src = this.src;
            int srcLen = this.srcLen;
            int s = pos;
            int last = lastmatch;
            int ticks = 0;
            for (; s <= srcLen; s++) {
                int e;
                switch (simpleQuant) {
                    case '+' -> {
                        if (s >= srcLen || !match_class(src[s] & 0xFF, simpleClass)) {
                            continue;
                        }
                        e = s + 1;
                        while (e < srcLen && match_class(src[e] & 0xFF, simpleClass)) {
                            if ((++ticks & 0xFF) == 0) {
                                org.luava.runtime.LuaState.checkGuard();
                            }
                            e++;
                        }
                    }
                    case '*' -> {
                        e = s;
                        while (e < srcLen && match_class(src[e] & 0xFF, simpleClass)) {
                            if ((++ticks & 0xFF) == 0) {
                                org.luava.runtime.LuaState.checkGuard();
                            }
                            e++;
                        }
                    }
                    case '-' -> e = s;
                    default -> e = (s < srcLen && match_class(src[s] & 0xFF, simpleClass)) ? s + 1 : s;
                }
                if (e != last) {
                    pos = e;
                    lastmatch = e;
                    return LuaString.valueOf(new String(src, s, e - s, StandardCharsets.ISO_8859_1));
                }
            }
            pos = srcLen + 1;
            return LuaNil.NIL;
        }

        @Override
        public LuaValue invoke(LuaValue... args) {
            return next();
        }

        /**
         * Observable upvalues ("s", "pattern"), built on first debug access
         * exactly like the closure this iterator replaces. Closed snapshots
         * (no VariableSlot): s/p never change, so sharing live storage buys
         * nothing. The base-class list stays empty until then, so the hot
         * path allocates nothing here.
         */
        @Override
        public java.util.List<org.luava.runtime.eval.Upvalue> getUpvalues() {
            if (!upvaluesBuilt) {
                upvaluesBuilt = true;
                super.upvalues.add(new org.luava.runtime.eval.Upvalue("s", srcVal));
                super.upvalues.add(new org.luava.runtime.eval.Upvalue("pattern", patVal));
            }
            return super.upvalues;
        }

        @Override
        public void replaceUpvalue(int index, org.luava.runtime.eval.Upvalue uv) {
            getUpvalues().set(index, uv);
        }

        @Override
        public String toLuaString() {
            return "function: builtin@0x" + Integer.toHexString(System.identityHashCode(this));
        }
    }

    public static LuaFunction gmatch(LuaValue sVal, LuaValue pVal, LuaValue initVal) {
        byte[] src = bytes(sVal);
        byte[] p = bytes(pVal);
        long initArg = (initVal != null && !initVal.isNil()) ? initVal.toLong() : 1;
        int init = posrelat(initArg, src.length);
        return new GmatchIterator(sVal, pVal, src, p, init);
    }
}
