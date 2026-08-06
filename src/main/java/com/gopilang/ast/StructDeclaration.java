package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

/**
 * A top-level struct declaration, {@code struct Name { field* }}. Deliberately
 * not part of the {@code Stmt} hierarchy — like {@code FunctionDeclaration},
 * it only ever appears in {@code Program.structs()}, never nested inside a
 * function body. Each field is a plain {@code Parameter} (type + name): a
 * struct field and a function parameter are the exact same shape, so no
 * separate "Field" record is introduced.
 */
public record StructDeclaration(String name, List<Parameter> fields, SourceRange range) implements ASTNode {
}
