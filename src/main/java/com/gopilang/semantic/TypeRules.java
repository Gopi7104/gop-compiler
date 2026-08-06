package com.gopilang.semantic;

import com.gopilang.ast.BinaryOperator;
import com.gopilang.ast.UnaryOperator;
import com.gopilang.types.PrimitiveType;

import java.util.Optional;

/**
 * The type-compatibility table: pure static functions with zero dependency
 * on {@code Scope}, {@code SemanticModel}, or the AST node hierarchy — only
 * {@code PrimitiveType}/{@code BinaryOperator}/{@code UnaryOperator}.
 * Deliberately separate from {@code SemanticAnalyzer} for independent
 * testability.
 */
public final class TypeRules {

    private TypeRules() {
    }

    /** Whether a value of type {@code from} may be used where {@code to} is expected (equal types, or {@code int} widening to {@code float}). */
    public static boolean isAssignable(PrimitiveType from, PrimitiveType to) {
        if (from == to) {
            return from != PrimitiveType.VOID;
        }
        return from == PrimitiveType.INT && to == PrimitiveType.FLOAT;
    }

    /** Whether a {@code return} value's type is compatible with the function's declared return type. */
    public static boolean isReturnCompatible(PrimitiveType valueType, PrimitiveType declaredReturnType) {
        return isAssignable(valueType, declaredReturnType);
    }

    /** Whether a call argument's type is compatible with the corresponding parameter's declared type. */
    public static boolean isArgumentCompatible(PrimitiveType argumentType, PrimitiveType parameterType) {
        return isAssignable(argumentType, parameterType);
    }

    /** The result type of applying {@code operator} to {@code operand}, or empty if the combination is not legal. */
    public static Optional<PrimitiveType> resultOfUnary(UnaryOperator operator, PrimitiveType operand) {
        return switch (operator) {
            case NEGATE -> isNumeric(operand) ? Optional.of(operand) : Optional.empty();
            case NOT -> operand == PrimitiveType.BOOL ? Optional.of(PrimitiveType.BOOL) : Optional.empty();
        };
    }

    /** The result type of {@code left operator right}, or empty if the combination is not legal. */
    public static Optional<PrimitiveType> resultOfBinary(
            PrimitiveType left, BinaryOperator operator, PrimitiveType right) {
        return switch (operator) {
            case ADD -> resultOfAdd(left, right);
            case SUBTRACT, MULTIPLY, DIVIDE -> resultOfArithmetic(left, right);
            case MODULO -> resultOfModulo(left, right);
            case EQUAL, NOT_EQUAL -> resultOfEquality(left, right);
            case LESS, GREATER, LESS_EQUAL, GREATER_EQUAL -> resultOfComparison(left, right);
            case AND, OR -> resultOfLogical(left, right);
        };
    }

    /** Whether {@code print(...)} accepts a value of this type (every primitive except {@code void}). */
    public static boolean isPrintable(PrimitiveType type) {
        return switch (type) {
            case INT, FLOAT, BOOL, STRING -> true;
            case VOID -> false;
        };
    }

    private static Optional<PrimitiveType> resultOfAdd(PrimitiveType left, PrimitiveType right) {
        if (left == PrimitiveType.STRING && right == PrimitiveType.STRING) {
            return Optional.of(PrimitiveType.STRING);
        }
        return resultOfArithmetic(left, right);
    }

    private static Optional<PrimitiveType> resultOfArithmetic(PrimitiveType left, PrimitiveType right) {
        if (left == PrimitiveType.INT && right == PrimitiveType.INT) {
            return Optional.of(PrimitiveType.INT);
        }
        if (isNumeric(left) && isNumeric(right)) {
            return Optional.of(PrimitiveType.FLOAT);
        }
        return Optional.empty();
    }

    private static Optional<PrimitiveType> resultOfModulo(PrimitiveType left, PrimitiveType right) {
        return (left == PrimitiveType.INT && right == PrimitiveType.INT)
                ? Optional.of(PrimitiveType.INT)
                : Optional.empty();
    }

    private static Optional<PrimitiveType> resultOfComparison(PrimitiveType left, PrimitiveType right) {
        return (isNumeric(left) && isNumeric(right)) ? Optional.of(PrimitiveType.BOOL) : Optional.empty();
    }

    private static Optional<PrimitiveType> resultOfEquality(PrimitiveType left, PrimitiveType right) {
        if (isNumeric(left) && isNumeric(right)) {
            return Optional.of(PrimitiveType.BOOL);
        }
        if (left == right && (left == PrimitiveType.BOOL || left == PrimitiveType.STRING)) {
            return Optional.of(PrimitiveType.BOOL);
        }
        return Optional.empty();
    }

    private static Optional<PrimitiveType> resultOfLogical(PrimitiveType left, PrimitiveType right) {
        return (left == PrimitiveType.BOOL && right == PrimitiveType.BOOL)
                ? Optional.of(PrimitiveType.BOOL)
                : Optional.empty();
    }

    private static boolean isNumeric(PrimitiveType type) {
        return type == PrimitiveType.INT || type == PrimitiveType.FLOAT;
    }
}
