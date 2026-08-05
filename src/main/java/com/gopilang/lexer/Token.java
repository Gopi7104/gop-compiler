package com.gopilang.lexer;

import com.gopilang.util.SourceLocation;

public record Token(TokenType type, String lexeme, Object literal, SourceLocation location) {

    public int length() {
        return lexeme.length();
    }

    @Override
    public String toString() {
        return "%s '%s' (%s)".formatted(type, lexeme, location);
    }
}
