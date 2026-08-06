package com.gopilang.ast;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

import java.util.Optional;

/**
 * A local variable declaration, {@code type name [= initializer];}. With no
 * initializer, the variable is declared but not yet definitely assigned —
 * {@code SemanticAnalyzer} tracks that separately per path and reports a
 * read before assignment as an error.
 *
 * <p>{@code structTypeName} is present when the parser read a struct name in
 * type position (Milestone S2) instead of a primitive keyword; {@code type}
 * is then a placeholder that must never be trusted on its own — semantic
 * analysis rejects every {@code structTypeName} occurrence immediately.
 */
public record VariableDeclaration(
        TypeRef type,
        Optional<String> structTypeName,
        String name,
        Optional<Expr> initializer,
        SourceRange range
) implements Stmt {
}
