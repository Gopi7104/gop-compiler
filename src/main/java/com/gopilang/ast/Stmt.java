package com.gopilang.ast;

/**
 * Sealed hierarchy of statement nodes, dispatched over with an exhaustive
 * switch by each consumer (semantic analysis, code generation, printing)
 * rather than a Visitor.
 */
public sealed interface Stmt extends ASTNode
        permits BlockStatement, VariableDeclaration, IfStatement, WhileStatement,
                ReturnStatement, PrintStatement, ExpressionStatement {
}
