package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** A {@code while (condition) body} loop. */
public record WhileStatement(Expr condition, Stmt body, SourceRange range) implements Stmt {
}
