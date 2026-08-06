package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** An array's length, {@code array.len()}. */
public record ArrayLengthExpression(Expr array, SourceRange range) implements Expr {
}
