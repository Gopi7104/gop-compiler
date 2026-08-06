package com.gopilang.ast;

/** The unary operators GopiLang's grammar recognizes: arithmetic negation and logical not. */
public enum UnaryOperator {
    NEGATE,
    NOT;

    // The source-syntax spelling, for user-facing diagnostics only — see
    // PrimitiveType.displayName().
    public String symbol() {
        return switch (this) {
            case NEGATE -> "-";
            case NOT -> "!";
        };
    }
}
