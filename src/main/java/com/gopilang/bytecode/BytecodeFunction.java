package com.gopilang.bytecode;

/**
 * Metadata for one compiled function: where its code begins in the shared
 * {@code BytecodeModule.instructions()} list ({@code codeStart}), and how
 * many local-variable slots a call frame must allocate ({@code slotCount},
 * which already includes the {@code parameterCount} leading slots). Holds
 * no instructions of its own — every function's code lives in one flat,
 * program-wide instruction list, addressed by index rather than owned
 * per-function, so {@code CALL} can jump to any function uniformly.
 */
public record BytecodeFunction(String name, int parameterCount, int slotCount, int codeStart) {
}
