package com.gopilang.semantic;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

public record VariableSymbol(String name, PrimitiveType type, SourceRange declaredAt) {
}
