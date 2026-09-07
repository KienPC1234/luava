package org.luava.runtime.concurrency;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaType;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

public final class LuaCoroutine extends LuaValue {
    public enum Status {
        SUSPENDED("suspended"),
        RUNNING("running"),
        NORMAL("normal"),
        DEAD("dead");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final ThreadLocal<LuaCoroutine> CURRENT_COROUTINE = new ThreadLocal<>();

    private final LuaFunction entryFunction;
    private final BlockingQueue<LuaValue[]> inQueue = new SynchronousQueue<>();
    private final BlockingQueue<LuaValue[]> outQueue = new SynchronousQueue<>();
    private volatile Status status = Status.SUSPENDED;
    private Thread virtualThread;
    private Throwable error;

    public LuaCoroutine(LuaFunction function) {
        this.entryFunction = function;
    }

    public static LuaCoroutine running() {
        return CURRENT_COROUTINE.get();
    }

    public static boolean isYieldable() {
        return CURRENT_COROUTINE.get() != null;
    }

    public Status getStatus() {
        return status;
    }

    public LuaValue[] resume(LuaValue... args) {
        if (status == Status.DEAD) {
            return new LuaValue[]{LuaValue.valueOf(false), LuaString.valueOf("cannot resume dead coroutine")};
        }

        if (status == Status.SUSPENDED && virtualThread == null) {
            // First resume: start the Virtual Thread
            status = Status.RUNNING;
            virtualThread = Thread.ofVirtual().name("lua-coroutine-" + System.identityHashCode(this)).start(() -> {
                CURRENT_COROUTINE.set(this);
                try {
                    LuaValue[] initialArgs = inQueue.take();
                    LuaValue result = entryFunction.invoke(initialArgs);
                    status = Status.DEAD;
                    LuaValue[] resArray;
                    if (result instanceof Varargs va) {
                        resArray = va.toArray();
                    } else {
                        resArray = new LuaValue[]{result};
                    }
                    outQueue.put(resArray);
                } catch (InterruptedException e) {
                    status = Status.DEAD;
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    status = Status.DEAD;
                    error = t;
                    try {
                        outQueue.put(new LuaValue[]{LuaString.valueOf(t.getMessage() != null ? t.getMessage() : t.toString())});
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    CURRENT_COROUTINE.remove();
                }
            });
        }

        try {
            status = Status.RUNNING;
            inQueue.put(args != null ? args : new LuaValue[0]);
            LuaValue[] yielded = outQueue.take();

            if (error != null) {
                return new LuaValue[]{LuaValue.valueOf(false), yielded.length > 0 ? yielded[0] : LuaString.valueOf("error in coroutine")};
            }

            LuaValue[] returnVals = new LuaValue[yielded.length + 1];
            returnVals[0] = LuaValue.valueOf(true);
            System.arraycopy(yielded, 0, returnVals, 1, yielded.length);
            return returnVals;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LuaException("Coroutine resume interrupted");
        }
    }

    public static LuaValue[] yield(LuaValue... args) {
        LuaCoroutine current = CURRENT_COROUTINE.get();
        if (current == null) {
            throw new LuaException("attempt to yield from outside a coroutine");
        }
        current.status = Status.SUSPENDED;
        try {
            current.outQueue.put(args != null ? args : new LuaValue[0]);
            return current.inQueue.take();
        } catch (InterruptedException e) {
            current.status = Status.DEAD;
            Thread.currentThread().interrupt();
            throw new LuaException("Coroutine yield interrupted");
        } finally {
            if (current.status != Status.DEAD) {
                current.status = Status.RUNNING;
            }
        }
    }

    @Override
    public LuaType type() {
        return LuaType.THREAD;
    }

    @Override
    public boolean isThread() {
        return true;
    }

    @Override
    public String toLuaString() {
        return "thread: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}
