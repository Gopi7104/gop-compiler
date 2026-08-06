/**
 * The abstract syntax tree produced by the parser: a sealed, immutable
 * hierarchy of record nodes ({@link com.gopilang.ast.ASTNode}, {@link
 * com.gopilang.ast.Stmt}, {@link com.gopilang.ast.Expr}). Frozen for v1 —
 * every consumer (semantic analysis, printing, code generation) dispatches
 * over these sealed types with a plain exhaustive switch rather than a
 * Visitor/{@code accept()} pattern, and the tree is never mutated after
 * parsing.
 */
package com.gopilang.ast;
