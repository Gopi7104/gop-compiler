package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record WhileStatement(Expr condition, Stmt body, SourceRange range) implements Stmt {
}
