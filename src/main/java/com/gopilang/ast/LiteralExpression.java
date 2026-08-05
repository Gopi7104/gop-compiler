package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

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
