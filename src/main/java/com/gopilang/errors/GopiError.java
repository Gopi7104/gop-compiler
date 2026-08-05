package com.gopilang.errors;

import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

/**
 * Internal, phase-tagged control-flow signal used within a single compiler phase
 * (e.g. to unwind out of a partially-scanned token or a broken statement) so that
 * phase can recover and keep going. Never escapes the phase that threw it — the
 * catching code converts it into a {@link Diagnostic} via {@link Diagnostic#from}
 * before it crosses into the rest of the compiler.
 */
public abstract class GopiError extends RuntimeException {

    private final ErrorPhase phase;
    private final SourceRange range;
    private final String suggestion;

    protected GopiError(ErrorPhase phase, SourceRange range, String message, String suggestion) {
        super(message);
        this.phase = phase;
        this.range = range;
        this.suggestion = suggestion;
    }

    protected GopiError(ErrorPhase phase, SourceLocation location, String message, String suggestion) {
        this(phase, SourceRange.point(location), message, suggestion);
    }

    public ErrorPhase phase() {
        return phase;
    }

    public SourceRange range() {
        return range;
    }

    public String suggestion() {
        return suggestion;
    }
}
