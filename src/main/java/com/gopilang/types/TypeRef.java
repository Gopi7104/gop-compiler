package com.gopilang.types;

/**
 * The type of a declaration or expression: an element type, plus whether it
 * is an array of that element type. Kept as a thin wrapper around
 * {@link PrimitiveType} rather than folding array-ness into a new
 * {@code PrimitiveType} case, since arrays are a separate dimension —
 * "array of X" for every existing X — not a new primitive.
 */
public record TypeRef(PrimitiveType elementType, boolean isArray) {

    @Override
    public String toString() {
        return isArray ? elementType + "[]" : elementType.toString();
    }

    // For user-facing diagnostics only — see PrimitiveType.displayName().
    public String displayName() {
        return isArray ? elementType.displayName() + "[]" : elementType.displayName();
    }
}
