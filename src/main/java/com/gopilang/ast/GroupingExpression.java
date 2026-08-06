package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** A parenthesized expression, {@code (inner)}, kept only to preserve source structure for printing. */
public record GroupingExpression(Expr inner, SourceRange range) implements Expr {
}
