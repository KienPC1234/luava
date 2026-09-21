/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

/**
 * Thrown by JIT-compiled code when a runtime guard fails (unexpected type,
 * non-self callee, stack-capacity overflow, ...). Carries no stack trace for
 * speed; the interpreter catches it at the JIT entry boundary and re-runs
 * the call with the bytecode VM.
 */
public final class DeoptSignal extends Error {
    public final int pc;
    /**
     * True for a deopt the compiled code always takes for this proto (an
     * impure proto deopts every CALL), not a runtime guard failure. Such
     * deopts are expected, so they must not count toward the storm-disarm
     * budget: a hot loop before the call still benefits, and the call itself
     * resumes in the interpreter every time.
     */
    public final boolean structural;

    public DeoptSignal(int pc) {
        this(pc, false);
    }

    public DeoptSignal(int pc, boolean structural) {
        super(null, null, false, false);
        this.pc = pc;
        this.structural = structural;
    }
}
