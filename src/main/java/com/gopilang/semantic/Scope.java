package com.gopilang.semantic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One mutable, parent-linked block scope, used only during the single
 * top-to-bottom walk {@code SemanticAnalyzer} performs — not persisted
 * alongside the (immutable) AST. Shadowing a variable from an enclosing
 * scope is a reported error, not silently allowed (Java-style, not
 * C-style).
 */
public final class Scope {

    private final Scope parent;
    private final Map<String, VariableSymbol> symbols = new HashMap<>();

    /** Creates a new scope chained to {@code parent}; {@code null} marks the outermost (function-parameter) scope. */
    public Scope(Scope parent) {
        this.parent = parent;
    }

    /** The enclosing scope, or {@code null} if this is the outermost (function-parameter) scope. */
    public Scope parent() {
        return parent;
    }

    /**
     * Declares {@code symbol} in this scope only. Does not insert on a
     * collision — the original definition stays authoritative, so later
     * references still resolve to it rather than to a rejected duplicate.
     * Returns the pre-existing symbol if {@code symbol.name()} was already
     * declared in this scope, empty otherwise.
     */
    public Optional<VariableSymbol> define(VariableSymbol symbol) {
        VariableSymbol existing = symbols.putIfAbsent(symbol.name(), symbol);
        return Optional.ofNullable(existing);
    }

    /** Resolves {@code name}, walking outward through the whole enclosing chain. */
    public Optional<VariableSymbol> resolve(String name) {
        VariableSymbol local = symbols.get(name);
        if (local != null) {
            return Optional.of(local);
        }
        return parent != null ? parent.resolve(name) : Optional.empty();
    }
}
