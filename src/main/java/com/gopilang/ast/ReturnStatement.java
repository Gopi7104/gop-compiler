package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.Optional;

/** A {@code return;} or {@code return value;} statement. */
public record ReturnStatement(Optional<Expr> value, SourceRange range) implements Stmt {
}
