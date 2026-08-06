package com.gopilang.bytecode;

import java.util.List;

public record BytecodeModule(
        List<Object> constantPool,
        List<BytecodeFunction> functions,
        List<Instruction> instructions
) {
    public BytecodeModule {
        constantPool = List.copyOf(constantPool);
        functions = List.copyOf(functions);
        instructions = List.copyOf(instructions);
    }
}
