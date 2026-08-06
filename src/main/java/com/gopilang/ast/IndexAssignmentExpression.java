package com.gopilang.ast;

import com.gopilang.util.SourceRange;

/**
 * Assigning to one element of an array, {@code array[index] = value}. Kept
 * separate from {@code AssignmentExpression} (whose target is a bare
 * variable name) rather than generalizing that record's target to an
 * arbitrary lvalue expression — this leaves the existing, well-tested
 * plain-variable assignment path completely untouched.
 */
public record IndexAssignmentExpression(Expr array, Expr index, Expr value, SourceRange range) implements Expr {
}
