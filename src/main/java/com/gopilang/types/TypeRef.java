package com.gopilang.types;

import java.util.Optional;

/**
 * The type of a declaration or expression: an element type, plus whether it
 * is an array of that element type, plus (as of Milestone S3) an optional
 * struct name. Kept as a thin wrapper around {@code PrimitiveType} rather
 * than folding array-ness or struct-ness into new {@code PrimitiveType}
 * cases, since both are separate dimensions — "array of X"/"struct named X"
 * for every existing X — not new primitives.
 *
 * <p><b>{@code elementType} has no semantic meaning whenever {@code
 * structName} is present.</b> Every semantic consumer (see {@code
 * TypeRules}, {@code SemanticAnalyzer}) checks {@code structName} first and
 * never reads {@code elementType} for a struct type. The placeholder value
 * stored there in that case exists only because {@code CodeGenerator} is
 * frozen and its two existing call sites (deciding {@code ADD} vs {@code
 * CONCAT}; deciding whether to {@code POP} a discarded expression statement)
 * read {@code elementType()} directly, with no way to ask {@code TypeRef}
 * anything else — both remain correct for any placeholder value, since a
 * struct-typed value can never actually be produced without construction or
 * field access, neither of which exists yet.
 */
public record TypeRef(PrimitiveType elementType, boolean isArray, Optional<String> structName) {

    /** Convenience constructor for a primitive (non-struct) type — the shape every call site predating Milestone S3 still uses. */
    public TypeRef(PrimitiveType elementType, boolean isArray) {
        this(elementType, isArray, Optional.empty());
    }

    @Override
    public String toString() {
        String base = structName.orElseGet(elementType::toString);
        return isArray ? base + "[]" : base;
    }

    // For user-facing diagnostics only — see PrimitiveType.displayName().
    public String displayName() {
        String base = structName.orElseGet(elementType::displayName);
        return isArray ? base + "[]" : base;
    }
}
