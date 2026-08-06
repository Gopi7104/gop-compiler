/**
 * The semantic analysis stage: resolves identifiers and calls, checks
 * types, verifies reachability and definite assignment, and validates the
 * program's entry point, all without ever mutating the AST. Its output,
 * {@link com.gopilang.semantic.SemanticModel}, is a separate immutable
 * model consumed by code generation.
 */
package com.gopilang.semantic;
