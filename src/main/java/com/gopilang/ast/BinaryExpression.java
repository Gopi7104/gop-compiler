package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record BinaryExpression(Expr left, BinaryOperator operator, Expr right, SourceRange range) implements Expr {
}
