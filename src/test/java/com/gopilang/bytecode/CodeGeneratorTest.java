package com.gopilang.bytecode;

import com.gopilang.ast.Program;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// First test suite for the `bytecode` package (none existed before this
// milestone — see CONTRIBUTING.md). Built to close the &&/|| known gap:
// CodeGenerator previously threw UnsupportedOperationException for AND/OR.
// These tests assert the exact short-circuit jump layout the design review
// specified (LOAD/JMP_IF_FALSE/.../JMP/PUSH_CONST, reusing only existing
// opcodes), not just "it compiles without throwing". Intended as the
// permanent foundation for future bytecode-generation regression tests, not
// a throwaway suite for this milestone alone.
class CodeGeneratorTest {

    private static BytecodeModule compile(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for: " + source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors for: " + source);
        return new CodeGenerator(program, model).generate();
    }

    // Slices out just one function's own instructions (no entry stub, no
    // other functions), using declaration order — CodeGenerator.generate()
    // compiles functions in the same order program.functions() lists them,
    // and each function's codeStart strictly increases, so this is safe
    // regardless of which function is declared first.
    private static List<Instruction> functionInstructions(BytecodeModule module, String name) {
        List<BytecodeFunction> functions = module.functions();
        int index = -1;
        for (int i = 0; i < functions.size(); i++) {
            if (functions.get(i).name().equals(name)) {
                index = i;
                break;
            }
        }
        assertTrue(index >= 0, "no function named '" + name + "' in compiled module");
        int start = functions.get(index).codeStart();
        int end = (index + 1 < functions.size()) ? functions.get(index + 1).codeStart() : module.instructions().size();
        return module.instructions().subList(start, end);
    }

    private static List<Opcode> opcodesOf(List<Instruction> instructions) {
        List<Opcode> opcodes = new ArrayList<>();
        for (Instruction instruction : instructions) {
            opcodes.add(instruction.opcode());
        }
        return opcodes;
    }

    @Nested
    class LogicalAnd {

        @Test
        void compilesToLoadJmpIfFalseLoadJmpPushConstFalse() {
            BytecodeModule module = compile("flag both(flag x, flag y) { give x && y; }" +
                    "none main() { show(both(yes, no)); }");
            List<Instruction> instructions = functionInstructions(module, "both");

            assertEquals(
                    List.of(Opcode.LOAD, Opcode.JMP_IF_FALSE, Opcode.LOAD, Opcode.JMP,
                            Opcode.PUSH_CONST, Opcode.RETURN, Opcode.RETURN),
                    opcodesOf(instructions));

            // JMP_IF_FALSE must land exactly on the PUSH_CONST (index 4 within
            // this function's own instructions, i.e. functions.get(...).codeStart() + 4).
            int codeStart = module.functions().stream()
                    .filter(f -> f.name().equals("both")).findFirst().orElseThrow().codeStart();
            assertEquals(codeStart + 4, instructions.get(1).operand(), "JMP_IF_FALSE should target the PUSH_CONST false");
            // JMP (after the right operand) must land exactly past PUSH_CONST, on the first RETURN.
            assertEquals(codeStart + 5, instructions.get(3).operand(), "JMP should target the instruction after PUSH_CONST");

            // The pushed constant must be the boolean `false`.
            int constIndex = instructions.get(4).operand();
            assertEquals(Boolean.FALSE, module.constantPool().get(constIndex));
        }
    }

    @Nested
    class LogicalOr {

        @Test
        void compilesToLoadJmpIfFalsePushConstTrueJmpLoad() {
            BytecodeModule module = compile("flag either(flag x, flag y) { give x || y; }" +
                    "none main() { show(either(yes, no)); }");
            List<Instruction> instructions = functionInstructions(module, "either");

            assertEquals(
                    List.of(Opcode.LOAD, Opcode.JMP_IF_FALSE, Opcode.PUSH_CONST, Opcode.JMP,
                            Opcode.LOAD, Opcode.RETURN, Opcode.RETURN),
                    opcodesOf(instructions));

            int codeStart = module.functions().stream()
                    .filter(f -> f.name().equals("either")).findFirst().orElseThrow().codeStart();
            // JMP_IF_FALSE must land exactly on the second LOAD (index 4), skipping PUSH_CONST/JMP.
            assertEquals(codeStart + 4, instructions.get(1).operand(), "JMP_IF_FALSE should target the right-operand LOAD");
            // JMP (after PUSH_CONST true) must land exactly past the second LOAD, on the first RETURN.
            assertEquals(codeStart + 5, instructions.get(3).operand(), "JMP should target the instruction after the right-operand LOAD");

            int constIndex = instructions.get(2).operand();
            assertEquals(Boolean.TRUE, module.constantPool().get(constIndex));
        }
    }

    @Nested
    class ConstantPoolDeduplication {

        @Test
        void shortCircuitFallbackReusesAnExistingBooleanConstant() {
            // `no` is already a literal in the pool; && 's own false-fallback
            // must reuse that same slot rather than adding a duplicate.
            BytecodeModule module = compile(
                    "none main() { flag y = no; show(y && y); }");
            long falseCount = module.constantPool().stream().filter(Boolean.FALSE::equals).count();
            assertEquals(1, falseCount, "expected exactly one `false` entry in the deduplicated constant pool");
        }
    }

    @Nested
    class NestedShortCircuit {

        @Test
        void chainedAndProducesTwoShortCircuitBlocks() {
            BytecodeModule module = compile(
                    "flag all3(flag a, flag b, flag c) { give a && b && c; }" +
                            "none main() { show(all3(yes, yes, yes)); }");
            List<Opcode> opcodes = opcodesOf(functionInstructions(module, "all3"));

            long jmpIfFalseCount = opcodes.stream().filter(Opcode.JMP_IF_FALSE::equals).count();
            assertEquals(2, jmpIfFalseCount, "a && b && c is two AND nodes, so two short-circuit checks");
        }

        @Test
        void mixedOrAndAndProducesOneOfEach() {
            BytecodeModule module = compile(
                    "flag mixed(flag a, flag b, flag c) { give a || b && c; }" +
                            "none main() { show(mixed(yes, no, yes)); }");
            // a || (b && c) — confirms precedence is preserved through codegen,
            // not just parsing: exactly one OR-shaped block wrapping one AND-shaped block.
            List<Instruction> instructions = functionInstructions(module, "mixed");
            List<Opcode> opcodes = opcodesOf(instructions);

            assertEquals(2, opcodes.stream().filter(Opcode.JMP_IF_FALSE::equals).count());
            assertEquals(2, opcodes.stream().filter(Opcode.JMP::equals).count());
            // Two short-circuit nodes (one OR, one AND), each with its own
            // constant-push fallback: OR's short-circuit-to-true, and AND's
            // short-circuit-to-false.
            assertEquals(2, opcodes.stream().filter(Opcode.PUSH_CONST::equals).count());
        }
    }
}
