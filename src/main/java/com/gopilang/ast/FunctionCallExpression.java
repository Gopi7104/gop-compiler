package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

public record FunctionCallExpression(String calleeName, List<Expr> arguments, SourceRange range) implements Expr {
}
