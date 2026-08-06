package com.gopilang.semantic;

import com.gopilang.ast.Parameter;
import com.gopilang.util.SourceRange;

import java.util.List;

/** A resolved struct declaration, registered into {@code SemanticModel.structTable()} before any function body is analyzed. */
public record StructSymbol(String name, List<Parameter> fields, SourceRange declaredAt) {
}
