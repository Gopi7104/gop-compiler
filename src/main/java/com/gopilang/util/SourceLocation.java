package com.gopilang.util;

public record SourceLocation(int line, int column) {

    @Override
    public String toString() {
        return "%d:%d".formatted(line, column);
    }
}
