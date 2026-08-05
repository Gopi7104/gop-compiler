package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record UnaryExpression(UnaryOperator operator, Expr operand, SourceRange range) implements Expr {
}
