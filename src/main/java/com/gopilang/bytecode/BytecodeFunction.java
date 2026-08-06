package com.gopilang.bytecode;

public record BytecodeFunction(String name, int parameterCount, int slotCount, int codeStart) {
}
