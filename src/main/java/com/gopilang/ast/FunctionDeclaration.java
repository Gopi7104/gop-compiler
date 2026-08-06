package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

import java.util.List;

/**
 * A top-level function declaration. Deliberately not part of the {@code Stmt}
 * hierarchy — nested functions are disallowed, so this only ever appears in
 * {@code Program.functions()}.
 */
public record FunctionDeclaration(
        PrimitiveType returnType,
        String name,
        List<Parameter> parameters,
        BlockStatement body,
        SourceRange range
) implements ASTNode {
}
