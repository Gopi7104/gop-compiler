package com.gopilang.semantic;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

import java.util.List;

public record FunctionSymbol(
        String name,
        PrimitiveType returnType,
        List<PrimitiveType> parameterTypes,
        SourceRange declaredAt
) {
}
