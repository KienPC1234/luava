package org.luava.runtime.bytecode;

public final class OpCode {
    public static final int OP_MOVE = 0;
    public static final int OP_LOADI = 1;
    public static final int OP_LOADF = 2;
    public static final int OP_LOADK = 3;
    public static final int OP_LOADKX = 4;
    public static final int OP_LOADFALSE = 5;
    public static final int OP_LFALSESKIP = 6;
    public static final int OP_LOADTRUE = 7;
    public static final int OP_LOADNIL = 8;
    public static final int OP_GETUPVAL = 9;
    public static final int OP_SETUPVAL = 10;
    public static final int OP_GETTABUP = 11;
    public static final int OP_GETTABLE = 12;
    public static final int OP_GETI = 13;
    public static final int OP_GETFIELD = 14;
    public static final int OP_SETTABUP = 15;
    public static final int OP_SETTABLE = 16;
    public static final int OP_SETI = 17;
    public static final int OP_SETFIELD = 18;
    public static final int OP_NEWTABLE = 19;
    public static final int OP_SELF = 20;
    public static final int OP_ADDI = 21;
    public static final int OP_ADDK = 22;
    public static final int OP_SUBK = 23;
    public static final int OP_MULK = 24;
    public static final int OP_MODK = 25;
    public static final int OP_POWK = 26;
    public static final int OP_DIVK = 27;
    public static final int OP_IDIVK = 28;
    public static final int OP_BANDK = 29;
    public static final int OP_BORK = 30;
    public static final int OP_BXORK = 31;
    public static final int OP_SHRI = 32;
    public static final int OP_SHLI = 33;
    public static final int OP_ADD = 34;
    public static final int OP_SUB = 35;
    public static final int OP_MUL = 36;
    public static final int OP_MOD = 37;
    public static final int OP_POW = 38;
    public static final int OP_DIV = 39;
    public static final int OP_IDIV = 40;
    public static final int OP_BAND = 41;
    public static final int OP_BOR = 42;
    public static final int OP_BXOR = 43;
    public static final int OP_SHL = 44;
    public static final int OP_SHR = 45;
    public static final int OP_MMBIN = 46;
    public static final int OP_MMBINI = 47;
    public static final int OP_MMBINK = 48;
    public static final int OP_UNM = 49;
    public static final int OP_BNOT = 50;
    public static final int OP_NOT = 51;
    public static final int OP_LEN = 52;
    public static final int OP_CONCAT = 53;
    public static final int OP_CLOSE = 54;
    public static final int OP_TBC = 55;
    public static final int OP_JMP = 56;
    public static final int OP_EQ = 57;
    public static final int OP_LT = 58;
    public static final int OP_LE = 59;
    public static final int OP_EQK = 60;
    public static final int OP_EQI = 61;
    public static final int OP_LTI = 62;
    public static final int OP_LEI = 63;
    public static final int OP_GTI = 64;
    public static final int OP_GEI = 65;
    public static final int OP_TEST = 66;
    public static final int OP_TESTSET = 67;
    public static final int OP_CALL = 68;
    public static final int OP_TAILCALL = 69;
    public static final int OP_RETURN = 70;
    public static final int OP_RETURN0 = 71;
    public static final int OP_RETURN1 = 72;
    public static final int OP_FORLOOP = 73;
    public static final int OP_FORPREP = 74;
    public static final int OP_TFORPREP = 75;
    public static final int OP_TFORCALL = 76;
    public static final int OP_TFORLOOP = 77;
    public static final int OP_SETLIST = 78;
    public static final int OP_CLOSURE = 79;
    public static final int OP_VARARG = 80;
    public static final int OP_VARARGPREP = 81;
    public static final int OP_EXTRAARG = 82;

    public static final int NUM_OPCODES = 83;

    public static final String[] OP_NAMES = new String[] {
        "MOVE", "LOADI", "LOADF", "LOADK", "LOADKX", "LOADFALSE", "LFALSESKIP", "LOADTRUE",
        "LOADNIL", "GETUPVAL", "SETUPVAL", "GETTABUP", "GETTABLE", "GETI", "GETFIELD",
        "SETTABUP", "SETTABLE", "SETI", "SETFIELD", "NEWTABLE", "SELF", "ADDI", "ADDK",
        "SUBK", "MULK", "MODK", "POWK", "DIVK", "IDIVK", "BANDK", "BORK", "BXORK",
        "SHRI", "SHLI", "ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV", "BAND",
        "BOR", "BXOR", "SHL", "SHR", "MMBIN", "MMBINI", "MMBINK", "UNM", "BNOT",
        "NOT", "LEN", "CONCAT", "CLOSE", "TBC", "JMP", "EQ", "LT", "LE", "EQK",
        "EQI", "LTI", "LEI", "GTI", "GEI", "TEST", "TESTSET", "CALL", "TAILCALL",
        "RETURN", "RETURN0", "RETURN1", "FORLOOP", "FORPREP", "TFORPREP", "TFORCALL",
        "TFORLOOP", "SETLIST", "CLOSURE", "VARARG", "VARARGPREP", "EXTRAARG"
    };

    public static String getOpName(int op) {
        if (op >= 0 && op < OP_NAMES.length) {
            return OP_NAMES[op];
        }
        return "OP_" + op;
    }

    private OpCode() {}
}
