package com.gopilang.ast;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

import java.util.Optional;

/**
 * One formal parameter of a {@code FunctionDeclaration}, e.g. {@code num x}.
 * {@code structTypeName} is present when the parser read a struct name in
 * type position (Milestone S2) instead of a primitive keyword; {@code type}
 * is then a placeholder ({@code Parser.STRUCT_TYPE_PLACEHOLDER}) that must
 * never be trusted on its own — semantic analysis rejects every
 * {@code structTypeName} occurrence immediately, so nothing downstream reads
 * the placeholder for a struct-typed parameter.
 */
public record Parameter(TypeRef type, Optional<String> structTypeName, String name, SourceRange range)
        implements ASTNode {
}
