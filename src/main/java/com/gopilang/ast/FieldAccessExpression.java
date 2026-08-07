package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * Reading one field of a struct, {@code target.fieldName}. {@code target} is
 * a general {@code Expr} (not narrowed to a bare variable) so that
 * {@code point.inner.x}, {@code arr[0].x}, {@code foo().x}, and
 * {@code new Point(...).x} all reuse this same node — the field name is
 * resolved later, against the target's struct type, exactly like {@code
 * FunctionCallExpression.calleeName()} is resolved against a function table.
 */
public record FieldAccessExpression(Expr target, String fieldName, SourceRange range) implements Expr {
}
