package org.luava.frontend.parser;

public class ParseException extends RuntimeException {
    private final int line;
    private final int column;
    private final String rawMessage;

    public ParseException(String message, int line, int column) {
        super(message + " at line " + line + ", col " + column);
        this.rawMessage = message;
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public String rawMessage() {
        return rawMessage;
    }

    public static final int LUA_IDSIZE = 60;

    /**
     * Format a chunk name into a short source identifier matching Lua 5.4's
     * luaO_chunkid behavior. The result is always at most LUA_IDSIZE-1 (59) chars.
     */
    public static String formatChunkName(String chunkName) {
        if (chunkName == null) {
            return "[string \"\"]";
        }
        if (chunkName.startsWith("=")) {
            // Literal source: use as-is, truncated to 59 chars
            String s = chunkName.substring(1);
            if (s.length() > LUA_IDSIZE - 1) {
                return s.substring(0, LUA_IDSIZE - 1);
            }
            return s;
        }
        if (chunkName.startsWith("@")) {
            // File name: truncate from front with "..." if too long
            String name = chunkName.substring(1);
            if (name.length() <= LUA_IDSIZE - 1) {
                return name;
            }
            // Reserve 3 chars for "..." and keep the rest from the end, max 59 total
            int keep = LUA_IDSIZE - 1 - 3; // 56 chars of name
            return "..." + name.substring(name.length() - keep);
        }
        // String source: format as [string "..."]
        // PRE="[string \"" (9), RETS="..." (3), POS="\"]" (2), plus \0: total overhead = 9+3+2+1=15
        int nl = chunkName.indexOf('\n');
        int maxContent = LUA_IDSIZE - 1 - 9 - 3 - 2; // 59 - 9 - 5 = 45
        if (chunkName.length() < maxContent && nl == -1) {
            return "[string \"" + chunkName + "\"]";
        }
        int len = (nl != -1) ? nl : chunkName.length();
        if (len > maxContent) len = maxContent;
        return "[string \"" + chunkName.substring(0, len) + "...\"]";
    }

    public String format(String chunkName) {
        return formatChunkName(chunkName) + ":" + line + ": " + rawMessage;
    }
}
