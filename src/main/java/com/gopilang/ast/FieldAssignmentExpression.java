package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * Assigning to one field of a struct, {@code target.fieldName = value}. Kept
 * separate from {@code AssignmentExpression} (whose target is a bare
 * variable name) rather than generalizing that record's target to an
 * arbitrary lvalue expression — the exact same reasoning {@code
 * IndexAssignmentExpression} already applies for array elements, applied
 * here for struct fields instead: it leaves the existing, well-tested
 * plain-variable assignment path completely untouched.
 */
public record FieldAssignmentExpression(Expr target, String fieldName, Expr value, SourceRange range)
        implements Expr {
}
