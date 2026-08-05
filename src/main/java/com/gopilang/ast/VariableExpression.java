package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record VariableExpression(String name, SourceRange range) implements Expr {
}
