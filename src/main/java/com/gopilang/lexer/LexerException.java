package com.gopilang.lexer;

import com.gopilang.errors.ErrorPhase;
import com.gopilang.errors.GopiError;
import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

/**
 * Thrown to unwind out of a broken token during {@link Lexer#scanTokens()}.
 * Always caught within the same call and converted to a {@link
 * com.gopilang.errors.Diagnostic} — never escapes the lexer.
 */
public final class LexerException extends GopiError {

    public LexerException(String message, SourceLocation location) {
        this(message, location, null);
    }

    public LexerException(String message, SourceLocation location, String suggestion) {
        super(ErrorPhase.LEXICAL, location, message, suggestion);
    }

    public LexerException(String message, SourceRange range) {
        this(message, range, null);
    }

    public LexerException(String message, SourceRange range, String suggestion) {
        super(ErrorPhase.LEXICAL, range, message, suggestion);
    }
}
