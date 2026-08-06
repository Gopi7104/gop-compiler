package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

/**
 * A call {@code calleeName(arguments...)}. The callee is stored as a raw
 * name; {@code SemanticAnalyzer} resolves it to a {@code FunctionSymbol} in
 * {@code SemanticModel.callResolutions()}.
 */
public record FunctionCallExpression(String calleeName, List<Expr> arguments, SourceRange range) implements Expr {
}
