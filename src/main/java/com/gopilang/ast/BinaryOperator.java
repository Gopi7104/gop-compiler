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
    OR
}
