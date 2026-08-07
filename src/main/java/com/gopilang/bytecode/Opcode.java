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
 *
 * <p>Milestone B1 (self-hosting bootstrap) added six zero-operand builtin
 * opcodes ({@code CHAR_CODE_AT}/{@code TEXT_LENGTH}/{@code
 * TEXT_FROM_CHAR_CODE}/{@code READ_FILE}/{@code ARG_COUNT}/{@code ARG_AT}) —
 * the minimum runtime primitives a GopiLang-written compiler needs to
 * inspect {@code text} values character-by-character and reach the
 * filesystem/argv, neither of which any existing opcode combination can do.
 * They use the same "arguments arrive via the operand stack" calling
 * convention {@code CALL} already established, not a new instruction shape.
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

    // Builtins (VM intrinsics) — zero-operand, arguments via the operand
    // stack, same calling convention as CALL
    CHAR_CODE_AT,
    TEXT_LENGTH,
    TEXT_FROM_CHAR_CODE,
    READ_FILE,
    ARG_COUNT,
    ARG_AT,

    // I/O
    PRINT,

    // Stack operations
    DUP,
    POP,

    // Program termination
    HALT
}
