package com.gopilang.parser;

import com.gopilang.errors.ErrorPhase;
import com.gopilang.errors.GopiError;
import com.gopilang.util.SourceRange;

/**
 * Thrown to unwind out of a broken statement/expression during parsing.
 * Always caught within {@link Parser#parseProgram()} and converted to a
 * {@link com.gopilang.errors.Diagnostic} — never escapes the parser.
 */
public final class ParseError extends GopiError {

    public ParseError(String message, SourceRange range) {
        this(message, range, null);
    }

    public ParseError(String message, SourceRange range, String suggestion) {
        super(ErrorPhase.SYNTAX, range, message, suggestion);
    }
}
