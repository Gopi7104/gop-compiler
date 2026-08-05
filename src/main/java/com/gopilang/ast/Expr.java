package com.gopilang.ast;

public sealed interface Expr extends ASTNode
        permits LiteralExpression, VariableExpression, GroupingExpression, UnaryExpression,
                BinaryExpression, AssignmentExpression, FunctionCallExpression {
}
