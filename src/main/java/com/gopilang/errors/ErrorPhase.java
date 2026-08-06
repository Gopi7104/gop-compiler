package com.gopilang.errors;

/**
 * Which compiler phase a {@link Diagnostic} came from. {@code TYPE} (type
 * mismatches) is kept distinct from {@code SEMANTIC} (resolution failures,
 * duplicate/shadowed declarations) even though both come from
 * {@code SemanticAnalyzer} — they are different categories of problem.
 */
public enum ErrorPhase {
    LEXICAL("Lexical Error"),
    SYNTAX("Syntax Error"),
    SEMANTIC("Semantic Error"),
    TYPE("Type Error"),
    RUNTIME("Runtime Error");

    private final String label;

    ErrorPhase(String label) {
        this.label = label;
    }

    /** The human-readable label shown at the start of a rendered diagnostic. */
    public String label() {
        return label;
    }
}
