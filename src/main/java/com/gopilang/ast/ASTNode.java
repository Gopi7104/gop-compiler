package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * Root of the sealed AST hierarchy. Every node is an immutable record and
 * carries its own source range for diagnostics; the tree is never mutated
 * after parsing (see {@code Stmt}/{@code Expr} for the statement/expression
 * sub-hierarchies dispatched over with plain switches, not a Visitor).
 */
public sealed interface ASTNode permits Program, Parameter, FunctionDeclaration, StructDeclaration, Stmt, Expr {
    SourceRange range();
}
