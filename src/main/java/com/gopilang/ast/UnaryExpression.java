package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** A unary operation, {@code operator operand} (e.g. {@code -x}, {@code !flag}). */
public record UnaryExpression(UnaryOperator operator, Expr operand, SourceRange range) implements Expr {
}
