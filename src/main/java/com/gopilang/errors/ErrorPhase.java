package com.gopilang.errors;

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

    public String label() {
        return label;
    }
}
