package com.gopilang.bytecode;

import java.util.List;

/**
 * The complete compiled program: the in-memory equivalent of a future
 * {@code .gbc} file. Produced once, wholesale, by
 * {@code CodeGenerator.generate()} — a deduplicated constant pool, one
 * metadata entry per function, one metadata entry per struct (addressed by
 * {@code NEW_STRUCT}'s operand exactly as {@code functions()} is addressed by
 * {@code CALL}'s), and one flat instruction stream shared across every
 * function. All four lists are defensively copied in the compact constructor
 * so the record's immutability holds regardless of what the caller does with
 * its own builder collections afterward.
 */
public record BytecodeModule(
        List<Object> constantPool,
        List<BytecodeFunction> functions,
        List<BytecodeStruct> structs,
        List<Instruction> instructions
) {
    public BytecodeModule {
        constantPool = List.copyOf(constantPool);
        functions = List.copyOf(functions);
        structs = List.copyOf(structs);
        instructions = List.copyOf(instructions);
    }
}
