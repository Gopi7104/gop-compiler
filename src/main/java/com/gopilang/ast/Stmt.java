package com.gopilang.ast;

public sealed interface Stmt extends ASTNode
        permits BlockStatement, VariableDeclaration, IfStatement, WhileStatement,
                ReturnStatement, PrintStatement, ExpressionStatement {
}
