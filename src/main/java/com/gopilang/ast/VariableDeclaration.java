package com.gopilang.ast;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

import java.util.Optional;

/**
 * A local variable declaration, {@code type name [= initializer];}. With no
 * initializer, the variable is declared but not yet definitely assigned —
 * {@code SemanticAnalyzer} tracks that separately per path and reports a
 * read before assignment as an error.
 */
public record VariableDeclaration(
        TypeRef type,
        String name,
        Optional<Expr> initializer,
        SourceRange range
) implements Stmt {
}
