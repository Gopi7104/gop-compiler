package com.gopilang.semantic;

import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceRange;

/**
 * A resolved variable (or parameter) declaration. Two occurrences of the
 * same name at different declaration sites are distinct symbols — this is
 * why {@code SemanticModel}'s node-keyed resolution maps must use identity
 * semantics rather than {@code VariableSymbol} equality.
 */
public record VariableSymbol(String name, TypeRef type, SourceRange declaredAt) {
}
