package com.gopilang.bytecode;

/**
 * The GopiLang VM's instruction set: a deliberately small, uniform set of
 * stack-machine opcodes (constants, locals, arithmetic, comparison, control
 * flow, function calls, arrays, stack manipulation, and termination) — no
 * per-type variants, no object model, unlike the much larger JVM instruction
 * set. Array values are plain {@code Object[]} at runtime; the array opcodes
 * ({@code NEW_ARRAY}/{@code ARRAY_GET}/{@code ARRAY_SET}/{@code
 * ARRAY_LENGTH}) operate on that reference directly. A struct instance is
 * also a plain {@code Object[]} (one slot per field, declaration order) —
 * {@code NEW_STRUCT} is the only struct-specific opcode; there is no
 * dedicated runtime struct type, matching arrays exactly.
 */
public enum Opcode {

    // Constants
    PUSH_CONST,

    // Locals
    LOAD,
    STORE,

    // Arithmetic
    ADD,
    SUB,
    MUL,
    DIV,
    MOD,
    CONCAT,
    NEG,
    NOT,

    // Comparison
    CMP_EQ,
    CMP_NE,
    CMP_LT,
    CMP_GT,
    CMP_LE,
    CMP_GE,

    // Control flow
    JMP,
    JMP_IF_FALSE,

    // Functions
    CALL,
    RETURN,

    // Arrays
    NEW_ARRAY,
    ARRAY_GET,
    ARRAY_SET,
    ARRAY_LENGTH,

    // Structs
    NEW_STRUCT,

    // I/O
    PRINT,

    // Stack operations
    DUP,
    POP,

    // Program termination
    HALT
}
