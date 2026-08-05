package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.Optional;

public record ReturnStatement(Optional<Expr> value, SourceRange range) implements Stmt {
}
