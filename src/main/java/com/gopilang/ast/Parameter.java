package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

public record Parameter(PrimitiveType type, String name, SourceRange range) implements ASTNode {
}
