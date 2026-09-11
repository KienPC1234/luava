/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

public final class Instruction {
    public static final int POS_OP = 0;
    public static final int SIZE_OP = 7;
    public static final int MASK_OP = (1 << SIZE_OP) - 1;

    public static final int POS_A = 7;
    public static final int SIZE_A = 8;
    public static final int MASK_A = (1 << SIZE_A) - 1;

    public static final int POS_k = 15;
    public static final int SIZE_k = 1;
    public static final int MASK_k = 1;

    public static final int POS_B = 16;
    public static final int SIZE_B = 8;
    public static final int MASK_B = (1 << SIZE_B) - 1;

    public static final int POS_C = 24;
    public static final int SIZE_C = 8;
    public static final int MASK_C = (1 << SIZE_C) - 1;

    public static final int POS_Bx = 15;
    public static final int SIZE_Bx = 17;
    public static final int MASK_Bx = (1 << SIZE_Bx) - 1;
    public static final int OFFSET_sBx = (1 << (SIZE_Bx - 1)) - 1; // 65535

    public static final int POS_Ax = 7;
    public static final int SIZE_Ax = 25;
    public static final int MASK_Ax = (1 << SIZE_Ax) - 1;

    public static final int POS_sJ = 7;
    public static final int SIZE_sJ = 25;
    public static final int MASK_sJ = (1 << SIZE_sJ) - 1;
    public static final int OFFSET_sJ = (1 << (SIZE_sJ - 1)) - 1; // 16777215

    public static int getOp(int i) {
        return (i >>> POS_OP) & MASK_OP;
    }

    public static int getA(int i) {
        return (i >>> POS_A) & MASK_A;
    }

    public static int getB(int i) {
        return (i >>> POS_B) & MASK_B;
    }

    public static int getC(int i) {
        return (i >>> POS_C) & MASK_C;
    }

    public static int getk(int i) {
        return (i >>> POS_k) & MASK_k;
    }

    public static int getBx(int i) {
        return (i >>> POS_Bx) & MASK_Bx;
    }

    public static int getsBx(int i) {
        return ((i >>> POS_Bx) & MASK_Bx) - OFFSET_sBx;
    }

    public static int getAx(int i) {
        return (i >>> POS_Ax) & MASK_Ax;
    }

    public static int getsJ(int i) {
        return ((i >>> POS_sJ) & MASK_sJ) - OFFSET_sJ;
    }

    public static int encodeABC(int op, int a, int b, int c, int k) {
        return ((op & MASK_OP) << POS_OP)
                | ((a & MASK_A) << POS_A)
                | ((k & MASK_k) << POS_k)
                | ((b & MASK_B) << POS_B)
                | ((c & MASK_C) << POS_C);
    }

    public static int encodeABC(int op, int a, int b, int c) {
        return encodeABC(op, a, b, c, 0);
    }

    public static int encodeABx(int op, int a, int bx) {
        return ((op & MASK_OP) << POS_OP)
                | ((a & MASK_A) << POS_A)
                | ((bx & MASK_Bx) << POS_Bx);
    }

    public static int encodesBx(int op, int a, int sbx) {
        int bx = sbx + OFFSET_sBx;
        return encodeABx(op, a, bx);
    }

    public static int encodeAx(int op, int ax) {
        return ((op & MASK_OP) << POS_OP)
                | ((ax & MASK_Ax) << POS_Ax);
    }

    public static int encodesJ(int op, int sj) {
        int uj = sj + OFFSET_sJ;
        return ((op & MASK_OP) << POS_OP)
                | ((uj & MASK_sJ) << POS_sJ);
    }

    public static int setsJ(int i, int sj) {
        int uj = sj + OFFSET_sJ;
        return (i & ~(MASK_sJ << POS_sJ)) | ((uj & MASK_sJ) << POS_sJ);
    }

    public static int setsBx(int i, int sbx) {
        int bx = sbx + OFFSET_sBx;
        return (i & ~(MASK_Bx << POS_Bx)) | ((bx & MASK_Bx) << POS_Bx);
    }

    public static int setA(int i, int a) {
        return (i & ~(MASK_A << POS_A)) | ((a & MASK_A) << POS_A);
    }

    public static int setB(int i, int b) {
        return (i & ~(MASK_B << POS_B)) | ((b & MASK_B) << POS_B);
    }

    public static int setC(int i, int c) {
        return (i & ~(MASK_C << POS_C)) | ((c & MASK_C) << POS_C);
    }

    private Instruction() {}
}
