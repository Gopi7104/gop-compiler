package com.gopilang.ast;

/** The binary operators GopiLang's grammar recognizes; legality and result type are decided by {@code TypeRules}. */
public enum BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MODULO,
    EQUAL,
    NOT_EQUAL,
    LESS,
    GREATER,
    LESS_EQUAL,
    GREATER_EQUAL,
    AND,
    OR;

    // The source-syntax spelling, for user-facing diagnostics only — see
    // PrimitiveType.displayName().
    public String symbol() {
        return switch (this) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            case EQUAL -> "==";
            case NOT_EQUAL -> "!=";
            case LESS -> "<";
            case GREATER -> ">";
            case LESS_EQUAL -> "<=";
            case GREATER_EQUAL -> ">=";
            case AND -> "&&";
            case OR -> "||";
        };
    }
}
