package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

/** An array-creation expression, {@code new elementType[size]} (e.g. {@code new num[5]}). */
public record NewArrayExpression(PrimitiveType elementType, Expr size, SourceRange range) implements Expr {
}
