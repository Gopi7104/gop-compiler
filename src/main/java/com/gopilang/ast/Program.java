package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

public record Program(List<FunctionDeclaration> functions, SourceRange range) implements ASTNode {
}
