package com.gopilang.util;

/** A single 1-based line/column position in source text. */
public record SourceLocation(int line, int column) {

    @Override
    public String toString() {
        return "%d:%d".formatted(line, column);
    }
}
