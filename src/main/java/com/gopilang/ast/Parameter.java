package com.gopilang.ast;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

/** One formal parameter of a {@code FunctionDeclaration}, e.g. {@code num x}. */
public record Parameter(TypeRef type, String name, SourceRange range) implements ASTNode {
}
