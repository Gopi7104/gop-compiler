package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** A binary operation, {@code left operator right} (e.g. {@code a + b}, {@code a < b}). */
public record BinaryExpression(Expr left, BinaryOperator operator, Expr right, SourceRange range) implements Expr {
}
