package com.gopilang.errors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates {@link Diagnostic}s for a single compiler phase. Each phase
 * (lexer, parser, semantic analyzer) owns its own instance rather than
 * sharing one — merging diagnostics across phases into one combined view is
 * left to the caller (e.g. the CLI), not this class's job.
 */
public final class DiagnosticReporter {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** Records one diagnostic. */
    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    /** All diagnostics reported so far, in report order. */
    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /** Whether any diagnostic has been reported. */
    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
