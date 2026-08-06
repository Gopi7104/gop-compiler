package com.gopilang.util;

/** A span of source text from {@code start} to {@code end}, inclusive, used by every AST node and diagnostic. */
public record SourceRange(SourceLocation start, SourceLocation end) {

    /** A zero-width range at a single location, used when a diagnostic or token has no meaningful extent. */
    public static SourceRange point(SourceLocation location) {
        return new SourceRange(location, location);
    }

    /** Whether this range starts and ends on the same line. */
    public boolean isSingleLine() {
        return start.line() == end.line();
    }

    @Override
    public String toString() {
        return isSingleLine() ? start.toString() : start + "-" + end;
    }
}
