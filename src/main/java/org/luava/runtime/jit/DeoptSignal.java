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

    public DeoptSignal(int pc) {
        super(null, null, false, false);
        this.pc = pc;
    }
}
