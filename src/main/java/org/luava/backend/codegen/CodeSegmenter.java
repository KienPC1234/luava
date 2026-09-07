package org.luava.backend.codegen;

import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;

import java.util.ArrayList;
import java.util.List;

public final class CodeSegmenter {
    // JVM method code limit is 65535 bytes; threshold set safely to prevent overflow
    private static final int ESTIMATED_MAX_BYTECODE_PER_SEGMENT = 45000;
    private static final int ESTIMATED_BYTES_PER_STATEMENT = 40;

    public record Segment(String methodName, List<Statement> statements) {}

    public static List<Segment> segmentBlock(Statements.BlockStmt block, String baseMethodName) {
        List<Segment> segments = new ArrayList<>();
        List<Statement> currentList = new ArrayList<>();
        int estimatedSize = 0;
        int segmentIndex = 0;

        for (Statement stmt : block.statements()) {
            currentList.add(stmt);
            estimatedSize += ESTIMATED_BYTES_PER_STATEMENT;

            if (estimatedSize >= ESTIMATED_MAX_BYTECODE_PER_SEGMENT) {
                segments.add(new Segment(baseMethodName + "$seg" + segmentIndex++, currentList));
                currentList = new ArrayList<>();
                estimatedSize = 0;
            }
        }

        if (!currentList.isEmpty()) {
            segments.add(new Segment(baseMethodName + (segmentIndex > 0 ? "$seg" + segmentIndex : ""), currentList));
        }

        return segments;
    }
}
