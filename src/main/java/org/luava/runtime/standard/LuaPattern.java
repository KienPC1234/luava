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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        final Capture[] capture = new Capture[LUA_MAXCAPTURES];

        MatchState(byte[] src, byte[] p) {
            this.src = src;
            this.srcLen = src.length;
            this.p = p;
            this.pLen = p.length;
            this.matchdepth = MAXCCALLS;
            this.level = 0;
            for (int i = 0; i < LUA_MAXCAPTURES; i++) {
                capture[i] = new Capture();
            }
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
            i++;
        }
        while (i >= 0) {
            int res = match(ms, s + i, ep + 1);
            if (res != -1) return res;
            i--;
        }
        return -1;
    }

    private static int min_expand(MatchState ms, int s, int p, int ep) {
        for (;;) {
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
        ms.capture[level].init = s;
        ms.capture[level].len = what;
        ms.level = level + 1;
        int res = match(ms, s, p);
        if (res == -1) {
            ms.level--;
        }
        return res;
    }

    private static int capture_to_close(MatchState ms) {
        for (int level = ms.level - 1; level >= 0; level--) {
            if (ms.capture[level].len == CAP_UNFINISHED) return level;
        }
        throw new LuaException("invalid pattern capture");
    }

    private static int end_capture(MatchState ms, int s, int p) {
        int l = capture_to_close(ms);
        ms.capture[l].len = s - ms.capture[l].init;
        int res = match(ms, s, p);
        if (res == -1) {
            ms.capture[l].len = CAP_UNFINISHED;
        }
        return res;
    }

    private static int check_capture(MatchState ms, int l) {
        l -= '1';
        if (l < 0 || l >= ms.level || ms.capture[l].len == CAP_UNFINISHED) {
            throw new LuaException("invalid capture index %" + (l + 1));
        }
        return l;
    }

    private static int match_capture(MatchState ms, int s, int l) {
        int idx = check_capture(ms, l);
        int len = ms.capture[idx].len;
        if (ms.srcLen - s >= len && Arrays.equals(ms.src, ms.capture[idx].init, ms.capture[idx].init + len, ms.src, s, s + len)) {
            return s + len;
        }
        return -1;
    }

    private static int match(MatchState ms, int s, int p) {
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


    private static void push_onecapture(MatchState ms, int i, int s, int e, List<LuaValue> out) {
        if (i >= ms.level) {
            if (i != 0) throw new LuaException("invalid capture index %" + (i + 1));
            byte[] bytes = Arrays.copyOfRange(ms.src, s, e);
            out.add(LuaString.valueOf(new String(bytes, StandardCharsets.ISO_8859_1)));
        } else {
            int len = ms.capture[i].len;
            if (len == CAP_UNFINISHED) {
                throw new LuaException("unfinished capture");
            } else if (len == CAP_POSITION) {
                out.add(LuaInteger.valueOf(ms.capture[i].init + 1));
            } else {
                byte[] bytes = Arrays.copyOfRange(ms.src, ms.capture[i].init, ms.capture[i].init + len);
                out.add(LuaString.valueOf(new String(bytes, StandardCharsets.ISO_8859_1)));
            }
        }
    }

    private static void push_captures(MatchState ms, int s, int e, List<LuaValue> out) {
        int nlevels = (ms.level == 0) ? 1 : ms.level;
        for (int i = 0; i < nlevels; i++) {
            push_onecapture(ms, i, s, e, out);
        }
    }

    private static int posrelat(int pos, int len) {
        if (pos > 0) return pos - 1;
        else if (pos == 0) return 0;
        else if (pos + len >= 0) return pos + len;
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
        byte[] src = sVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] p = pVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        int initArg = (initVal != null && !initVal.isNil()) ? (int) initVal.toLong() : 1;
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
                List<LuaValue> captures = new ArrayList<>();
                captures.add(LuaInteger.valueOf(s + 1));
                captures.add(LuaInteger.valueOf(res));
                for (int i = 0; i < ms.level; i++) {
                    push_onecapture(ms, i, s, res, captures);
                }
                return Varargs.of(captures.toArray(new LuaValue[0]));
            }
            if (anchor) break;
        }
        return Varargs.of(LuaNil.NIL);
    }

    public static Varargs match(LuaValue sVal, LuaValue pVal, LuaValue initVal) {
        byte[] src = sVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] p = pVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        int initArg = (initVal != null && !initVal.isNil()) ? (int) initVal.toLong() : 1;
        int init = posrelat(initArg, src.length);

        boolean anchor = p.length > 0 && p[0] == '^';
        byte[] actP = anchor ? Arrays.copyOfRange(p, 1, p.length) : p;

        MatchState ms = new MatchState(src, actP);
        for (int s = init; s <= src.length; s++) {
            ms.reprepstate();
            int res = match(ms, s, 0);
            if (res != -1) {
                List<LuaValue> captures = new ArrayList<>();
                push_captures(ms, s, res, captures);
                return Varargs.of(captures.toArray(new LuaValue[0]));
            }
            if (anchor) break;
        }
        return Varargs.of(LuaNil.NIL);
    }

    public static Varargs gsub(LuaValue sVal, LuaValue pVal, LuaValue replVal, LuaValue maxVal) {
        byte[] src = sVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] p = pVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        int max_s = (maxVal != null && !maxVal.isNil()) ? (int) maxVal.toLong() : Integer.MAX_VALUE;

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
            List<LuaValue> args = new ArrayList<>();
            push_captures(ms, s, e, args);
            org.luava.runtime.concurrency.LuaCoroutine curCoro = org.luava.runtime.concurrency.LuaCoroutine.running();
            if (curCoro != null) curCoro.enterNonYieldable();
            LuaValue res;
            try {
                res = tr.call(args.toArray(new LuaValue[0]));
            } finally {
                if (curCoro != null) curCoro.exitNonYieldable();
            }
            if (res.isNil() || (res.isBoolean() && !res.toBoolean())) {
                b.write(ms.src, s, e - s);
                return false;
            } else if (!res.isString() && !res.isNumber()) {
                throw new LuaException("invalid replacement value (a " + res.typeName() + ")");
            } else {
                byte[] bytes = res.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
                b.write(bytes, 0, bytes.length);
                return true;
            }
        } else if (tr.isTable()) {
            List<LuaValue> args = new ArrayList<>();
            push_onecapture(ms, 0, s, e, args);
            LuaValue key = args.get(0);
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
                byte[] bytes = res.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
                b.write(bytes, 0, bytes.length);
                return true;
            }
        } else if (tr.isString() || tr.isNumber()) {
            byte[] repl = tr.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
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
                    List<LuaValue> cap = new ArrayList<>();
                    push_onecapture(ms, next - '1', s, e, cap);
                    byte[] bytes = cap.get(0).toLuaString().getBytes(StandardCharsets.ISO_8859_1);
                    b.write(bytes, 0, bytes.length);
                } else {
                    throw new LuaException("invalid use of '%' in replacement string");
                }
            }
        }
    }

    public static LuaFunction gmatch(LuaValue sVal, LuaValue pVal, LuaValue initVal) {
        byte[] src = sVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] p = pVal.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        int initArg = (initVal != null && !initVal.isNil()) ? (int) initVal.toLong() : 1;
        int init = posrelat(initArg, src.length);

        MatchState ms = new MatchState(src, p);
        int[] state = new int[]{init, -1};

        LuaFunction iterFn = LuaFunction.of(iterArgs -> {
            int s = state[0];
            int lastmatch = state[1];
            for (; s <= src.length; s++) {
                ms.reprepstate();
                int e = match(ms, s, 0);
                if (e != -1 && e != lastmatch) {
                    state[0] = state[1] = e;
                    List<LuaValue> captures = new ArrayList<>();
                    push_captures(ms, s, e, captures);
                    return Varargs.of(captures.toArray(new LuaValue[0]));
                }
            }
            state[0] = src.length + 1;
            return LuaNil.NIL;
        });
        iterFn.getUpvalues().add(new org.luava.runtime.eval.Upvalue("s", new org.luava.runtime.eval.Environment.VariableSlot(sVal, false, false)));
        iterFn.getUpvalues().add(new org.luava.runtime.eval.Upvalue("pattern", new org.luava.runtime.eval.Environment.VariableSlot(pVal, false, false)));
        return iterFn;
    }
}
