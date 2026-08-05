package com.gopilang.ast;

import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceRange;

import java.util.List;

public record FunctionDeclaration(
        PrimitiveType returnType,
        String name,
        List<Parameter> parameters,
        BlockStatement body,
        SourceRange range
) implements ASTNode {
}
