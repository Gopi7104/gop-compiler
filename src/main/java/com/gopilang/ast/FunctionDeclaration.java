package com.gopilang.ast;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

import java.util.List;
import java.util.Optional;

/**
 * A top-level function declaration. Deliberately not part of the {@code Stmt}
 * hierarchy — nested functions are disallowed, so this only ever appears in
 * {@code Program.functions()}.
 *
 * <p>{@code structReturnTypeName} is present when the parser read a struct
 * name as the return type (Milestone S2) instead of a primitive keyword;
 * {@code returnType} is then a placeholder that must never be trusted on its
 * own — semantic analysis rejects every {@code structReturnTypeName}
 * occurrence immediately.
 */
public record FunctionDeclaration(
        TypeRef returnType,
        Optional<String> structReturnTypeName,
        String name,
        List<Parameter> parameters,
        BlockStatement body,
        SourceRange range
) implements ASTNode {
}
