package com.gopilang.lexer;

import com.gopilang.util.SourceLocation;

/** One lexed token: its kind, exact source text, decoded literal value (or {@code null}), and start location. */
public record Token(TokenType type, String lexeme, Object literal, SourceLocation location) {

    /** Length of this token's lexeme, in characters. */
    public int length() {
        return lexeme.length();
    }

    @Override
    public String toString() {
        return "%s '%s' (%s)".formatted(type, lexeme, location);
    }
}
