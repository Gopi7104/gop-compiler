package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.Optional;

public record IfStatement(
        Expr condition,
        Stmt thenBranch,
        Optional<Stmt> elseBranch,
        SourceRange range
) implements Stmt {
}
