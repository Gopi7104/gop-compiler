package com.gopilang.bytecode;

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

    // I/O
    PRINT,

    // Stack operations
    DUP,
    POP,

    // Program termination
    HALT
}
