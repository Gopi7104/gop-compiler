package com.gopilang.errors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiagnosticReporter {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
