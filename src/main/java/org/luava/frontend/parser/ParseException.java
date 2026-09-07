package org.luava.frontend.parser;

public class ParseException extends RuntimeException {
    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
        super(message + " at line " + line + ", col " + column);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
