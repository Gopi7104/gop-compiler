package com.gopilang.printer;

import com.gopilang.bytecode.BytecodeFunction;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.BytecodeStruct;
import com.gopilang.bytecode.Instruction;
import com.gopilang.bytecode.Opcode;

/**
 * Renders a compiled {@link BytecodeModule} as human-readable text, for the
 * {@code --disassemble} CLI mode: the constant pool, the function table,
 * and the flat instruction stream with resolved (already-backpatched) jump
 * targets. Purely a read-only view over an existing module — never
 * regenerates or re-executes anything.
 */
public final class BytecodeDisassembler {

    private BytecodeDisassembler() {
    }

    /** Renders {@code module}'s constant pool, function table, struct table, and instruction listing. */
    public static String disassemble(BytecodeModule module) {
        StringBuilder out = new StringBuilder();
        printConstantPool(module, out);
        out.append('\n');
        printFunctions(module, out);
        out.append('\n');
        printStructs(module, out);
        out.append('\n');
        printInstructions(module, out);
        return out.toString();
    }

    private static void printConstantPool(BytecodeModule module, StringBuilder out) {
        out.append("Constant Pool\n");
        out.append("-------------\n");
        for (int i = 0; i < module.constantPool().size(); i++) {
            out.append(i).append(": ").append(renderConstant(module.constantPool().get(i))).append('\n');
        }
    }

    private static String renderConstant(Object constant) {
        return constant instanceof String s ? "\"" + s + "\"" : String.valueOf(constant);
    }

    private static void printFunctions(BytecodeModule module, StringBuilder out) {
        out.append("Functions\n");
        out.append("---------\n");
        for (int i = 0; i < module.functions().size(); i++) {
            BytecodeFunction function = module.functions().get(i);
            out.append(i).append(' ').append(function.name()).append('\n');
            out.append("  params:").append(function.parameterCount()).append('\n');
            out.append("  slots:").append(function.slotCount()).append('\n');
            out.append("  codeStart:").append(function.codeStart()).append('\n');
            out.append('\n');
        }
    }

    private static void printStructs(BytecodeModule module, StringBuilder out) {
        out.append("Structs\n");
        out.append("-------\n");
        for (int i = 0; i < module.structs().size(); i++) {
            BytecodeStruct struct = module.structs().get(i);
            out.append(i).append(' ').append(struct.name()).append('\n');
            out.append("  fields:").append(struct.fieldCount()).append('\n');
            out.append('\n');
        }
    }

    private static void printInstructions(BytecodeModule module, StringBuilder out) {
        out.append("Instructions\n");
        out.append("------------\n");
        for (int i = 0; i < module.instructions().size(); i++) {
            Instruction instruction = module.instructions().get(i);
            out.append(String.format("%04d %s", i, instruction.opcode()));
            if (hasOperand(instruction.opcode())) {
                out.append(' ').append(instruction.operand());
            }
            out.append('\n');
        }
    }

    private static boolean hasOperand(Opcode opcode) {
        return switch (opcode) {
            case PUSH_CONST, LOAD, STORE, CALL, JMP, JMP_IF_FALSE, NEW_STRUCT -> true;
            default -> false;
        };
    }
}
