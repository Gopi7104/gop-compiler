package com.gopilang.semantic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class Scope {

    private final Scope parent;
    private final Map<String, VariableSymbol> symbols = new HashMap<>();

    // parent == null marks the outermost (function-parameter) scope.
    public Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope parent() {
        return parent;
    }

    // Local-scope-only. Does not insert on a collision — the original
    // definition stays authoritative, so later references still resolve to
    // it rather than to a rejected duplicate.
    public Optional<VariableSymbol> define(VariableSymbol symbol) {
        VariableSymbol existing = symbols.putIfAbsent(symbol.name(), symbol);
        return Optional.ofNullable(existing);
    }

    // Walks outward through the whole enclosing chain.
    public Optional<VariableSymbol> resolve(String name) {
        VariableSymbol local = symbols.get(name);
        if (local != null) {
            return Optional.of(local);
        }
        return parent != null ? parent.resolve(name) : Optional.empty();
    }
}
