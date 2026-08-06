package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** Reading one element of an array, {@code array[index]}. */
public record ArrayAccessExpression(Expr array, Expr index, SourceRange range) implements Expr {
}
