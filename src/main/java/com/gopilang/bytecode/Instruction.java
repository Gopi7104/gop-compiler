package com.gopilang.bytecode;

/**
 * One bytecode instruction: an opcode plus a single {@code int} operand.
 * Every instruction is this same fixed size regardless of opcode — unlike
 * JVM-style variable-length bytecode — so jump/call targets are plain
 * instruction indices into {@code BytecodeModule.instructions()}, directly
 * addressable with no decoding pass. Opcodes that take no operand (e.g.
 * {@code ADD}, {@code RETURN}, {@code HALT}) simply carry an unused {@code 0}.
 */
public record Instruction(Opcode opcode, int operand) {
}
