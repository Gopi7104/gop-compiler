/**
 * The bytecode generation stage: lowers a semantically-valid {@link
 * com.gopilang.ast.Program} into a {@link com.gopilang.bytecode.BytecodeModule}
 * — a deduplicated constant pool, a function table, and one flat,
 * uniformly-sized instruction stream ({@link com.gopilang.bytecode.Opcode}
 * plus a single {@code int} operand) addressed by plain instruction index.
 */
package com.gopilang.bytecode;
