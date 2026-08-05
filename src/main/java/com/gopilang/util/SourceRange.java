package com.gopilang.util;

public record SourceRange(SourceLocation start, SourceLocation end) {

    public static SourceRange point(SourceLocation location) {
        return new SourceRange(location, location);
    }

    public boolean isSingleLine() {
        return start.line() == end.line();
    }

    @Override
    public String toString() {
        return isSingleLine() ? start.toString() : start + "-" + end;
    }
}
