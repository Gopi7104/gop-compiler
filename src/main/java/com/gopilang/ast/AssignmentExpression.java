package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record AssignmentExpression(String target, Expr value, SourceRange range) implements Expr {
}
