package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

/**
 * The root of a parsed source file: just a list of top-level function
 * declarations, with no top-level variables or statements. A valid program
 * must define {@code void main()}, but that is a whole-program semantic
 * question ({@code SemanticAnalyzer.validateMainFunction()}), not something
 * the parser itself enforces.
 */
public record Program(List<FunctionDeclaration> functions, SourceRange range) implements ASTNode {
}
