package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record ExpressionStatement(Expr expression, SourceRange range) implements Stmt {
}
