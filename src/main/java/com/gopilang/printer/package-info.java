/**
 * Read-only rendering over compiler artifacts, for CLI inspection modes:
 * {@link com.gopilang.printer.AstPrinter} renders a parsed program as a
 * tree ({@code --ast}), and {@link com.gopilang.printer.BytecodeDisassembler}
 * renders a compiled module as text ({@code --disassemble}). Neither
 * modifies or re-derives anything — both are pure views over an
 * already-produced result.
 */
package com.gopilang.printer;
