/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class IoLib {
    private IoLib() {}

    public abstract static class FileHandle {
        protected boolean closed = false;
        protected final String name;

        public FileHandle(String name) {
            this.name = name;
        }

        public boolean isClosed() {
            return closed;
        }

        public String getName() {
            return name;
        }

        public void checkOpen() {
            if (closed) {
                throw new LuaException("attempt to use a closed file");
            }
        }

        public abstract LuaValue close() throws IOException;
        public abstract void flush() throws IOException;
        public abstract long seek(String whence, long offset) throws IOException;
        public abstract void write(String data) throws IOException;

        protected abstract int readByte() throws IOException;
        protected abstract void unreadByte(int b) throws IOException;
        protected abstract int readBytes(byte[] buf, int off, int len) throws IOException;

        protected String readLine(boolean chop) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int c = readByte();
            if (c == -1) return null;
            while (c != -1 && c != '\n') {
                baos.write(c);
                c = readByte();
            }
            if (!chop && c == '\n') {
                baos.write('\n');
            }
            return baos.toString(StandardCharsets.ISO_8859_1);
        }

        protected String readAll() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = readBytes(buf, 0, buf.length)) > 0) {
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.ISO_8859_1);
        }

        private static final int L_MAXLENNUM = 200;

        private static class RN {
            final FileHandle fh;
            int c;
            int n = 0;
            boolean overflow = false;
            final char[] buff = new char[L_MAXLENNUM + 1];

            RN(FileHandle fh) {
                this.fh = fh;
            }

            boolean nextc() throws IOException {
                if (n >= L_MAXLENNUM) {
                    overflow = true;
                    return false;
                }
                buff[n++] = (char) c;
                c = fh.readByte();
                return true;
            }

            boolean test2(char c1, char c2) throws IOException {
                if (c == c1 || c == c2) {
                    return nextc();
                }
                return false;
            }

            int readdigits(boolean hex) throws IOException {
                int count = 0;
                while ((hex ? isHexDigit(c) : (c >= '0' && c <= '9')) && nextc()) {
                    count++;
                }
                return count;
            }
        }

        public LuaValue readNumber() throws IOException {
            checkOpen();
            RN rn = new RN(this);
            do {
                rn.c = readByte();
            } while (rn.c != -1 && (rn.c == ' ' || rn.c == '\t' || rn.c == '\n' || rn.c == '\r' || rn.c == 0x0b || rn.c == 0x0c));

            if (rn.c == -1) return LuaNil.NIL;

            rn.test2('-', '+');
            int count = 0;
            boolean hex = false;
            if (rn.test2('0', '0')) {
                if (rn.test2('x', 'X')) {
                    hex = true;
                } else {
                    count = 1;
                }
            }
            count += rn.readdigits(hex);
            if (rn.test2('.', '.')) {
                count += rn.readdigits(hex);
            }
            if (count > 0 && rn.test2(hex ? 'p' : 'e', hex ? 'P' : 'E')) {
                rn.test2('-', '+');
                rn.readdigits(false);
            }
            if (rn.c != -1) {
                unreadByte(rn.c);
            }
            if (rn.overflow) {
                return LuaNil.NIL;
            }
            String s = new String(rn.buff, 0, rn.n);
            LuaValue num = LuaValue.parseNumber(s);
            return num != null ? num : LuaNil.NIL;
        }

        private static boolean isHexDigit(int c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        public LuaValue readOne(LuaValue fmt) throws IOException {
            checkOpen();
            if (fmt.isInteger() || fmt.isFloat()) {
                long n = fmt.toLong();
                if (n < 0) {
                    throw new LuaException("bad argument to 'read' (invalid format)");
                }
                if (n == 0) {
                    int c = readByte();
                    if (c == -1) return LuaNil.NIL;
                    unreadByte(c);
                    return LuaString.valueOf("");
                }
                byte[] buf = new byte[(int) n];
                int read = readBytes(buf, 0, (int) n);
                if (read <= 0) return LuaNil.NIL;
                return LuaString.valueOf(new String(buf, 0, read, StandardCharsets.ISO_8859_1));
            }
            String s = fmt.isString() ? fmt.toLuaString() : "l";
            if (s.startsWith("*")) s = s.substring(1);
            char format = !s.isEmpty() ? s.charAt(0) : 0;
            return switch (format) {
                case 'a' -> {
                    String all = readAll();
                    yield LuaString.valueOf(all != null ? all : "");
                }
                case 'l' -> {
                    String line = readLine(true);
                    yield line != null ? LuaString.valueOf(line) : LuaNil.NIL;
                }
                case 'L' -> {
                    String line = readLine(false);
                    yield line != null ? LuaString.valueOf(line) : LuaNil.NIL;
                }
                case 'n' -> readNumber();
                default -> throw new LuaException("bad argument to 'read' (invalid format)");
            };
        }

        public void setvbuf(String mode, int size) throws IOException {
        }
    }

    public static class RafFileHandle extends FileHandle {
        private final RandomAccessFile raf;
        private final boolean canRead;
        private final boolean canWrite;

        public RafFileHandle(RandomAccessFile raf, String name, String mode) {
            super(name);
            this.raf = raf;
            this.canRead = mode.startsWith("r") || mode.contains("+");
            this.canWrite = mode.startsWith("w") || mode.startsWith("a") || mode.contains("+");
        }

        private static final int VBUF_NO = 0;
        private static final int VBUF_FULL = 1;
        private static final int VBUF_LINE = 2;

        private int vbufMode = VBUF_NO;
        private byte[] vbuf = new byte[1024];
        private int vbufCount = 0;

        @Override
        public void setvbuf(String mode, int size) throws IOException {
            flushWriteBuffer();
            int sz = size > 0 ? size : 1024;
            vbuf = new byte[sz];
            vbufCount = 0;
            switch (mode) {
                case "no" -> vbufMode = VBUF_NO;
                case "line" -> vbufMode = VBUF_LINE;
                case "full" -> vbufMode = VBUF_FULL;
            }
        }

        private void flushWriteBuffer() throws IOException {
            if (vbufCount > 0) {
                raf.write(vbuf, 0, vbufCount);
                vbufCount = 0;
            }
        }

        private void checkReadable() throws IOException {
            checkOpen();
            if (!canRead) {
                throw new IOException("Bad file descriptor");
            }
            flushWriteBuffer();
        }

        private void checkWritable() throws IOException {
            checkOpen();
            if (!canWrite) {
                throw new IOException("Bad file descriptor");
            }
        }

        @Override
        protected int readByte() throws IOException {
            checkReadable();
            return raf.read();
        }

        @Override
        protected void unreadByte(int b) throws IOException {
            checkReadable();
            long pos = raf.getFilePointer();
            if (pos > 0) {
                raf.seek(pos - 1);
            }
        }

        @Override
        protected int readBytes(byte[] buf, int off, int len) throws IOException {
            checkReadable();
            return raf.read(buf, off, len);
        }

        @Override
        public LuaValue close() throws IOException {
            if (!closed) {
                try {
                    flushWriteBuffer();
                } finally {
                    closed = true;
                    raf.close();
                }
            }
            return LuaBoolean.TRUE;
        }

        @Override
        public void flush() throws IOException {
            checkOpen();
            flushWriteBuffer();
            raf.getChannel().force(true);
        }

        @Override
        public long seek(String whence, long offset) throws IOException {
            checkOpen();
            flushWriteBuffer();
            long pos = switch (whence) {
                case "set" -> offset;
                case "end" -> raf.length() + offset;
                default -> raf.getFilePointer() + offset;
            };
            if (pos < 0) throw new IOException("invalid seek position");
            raf.seek(pos);
            return raf.getFilePointer();
        }

        @Override
        public void write(String data) throws IOException {
            checkWritable();
            byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
            if (vbufMode == VBUF_NO) {
                raf.write(bytes);
                return;
            }
            if (vbufMode == VBUF_FULL) {
                int rem = bytes.length;
                int curOff = 0;
                while (rem > 0) {
                    int space = vbuf.length - vbufCount;
                    if (space == 0) {
                        flushWriteBuffer();
                        space = vbuf.length;
                    }
                    if (vbufCount == 0 && rem >= vbuf.length) {
                        raf.write(bytes, curOff, rem);
                        break;
                    }
                    int toCopy = Math.min(space, rem);
                    System.arraycopy(bytes, curOff, vbuf, vbufCount, toCopy);
                    vbufCount += toCopy;
                    curOff += toCopy;
                    rem -= toCopy;
                }
                return;
            }
            if (vbufMode == VBUF_LINE) {
                for (int i = 0; i < bytes.length; i++) {
                    byte b = bytes[i];
                    if (vbufCount >= vbuf.length) {
                        flushWriteBuffer();
                    }
                    vbuf[vbufCount++] = b;
                    if (b == '\n') {
                        flushWriteBuffer();
                    }
                }
            }
        }
    }

    public static class ProcessFileHandle extends FileHandle {
        private final Process process;
        private final PushbackInputStream in;
        private final OutputStream out;
        private final java.nio.file.Path sentinel;
        private final java.nio.file.Path fifo;

        public ProcessFileHandle(Process process, String mode, String name) {
            this(process, mode, name, null, null, null);
        }

        public ProcessFileHandle(Process process, String mode, String name,
                                 java.nio.file.Path sentinel) {
            this(process, mode, name, sentinel, null, null);
        }

        /**
         * {@code explicitIn} (when non-null) replaces the process pipe for
         * mode "r"; used with a FIFO so EOF follows POSIX popen semantics
         * (all writers closed) rather than the JDK pipe's early process-exit
         * EOF. {@code fifo} is deleted on close.
         */
        public ProcessFileHandle(Process process, String mode, String name,
                                 java.nio.file.Path sentinel,
                                 java.io.InputStream explicitIn,
                                 java.nio.file.Path fifo) {
            super(name);
            this.process = process;
            this.fifo = fifo;
            java.io.InputStream src = explicitIn != null ? explicitIn : process.getInputStream();
            this.in = mode.contains("r") ? new PushbackInputStream(new BufferedInputStream(src), 1024) : null;
            this.out = mode.contains("w") ? new BufferedOutputStream(process.getOutputStream()) : null;
            this.sentinel = sentinel;
        }

        @Override
        protected int readByte() throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            return in.read();
        }

        @Override
        protected void unreadByte(int b) throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            if (b != -1) {
                in.unread(b);
            }
        }

        @Override
        protected int readBytes(byte[] buf, int off, int len) throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            return in.read(buf, off, len);
        }

        @Override
        public LuaValue close() throws IOException {
            if (!closed) {
                closed = true;
                if (in != null) in.close();
                if (out != null) out.close();
                try {
                    return OsTime.finishShell(new OsTime.ShellRun(process, sentinel));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf("interrupted"), LuaInteger.valueOf(-1));
                } finally {
                    if (fifo != null) {
                        try {
                            java.nio.file.Files.deleteIfExists(fifo);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            throw new LuaException("attempt to use a closed file");
        }

        @Override
        public void flush() throws IOException {
            checkOpen();
            if (out != null) out.flush();
        }

        @Override
        public long seek(String whence, long offset) throws IOException {
            checkOpen();
            throw new IOException("cannot seek on a process stream");
        }

        @Override
        public void write(String data) throws IOException {
            checkOpen();
            if (out == null) throw new IOException("Bad file descriptor");
            out.write(data.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    public static class StdFileHandle extends FileHandle {
        private final PushbackInputStream in;
        private final PrintStream out;

        public StdFileHandle(String name, InputStream in, PrintStream out) {
            super(name);
            this.in = in != null ? new PushbackInputStream(in, 1024) : null;
            this.out = out;
        }

        @Override
        protected int readByte() throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            return in.read();
        }

        @Override
        protected void unreadByte(int b) throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            if (b != -1) {
                in.unread(b);
            }
        }

        @Override
        protected int readBytes(byte[] buf, int off, int len) throws IOException {
            checkOpen();
            if (in == null) throw new IOException("Bad file descriptor");
            return in.read(buf, off, len);
        }

        @Override
        public LuaValue close() throws IOException {
            return Varargs.of(LuaNil.NIL, LuaString.valueOf("cannot close standard file"));
        }

        @Override
        public void flush() throws IOException {
            if (out != null) out.flush();
        }

        @Override
        public long seek(String whence, long offset) throws IOException {
            checkOpen();
            throw new IOException("cannot seek on standard stream");
        }

        @Override
        public void write(String data) throws IOException {
            checkOpen();
            if (out == null) throw new IOException("Bad file descriptor");
            out.print(data);
            out.flush();
        }
    }

    private static FileHandle checkFile(LuaValue[] args, String funcName) {
        if (args.length == 0 || args[0].isNil()) {
            throw new LuaException("bad argument #1 to '" + funcName + "' (FILE* expected, got " + (args.length == 0 ? "no value" : "nil") + ")");
        }
        if (!(args[0] instanceof LuaUserdata ud) || !(ud.getUserdata() instanceof FileHandle fh)) {
            throw new LuaException("bad argument #1 to '" + funcName + "' (FILE* expected, got " + args[0].typeName() + ")");
        }
        fh.checkOpen();
        return fh;
    }

    private static FileHandle checkFileOrClosed(LuaValue[] args, String funcName) {
        if (args.length == 0 || args[0].isNil()) {
            throw new LuaException("bad argument #1 to '" + funcName + "' (FILE* expected, got " + (args.length == 0 ? "no value" : "nil") + ")");
        }
        if (!(args[0] instanceof LuaUserdata ud) || !(ud.getUserdata() instanceof FileHandle fh)) {
            throw new LuaException("bad argument #1 to '" + funcName + "' (FILE* expected, got " + args[0].typeName() + ")");
        }
        return fh;
    }

    private static boolean checkMode(String mode) {
        if (mode == null || mode.isEmpty()) return false;
        int idx = 0;
        char c = mode.charAt(idx++);
        if (c != 'r' && c != 'w' && c != 'a') return false;
        if (idx < mode.length() && mode.charAt(idx) == '+') {
            idx++;
        }
        while (idx < mode.length()) {
            if (mode.charAt(idx) != 'b') return false;
            idx++;
        }
        return true;
    }

    private static Varargs fileResultError(String msg, String fname, int errno) {
        String text = (fname != null ? fname + ": " : "") + (msg != null ? msg : "I/O error");
        return Varargs.of(LuaNil.NIL, LuaString.valueOf(text), LuaInteger.valueOf(errno != 0 ? errno : 22));
    }

    public static void open(LuaTable globals) {
        LuaTable io = new LuaTable();
        fillInto(io, globals);
        globals.rawset(LuaString.valueOf("io"), io);
    }

    public static void fillInto(LuaTable io, LuaTable globals) {
        LuaTable fileMt = new LuaTable();
        LuaTable fileMethods = new LuaTable();

        fileMt.rawset(LuaString.valueOf("__index"), fileMethods);
        fileMt.rawset(LuaString.valueOf("__name"), LuaString.valueOf("FILE*"));
        fileMt.rawset(LuaString.valueOf("__tostring"), LuaFunction.of(args -> {
            FileHandle fh = checkFileOrClosed(args, "__tostring");
            if (fh.isClosed()) {
                return LuaString.valueOf("file (closed)");
            }
            return LuaString.valueOf("file (0x" + Integer.toHexString(System.identityHashCode(fh)) + ")");
        }));
        fileMt.rawset(LuaString.valueOf("__close"), LuaFunction.of(args -> {
            FileHandle fh = checkFileOrClosed(args, "?");
            if (!fh.isClosed()) {
                try {
                    fh.close();
                } catch (Exception ignored) {}
            }
            return LuaNil.NIL;
        }));
        fileMt.rawset(LuaString.valueOf("__gc"), fileMt.rawget(LuaString.valueOf("__close")));

        // File handle methods
        fileMethods.rawset(LuaString.valueOf("close"), LuaFunction.of(args -> {
            FileHandle fh = checkFileOrClosed(args, "close");
            fh.checkOpen();
            try {
                return fh.close();
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 9);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("flush"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "flush");
            try {
                fh.flush();
                return LuaBoolean.TRUE;
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 22);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("setvbuf"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "setvbuf");
            if (args.length < 2 || !args[1].isString()) {
                throw new LuaException("bad argument #2 to 'setvbuf' (string expected)");
            }
            String mode = args[1].toLuaString();
            if (!"no".equals(mode) && !"full".equals(mode) && !"line".equals(mode)) {
                throw new LuaException("bad argument #2 to 'setvbuf' (invalid option '" + mode + "')");
            }
            int size = 1024;
            if (args.length > 2 && !args[2].isNil()) {
                if (!args[2].isInteger() && !args[2].isFloat()) {
                    throw new LuaException("bad argument #3 to 'setvbuf' (number expected)");
                }
                size = (int) args[2].toLong();
            }
            try {
                fh.setvbuf(mode, size);
                return LuaBoolean.TRUE;
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 22);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("write"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "write");
            try {
                for (int i = 1; i < args.length; i++) {
                    LuaValue arg = args[i];
                    if (!arg.isString() && !arg.isNumber()) {
                        throw new LuaException("bad argument #" + i + " to 'write' (string expected, got " + arg.typeName() + ")");
                    }
                    fh.write(arg.toLuaString());
                }
                return args[0]; // returns the file handle
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 28);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("read"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "read");
            try {
                if (args.length <= 1) {
                    return fh.readOne(LuaString.valueOf("l"));
                }
                List<LuaValue> results = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    LuaValue res = fh.readOne(args[i]);
                    results.add(res);
                    if (res.isNil()) {
                        break;
                    }
                }
                return Varargs.of(results.toArray(new LuaValue[0]));
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 5);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("seek"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "seek");
            String whence = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "cur";
            if (!"set".equals(whence) && !"cur".equals(whence) && !"end".equals(whence)) {
                throw new LuaException("bad argument #2 to 'seek' (invalid option '" + whence + "')");
            }
            long offset = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : 0;
            try {
                long pos = fh.seek(whence, offset);
                return LuaInteger.valueOf(pos);
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 29);
            }
        }));

        fileMethods.rawset(LuaString.valueOf("lines"), LuaFunction.of(args -> {
            FileHandle fh = checkFile(args, "lines");
            if (args.length - 1 > 250) {
                throw new LuaException("bad argument #252 to 'lines' (too many arguments)");
            }
            LuaValue[] readFmts = new LuaValue[Math.max(1, args.length - 1)];
            if (args.length <= 1) {
                readFmts[0] = LuaString.valueOf("l");
            } else {
                System.arraycopy(args, 1, readFmts, 0, args.length - 1);
            }
            return LuaFunction.of(itArgs -> {
                if (fh.isClosed()) {
                    throw new LuaException("file is already closed");
                }
                try {
                    if (readFmts.length == 1) {
                        return fh.readOne(readFmts[0]);
                    }
                    List<LuaValue> results = new ArrayList<>();
                    for (LuaValue fmt : readFmts) {
                        LuaValue res = fh.readOne(fmt);
                        results.add(res);
                        if (res.isNil()) break;
                    }
                    if (results.isEmpty() || results.get(0).isNil()) return LuaNil.NIL;
                    return Varargs.of(results.toArray(new LuaValue[0]));
                } catch (IOException e) {
                    throw new LuaException(e.getMessage());
                }
            });
        }));

        // Standard files
        LuaUserdata stdinUd = new LuaUserdata(new StdFileHandle("stdin", System.in, null), 0);
        stdinUd.setMetatable(fileMt);
        LuaUserdata stdoutUd = new LuaUserdata(new StdFileHandle("stdout", null, System.out), 0);
        stdoutUd.setMetatable(fileMt);
        LuaUserdata stderrUd = new LuaUserdata(new StdFileHandle("stderr", null, System.err), 0);
        stderrUd.setMetatable(fileMt);

        io.rawset(LuaString.valueOf("stdin"), stdinUd);
        io.rawset(LuaString.valueOf("stdout"), stdoutUd);
        io.rawset(LuaString.valueOf("stderr"), stderrUd);

        final LuaUserdata[] currentIn = new LuaUserdata[]{stdinUd};
        final LuaUserdata[] currentOut = new LuaUserdata[]{stdoutUd};
        org.luava.runtime.eval.GCManager.addRootProvider(consumer -> {
            if (currentIn[0] != null) consumer.accept(currentIn[0]);
            if (currentOut[0] != null) consumer.accept(currentOut[0]);
        });

        // io.open
        io.rawset(LuaString.valueOf("open"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'open' (string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            String filename = args[0].toLuaString();
            String mode = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "r";
            if (!checkMode(mode)) {
                throw new LuaException("bad argument #2 to 'open' (invalid mode)");
            }

            try {
                String rafMode = (mode.startsWith("r") && !mode.contains("+")) ? "r" : "rw";
                File file = new File(filename);
                if (mode.contains("w") && file.exists()) {
                    file.delete();
                }
                RandomAccessFile raf = new RandomAccessFile(file, rafMode);
                if (mode.contains("a")) {
                    raf.seek(raf.length());
                }
                RafFileHandle fh = new RafFileHandle(raf, filename, mode);
                LuaUserdata ud = new LuaUserdata(fh, 0);
                ud.setMetatable(fileMt);
                org.luava.runtime.eval.GCManager.register(ud, fileMt.rawget(LuaString.valueOf("__gc")));
                return ud;
            } catch (IOException e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()), LuaInteger.valueOf(2));
            }
        }));

        // io.popen
        io.rawset(LuaString.valueOf("popen"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'popen' (string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            String cmd = args[0].toLuaString();
            String mode = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "r";
            if (!"r".equals(mode) && !"w".equals(mode)) {
                throw new LuaException("bad argument #2 to 'popen' (invalid mode)");
            }
            try {
                if ("r".equals(mode) && !OsTime.isWindows()) {
                    // FIFO so read EOF follows POSIX popen semantics (all
                    // writers closed). The JDK pipe would signal EOF as soon
                    // as the direct shell child exits, dropping output still
                    // produced by background jobs.
                    java.nio.file.Path fifo = java.nio.file.Files.createTempFile("luava-popen", ".fifo");
                    java.nio.file.Files.deleteIfExists(fifo);
                    int mk = new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor();
                    if (mk == 0) {
                        final java.io.InputStream[] holder = new java.io.InputStream[1];
                        final java.io.IOException[] err = new java.io.IOException[1];
                        Thread reader = Thread.ofVirtual().start(() -> {
                            try {
                                holder[0] = new java.io.FileInputStream(fifo.toFile());
                            } catch (java.io.IOException e) {
                                err[0] = e;
                            }
                        });
                        OsTime.ShellRun run = OsTime.startShellRedirect(cmd, fifo.toFile());
                        reader.join(10000);
                        if (err[0] != null || holder[0] == null) {
                            run.process().destroy();
                            java.nio.file.Files.deleteIfExists(fifo);
                            throw new java.io.IOException("cannot open popen stream");
                        }
                        ProcessFileHandle pfh = new ProcessFileHandle(
                            run.process(), mode, cmd, run.sentinel(), holder[0], fifo);
                        LuaUserdata ud = new LuaUserdata(pfh, 0);
                        ud.setMetatable(fileMt);
                        org.luava.runtime.eval.GCManager.register(ud, fileMt.rawget(LuaString.valueOf("__gc")));
                        return ud;
                    }
                    java.nio.file.Files.deleteIfExists(fifo);
                }
                OsTime.ShellRun run = OsTime.startShell(cmd, false);
                ProcessFileHandle pfh = new ProcessFileHandle(run.process(), mode, cmd, run.sentinel());
                LuaUserdata ud = new LuaUserdata(pfh, 0);
                ud.setMetatable(fileMt);
                org.luava.runtime.eval.GCManager.register(ud, fileMt.rawget(LuaString.valueOf("__gc")));
                return ud;
            } catch (java.io.IOException e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()), LuaInteger.valueOf(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("interrupted"), LuaInteger.valueOf(2));
            }
        }));

        // io.type
        io.rawset(LuaString.valueOf("type"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'type' (value expected)");
            }
            if (!(args[0] instanceof LuaUserdata ud) || !(ud.getUserdata() instanceof FileHandle fh)) {
                return LuaNil.NIL;
            }
            return LuaString.valueOf(fh.isClosed() ? "closed file" : "file");
        }));

        io.rawset(LuaString.valueOf("input"), LuaFunction.of(args -> {
            if (args.length == 0 || args[0].isNil()) {
                return currentIn[0];
            }
            if (args[0] instanceof LuaUserdata ud && ud.getUserdata() instanceof FileHandle) {
                currentIn[0] = ud;
                return ud;
            }
            if (args[0].isString()) {
                LuaValue openRes = io.rawget(LuaString.valueOf("open")).call(args[0], LuaString.valueOf("r"));
                if (openRes.isNil()) throw new LuaException("cannot open file '" + args[0].toLuaString() + "'");
                currentIn[0] = (LuaUserdata) openRes;
                return openRes;
            }
            throw new LuaException("bad argument #1 to 'input' (FILE* expected, got " + args[0].typeName() + ")");
        }));

        io.rawset(LuaString.valueOf("output"), LuaFunction.of(args -> {
            if (args.length == 0 || args[0].isNil()) {
                return currentOut[0];
            }
            if (args[0] instanceof LuaUserdata ud && ud.getUserdata() instanceof FileHandle) {
                currentOut[0] = ud;
                return ud;
            }
            if (args[0].isString()) {
                LuaValue openRes = io.rawget(LuaString.valueOf("open")).call(args[0], LuaString.valueOf("w"));
                if (openRes.isNil()) throw new LuaException("cannot open file '" + args[0].toLuaString() + "'");
                currentOut[0] = (LuaUserdata) openRes;
                return openRes;
            }
            throw new LuaException("bad argument #1 to 'output' (FILE* expected, got " + args[0].typeName() + ")");
        }));

        // io.close, io.flush, io.read, io.write, io.lines delegating
        io.rawset(LuaString.valueOf("close"), LuaFunction.of(args -> {
            LuaValue target = (args.length > 0 && !args[0].isNil()) ? args[0] : currentOut[0];
            return fileMethods.rawget(LuaString.valueOf("close")).call(target);
        }));

        io.rawset(LuaString.valueOf("flush"), LuaFunction.of(args -> {
            LuaUserdata out = currentOut[0];
            if (out == null || !(out.getUserdata() instanceof FileHandle fh)) {
                throw new LuaException("bad argument to 'flush' (FILE* expected)");
            }
            if (fh.isClosed()) {
                throw new LuaException("default output file is closed");
            }
            return fileMethods.rawget(LuaString.valueOf("flush")).call(out);
        }));

        io.rawset(LuaString.valueOf("read"), LuaFunction.of(args -> {
            LuaUserdata in = currentIn[0];
            if (in == null || !(in.getUserdata() instanceof FileHandle fh)) {
                throw new LuaException("bad argument to 'read' (FILE* expected)");
            }
            if (fh.isClosed()) {
                throw new LuaException("default input file is closed");
            }
            LuaValue[] pass = new LuaValue[args.length + 1];
            pass[0] = in;
            System.arraycopy(args, 0, pass, 1, args.length);
            return fileMethods.rawget(LuaString.valueOf("read")).call(pass);
        }));

        io.rawset(LuaString.valueOf("write"), LuaFunction.of(args -> {
            LuaUserdata out = currentOut[0];
            if (out == null || !(out.getUserdata() instanceof FileHandle fh)) {
                throw new LuaException("bad argument to 'write' (FILE* expected)");
            }
            if (fh.isClosed()) {
                throw new LuaException("default output file is closed");
            }
            try {
                for (int i = 0; i < args.length; i++) {
                    LuaValue arg = args[i];
                    if (!arg.isString() && !arg.isNumber()) {
                        throw new LuaException("bad argument #" + (i + 1) + " to 'write' (string expected, got " + arg.typeName() + ")");
                    }
                    fh.write(arg.toLuaString());
                }
                return out;
            } catch (IOException e) {
                return fileResultError(e.getMessage(), null, 28);
            }
        }));

        io.rawset(LuaString.valueOf("lines"), LuaFunction.of(args -> {
            if (args.length - 1 > 250) {
                throw new LuaException("bad argument #252 to 'lines' (too many arguments)");
            }
            if (args.length == 0 || args[0].isNil()) {
                LuaUserdata in = currentIn[0];
                if (in == null || !(in.getUserdata() instanceof FileHandle fh)) {
                    throw new LuaException("bad argument to 'lines' (FILE* expected)");
                }
                if (fh.isClosed()) {
                    throw new LuaException("default input file is closed");
                }
                LuaValue[] pass = new LuaValue[Math.max(1, args.length)];
                pass[0] = in;
                for (int i = 1; i < args.length; i++) {
                    pass[i] = args[i];
                }
                return fileMethods.rawget(LuaString.valueOf("lines")).call(pass);
            }
            LuaValue fileRes = io.rawget(LuaString.valueOf("open")).call(args[0], LuaString.valueOf("r"));
            if (fileRes.isNil()) {
                throw new LuaException("cannot open file '" + args[0].toLuaString() + "'");
            }
            LuaUserdata fileUd = (LuaUserdata) fileRes;
            FileHandle fh = (FileHandle) fileUd.getUserdata();
            LuaValue[] readFmts = new LuaValue[Math.max(1, args.length - 1)];
            if (args.length <= 1) {
                readFmts[0] = LuaString.valueOf("l");
            } else {
                System.arraycopy(args, 1, readFmts, 0, args.length - 1);
            }
            LuaFunction iterFn = LuaFunction.of(itArgs -> {
                if (fh.isClosed()) {
                    throw new LuaException("file is already closed");
                }
                try {
                    if (readFmts.length == 1) {
                        LuaValue line = fh.readOne(readFmts[0]);
                        if (line.isNil()) {
                            try { fh.close(); } catch (Exception ignored) {}
                            return LuaNil.NIL;
                        }
                        return line;
                    }
                    List<LuaValue> results = new ArrayList<>();
                    for (LuaValue fmt : readFmts) {
                        LuaValue res = fh.readOne(fmt);
                        results.add(res);
                        if (res.isNil()) break;
                    }
                    if (results.isEmpty() || results.get(0).isNil()) {
                        try { fh.close(); } catch (Exception ignored) {}
                        return LuaNil.NIL;
                    }
                    return Varargs.of(results.toArray(new LuaValue[0]));
                } catch (IOException e) {
                    try { fh.close(); } catch (Exception ignored) {}
                    throw new LuaException(e.getMessage());
                }
            });
            iterFn.getUpvalues().add(new org.luava.runtime.eval.Upvalue("fileUd", new org.luava.runtime.eval.Environment.VariableSlot(fileUd, false, false)));
            return Varargs.of(iterFn, LuaNil.NIL, LuaNil.NIL, fileUd);
        }));

        io.rawset(LuaString.valueOf("tmpfile"), LuaFunction.of(args -> {
            try {
                File f = File.createTempFile("luatmp_", ".bin");
                f.deleteOnExit();
                RandomAccessFile raf = new RandomAccessFile(f, "rw");
                LuaUserdata ud = new LuaUserdata(new RafFileHandle(raf, f.getAbsolutePath(), "w+b"), 0);
                ud.setMetatable(fileMt);
                org.luava.runtime.eval.GCManager.register(ud, fileMt.rawget(LuaString.valueOf("__gc")));
                return ud;
            } catch (IOException e) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(e.getMessage()));
            }
        }));
    }
}
