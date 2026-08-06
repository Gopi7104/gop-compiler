package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * A read of a variable by name. Resolution to a {@code VariableSymbol}
 * happens later, in {@code SemanticModel.variableResolutions()} — this node
 * only records the raw identifier as written.
 */
public record VariableExpression(String name, SourceRange range) implements Expr {
}
