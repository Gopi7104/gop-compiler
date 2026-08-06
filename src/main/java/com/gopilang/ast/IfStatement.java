package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.Optional;

/** An {@code if (condition) thenBranch [else elseBranch]} statement; {@code else} always attaches to the nearest {@code if}. */
public record IfStatement(
        Expr condition,
        Stmt thenBranch,
        Optional<Stmt> elseBranch,
        SourceRange range
) implements Stmt {
}
