package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/** An expression evaluated for its side effect, e.g. {@code foo();} or {@code x = 5;} as a bare statement. */
public record ExpressionStatement(Expr expression, SourceRange range) implements Stmt {
}
