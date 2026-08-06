package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

/** One formal parameter of a {@code FunctionDeclaration}, e.g. {@code int x}. */
public record Parameter(PrimitiveType type, String name, SourceRange range) implements ASTNode {
}
