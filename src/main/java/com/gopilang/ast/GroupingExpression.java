package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record GroupingExpression(Expr inner, SourceRange range) implements Expr {
}
