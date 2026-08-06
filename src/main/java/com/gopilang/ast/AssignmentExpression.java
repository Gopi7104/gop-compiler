package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * An assignment expression, {@code target = value}. The target is stored as
 * the raw identifier name; {@code SemanticAnalyzer} resolves it to a
 * {@code VariableSymbol} in {@code SemanticModel.assignmentTargetResolutions()}.
 */
public record AssignmentExpression(String target, Expr value, SourceRange range) implements Expr {
}
