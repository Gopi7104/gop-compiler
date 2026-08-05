package com.gopilang.lexer;

import com.gopilang.errors.ErrorPhase;
import com.gopilang.errors.GopiError;
import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

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
