package com.gopilang.semantic;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

import java.util.List;

/** A resolved function signature, registered into {@code SemanticModel.functionTable()} before any body is analyzed. */
public record FunctionSymbol(
        String name,
        PrimitiveType returnType,
        List<PrimitiveType> parameterTypes,
        SourceRange declaredAt
) {
}
