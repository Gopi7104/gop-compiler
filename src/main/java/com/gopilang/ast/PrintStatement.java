package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** The built-in {@code print(value);} statement. */
public record PrintStatement(Expr value, SourceRange range) implements Stmt {
}
