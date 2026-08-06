package com.gopilang.errors;

import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

/**
 * A single, phase-tagged, user-facing compiler error: the only thing that
 * crosses phase boundaries (unlike {@link GopiError}, which never leaves
 * the phase that threw it). Each phase owns its own {@link DiagnosticReporter}.
 */
public record Diagnostic(ErrorPhase phase, SourceRange range, String message, String suggestion) {

    /** Converts a caught {@link GopiError} into the phase-crossing {@code Diagnostic} form. */
    public static Diagnostic from(GopiError error) {
        return new Diagnostic(error.phase(), error.range(), error.getMessage(), error.suggestion());
    }

    /** Renders this diagnostic as a human-readable, source-annotated message with a caret under the offending range. */
    public String render(String sourceLine) {
        SourceLocation start = range.start();
        SourceLocation end = range.end();
        String gutter = " ".repeat(String.valueOf(start.line()).length());

        int caretWidth = range.isSingleLine()
                ? Math.max(1, end.column() - start.column() + 1)
                : Math.max(1, sourceLine.length() - (start.column() - 1));

        StringBuilder sb = new StringBuilder();
        sb.append(phase.label()).append(": ").append(message).append('\n');
        sb.append(gutter).append("--> line ").append(start.line())
                .append(", column ").append(start.column()).append('\n');
        sb.append(gutter).append(" |\n");
        sb.append(start.line()).append(" | ").append(sourceLine).append('\n');
        sb.append(gutter).append(" | ")
                .append(" ".repeat(Math.max(0, start.column() - 1)))
                .append("^".repeat(caretWidth))
                .append('\n');

        if (suggestion != null) {
            sb.append('\n').append("Suggestion: ").append(suggestion).append('\n');
        }

        return sb.toString();
    }
}
