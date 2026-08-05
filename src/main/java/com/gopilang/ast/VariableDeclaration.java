package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

import java.util.Optional;

public record VariableDeclaration(
        PrimitiveType type,
        String name,
        Optional<Expr> initializer,
        SourceRange range
) implements Stmt {
}
