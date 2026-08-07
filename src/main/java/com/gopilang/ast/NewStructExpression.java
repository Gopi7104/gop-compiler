package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

/**
 * A struct construction expression, {@code new StructName(arguments...)}.
 * The struct name is stored as a raw string, exactly like {@code
 * FunctionCallExpression.calleeName()} — {@code SemanticAnalyzer} resolves it
 * against {@code SemanticModel.structTable()}. Arguments are positional, one
 * per field in declaration order; there is no named-field or literal syntax.
 */
public record NewStructExpression(String structName, List<Expr> arguments, SourceRange range) implements Expr {
}
