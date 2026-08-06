/**
 * The diagnostic architecture shared by every pipeline stage. Two parallel
 * hierarchies: {@link com.gopilang.errors.GopiError}, an internal,
 * phase-local control-flow signal that never escapes the phase that threw
 * it, and {@link com.gopilang.errors.Diagnostic}, the only thing that
 * crosses phase boundaries. Each phase collects its own diagnostics in a
 * private {@link com.gopilang.errors.DiagnosticReporter}.
 */
package com.gopilang.errors;
