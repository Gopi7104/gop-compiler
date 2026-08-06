package com.gopilang.ast;

/**
 * Sealed hierarchy of expression nodes. Consumers (semantic analysis, code
 * generation, printing) dispatch over this with an exhaustive switch rather
 * than a Visitor — Java's pattern-matching switch already guarantees every
 * case is handled.
 */
public sealed interface Expr extends ASTNode
        permits LiteralExpression, VariableExpression, GroupingExpression, UnaryExpression,
                BinaryExpression, AssignmentExpression, FunctionCallExpression {
}
