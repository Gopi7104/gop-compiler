package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

/**
 * A literal value (int, float, bool, or string). The compact constructor
 * enforces that {@code value}'s runtime Java type actually matches the
 * declared {@code type} (e.g. {@code INT} must box an {@code Integer}) —
 * this is the one AST node that self-validates, since the lexer/parser
 * already know the literal's kind and could otherwise construct a
 * mismatched pair by mistake.
 */
public record LiteralExpression(Object value, PrimitiveType type, SourceRange range) implements Expr {

    public LiteralExpression {
        boolean matches = switch (type) {
            case INT -> value instanceof Integer;
            case FLOAT -> value instanceof Double;
            case BOOL -> value instanceof Boolean;
            case STRING -> value instanceof String;
            case VOID -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "LiteralExpression value " + value + " does not match declared type " + type);
        }
    }
}
