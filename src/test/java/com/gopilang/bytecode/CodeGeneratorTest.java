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

    // Mirrors functionInstructions()'s own name-to-index lookup, for
    // NEW_STRUCT's operand (a struct index, not a field count — see
    // NewStructConstruction below).
    private static int structIndexOf(BytecodeModule module, String name) {
        List<BytecodeStruct> structs = module.structs();
        for (int i = 0; i < structs.size(); i++) {
            if (structs.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no struct named '" + name + "' in compiled module");
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

    // Milestone S4, v3: struct construction, "new StructName(...)". NEW_STRUCT
    // encodes a STRUCT INDEX (module.structs(), addressed the same way CALL
    // addresses module.functions()) — not a field count — so the VM can read
    // the field count (and later, richer metadata) back out of that table.
    @Nested
    class NewStructConstruction {

        @Test
        void zeroFieldConstructionCompilesToNewStructAlone() {
            BytecodeModule module = compile("struct Empty { } none main() { Empty e = new Empty(); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(List.of(Opcode.NEW_STRUCT, Opcode.STORE, Opcode.RETURN), opcodesOf(instructions));
            assertEquals(structIndexOf(module, "Empty"), instructions.get(0).operand());
        }

        @Test
        void multiFieldConstructionEvaluatesArgumentsThenEmitsNewStruct() {
            BytecodeModule module = compile(
                    "struct Point { num x; num y; } none main() { Point p = new Point(1, 2); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
            assertEquals(structIndexOf(module, "Point"), instructions.get(2).operand());
        }

        @Test
        void discardedConstructionIsPoppedLikeAnyOtherNonVoidExpressionStatement() {
            BytecodeModule module = compile(
                    "struct Point { num x; num y; } none main() { new Point(1, 2); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.POP, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void evaluationOrderIsLeftToRight() {
            BytecodeModule module = compile(
                    "struct Point { num x; num y; num z; } "
                            + "none main() { Point p = new Point(10, 20, 30); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.NEW_STRUCT,
                            Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
            assertEquals(10, module.constantPool().get(instructions.get(0).operand()));
            assertEquals(20, module.constantPool().get(instructions.get(1).operand()));
            assertEquals(30, module.constantPool().get(instructions.get(2).operand()));
        }

        @Test
        void structIndexMatchesDeclarationOrderNotFieldCount() {
            // B has MORE fields than A, so if NEW_STRUCT's operand were ever
            // accidentally a field count instead of an index, this would
            // catch it: A's operand must be 0 (declared first) even though
            // it has fewer fields than B.
            BytecodeModule module = compile(
                    "struct A { num x; } struct B { num x; num y; num z; } "
                            + "none main() { new A(1); new B(1, 2, 3); }");
            List<Instruction> instructions = functionInstructions(module, "main");
            List<Instruction> newStructInstructions = instructions.stream()
                    .filter(i -> i.opcode() == Opcode.NEW_STRUCT).toList();

            assertEquals(2, newStructInstructions.size());
            assertEquals(structIndexOf(module, "A"), newStructInstructions.get(0).operand());
            assertEquals(structIndexOf(module, "B"), newStructInstructions.get(1).operand());
        }

        @Test
        void nestedConstructionCompilesInnerConstructionFirst() {
            BytecodeModule module = compile(
                    "struct Point { num x; } struct Box { Point corner; } "
                            + "none main() { Box b = new Box(new Point(1)); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.NEW_STRUCT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
            assertEquals(structIndexOf(module, "Point"), instructions.get(1).operand());
            assertEquals(structIndexOf(module, "Box"), instructions.get(2).operand());
        }

        @Test
        void constructionPassedAsAFunctionArgumentEvaluatesBeforeTheCall() {
            BytecodeModule module = compile(
                    "struct Point { num x; } none takes(Point p) { } "
                            + "none main() { takes(new Point(1)); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.CALL, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void arrayCreationBytecodeIsUnaffectedByStructConstruction() {
            // Regression: NEW_ARRAY's own compilation path is untouched.
            BytecodeModule module = compile("none main() { num[] a = new num[5]; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(List.of(Opcode.PUSH_CONST, Opcode.NEW_ARRAY, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }
    }

    // Phase 3, v3 Milestone S5: field access/assignment compile to the
    // existing ARRAY_GET/ARRAY_SET opcodes — no new opcode, no VM change.
    // A struct instance is a plain Object[] at runtime (see NewStructConstruction
    // above), so `point.x` is exactly `point[fieldIndex(x)]` in disguise.
    @Nested
    class FieldAccessExpressions {

        @Test
        void basicReadCompilesToPushConstThenArrayGet() {
            BytecodeModule module = compile(
                    "struct Point { num x; } none main() { Point p = new Point(1); num v = p.x; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.STORE,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.ARRAY_GET, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
            // The PUSH_CONST feeding ARRAY_GET must push field index 0 (x is the only field).
            int fieldIndexConst = instructions.get(4).operand();
            assertEquals(0, module.constantPool().get(fieldIndexConst));
        }

        @Test
        void basicWriteCompilesToPushConstThenValueThenArraySet() {
            BytecodeModule module = compile(
                    "struct Point { num x; } none main() { Point p = new Point(1); p.x = 5; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.STORE,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.ARRAY_SET, Opcode.POP,
                            Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void multiFieldIndexCorrectness() {
            // y is declared second, so p.y must push field index 1, not 0.
            BytecodeModule module = compile(
                    "struct Point { num x; num y; } "
                            + "none main() { Point p = new Point(1, 2); num v = p.y; }");
            List<Instruction> instructions = functionInstructions(module, "main");
            List<Instruction> arrayGets = instructions.stream()
                    .filter(i -> i.opcode() == Opcode.ARRAY_GET).toList();

            assertEquals(1, arrayGets.size());
            int arrayGetIndex = instructions.indexOf(arrayGets.get(0));
            int fieldIndexConst = instructions.get(arrayGetIndex - 1).operand();
            assertEquals(1, module.constantPool().get(fieldIndexConst));
        }

        @Test
        void nestedReadCompilesTargetRecursivelyThenOuterFieldAccess() {
            BytecodeModule module = compile(
                    "struct Point { num x; } struct Box { Point corner; } "
                            + "none main() { Box b = new Box(new Point(1)); num v = b.corner.x; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.NEW_STRUCT, Opcode.STORE,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.ARRAY_GET, Opcode.PUSH_CONST, Opcode.ARRAY_GET,
                            Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void nestedWriteCompilesTargetThenFieldIndexThenValueThenArraySet() {
            BytecodeModule module = compile(
                    "struct Point { num x; } struct Box { Point corner; } "
                            + "none main() { Box b = new Box(new Point(1)); b.corner.x = 9; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.NEW_STRUCT, Opcode.STORE,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.ARRAY_GET, Opcode.PUSH_CONST, Opcode.PUSH_CONST,
                            Opcode.ARRAY_SET, Opcode.POP, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void repeatedFieldAccessesReuseConstantPoolSlot() {
            BytecodeModule module = compile(
                    "struct Point { num x; } "
                            + "none main() { Point p = new Point(1); num a = p.x; num b = p.x; }");
            List<Instruction> instructions = functionInstructions(module, "main");
            List<Instruction> arrayGets = instructions.stream()
                    .filter(i -> i.opcode() == Opcode.ARRAY_GET).toList();
            assertEquals(2, arrayGets.size());

            long zeroCount = module.constantPool().stream()
                    .filter(value -> value instanceof Integer i && i == 0).count();
            assertEquals(1, zeroCount, "both p.x reads should reuse the same field-index 0 constant");
        }

        @Test
        void chainedAssignmentReusesArraySetsPushedBackValue() {
            // ARRAY_SET already pushes the assigned value back, so
            // `a.x = b.x = 5` needs no extra opcode for chaining.
            BytecodeModule module = compile(
                    "struct Point { num x; } "
                            + "none main() { Point a = new Point(0); Point b = new Point(0); a.x = b.x = 5; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            long arraySetCount = instructions.stream().filter(i -> i.opcode() == Opcode.ARRAY_SET).count();
            assertEquals(2, arraySetCount);
            // No DUP anywhere in this chain — ARRAY_SET's own pushed-back value is reused directly.
            assertTrue(instructions.stream().noneMatch(i -> i.opcode() == Opcode.DUP));
        }

        @Test
        void arrayBytecodeUnaffectedByFieldAccess() {
            // Regression: plain array indexing still compiles to exactly ARRAY_GET/ARRAY_SET, unchanged.
            BytecodeModule module = compile(
                    "none main() { num[] a = new num[3]; a[0] = 1; num v = a[0]; }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.NEW_ARRAY, Opcode.STORE,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.ARRAY_SET, Opcode.POP,
                            Opcode.LOAD, Opcode.PUSH_CONST, Opcode.ARRAY_GET, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void structConstructionBytecodeUnaffectedByFieldAccess() {
            // Regression: NEW_STRUCT's own compilation path (covered in
            // NewStructConstruction above) is untouched by field access existing.
            BytecodeModule module = compile("struct Point { num x; num y; } none main() { Point p = new Point(1, 2); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.NEW_STRUCT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }
    }

    // Milestone B1 (self-hosting bootstrap): a builtin call compiles to
    // argument evaluation (identical to an ordinary FunctionCallExpression)
    // followed by its dedicated opcode instead of CALL — see
    // CodeGenerator.BUILTIN_OPCODES.
    @Nested
    class Builtins {

        @Test
        void charCodeAtCompilesArgumentsThenItsOwnOpcode() {
            BytecodeModule module = compile("none main() { num c = charCodeAt(\"abc\", 0); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.CHAR_CODE_AT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void textLengthCompilesToItsOwnOpcode() {
            BytecodeModule module = compile("none main() { num n = textLength(\"abc\"); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.TEXT_LENGTH, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void textFromCharCodeCompilesToItsOwnOpcode() {
            BytecodeModule module = compile("none main() { text t = textFromCharCode(65); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.TEXT_FROM_CHAR_CODE, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void readFileCompilesToItsOwnOpcode() {
            BytecodeModule module = compile("none main() { text t = readFile(\"x.gopi\"); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.READ_FILE, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void argCountCompilesWithNoArgumentsPushed() {
            BytecodeModule module = compile("none main() { num n = argCount(); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(List.of(Opcode.ARG_COUNT, Opcode.STORE, Opcode.RETURN), opcodesOf(instructions));
        }

        @Test
        void argAtCompilesArgumentThenItsOwnOpcode() {
            BytecodeModule module = compile("none main() { text t = argAt(0); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.ARG_AT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void noBuiltinOpcodeCarriesAFunctionIndexOperand() {
            // Builtins are never real functions, so their opcode's operand is
            // always 0 (arguments arrive entirely via the stack, exactly like
            // CALL's own convention) — never mistaken for a CALL-style index.
            BytecodeModule module = compile("none main() { num c = charCodeAt(\"abc\", 0); }");
            List<Instruction> instructions = functionInstructions(module, "main");
            Instruction charCodeAt = instructions.stream()
                    .filter(i -> i.opcode() == Opcode.CHAR_CODE_AT).findFirst().orElseThrow();
            assertEquals(0, charCodeAt.operand());
        }

        @Test
        void argumentsToABuiltinEvaluateLeftToRight() {
            BytecodeModule module = compile(
                    "num a() { show(\"A\"); give 1; } "
                            + "none main() { num c = charCodeAt(\"abc\", a()); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            // "abc" pushed first, then a()'s CALL, then CHAR_CODE_AT consumes both.
            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.CALL, Opcode.CHAR_CODE_AT, Opcode.STORE, Opcode.RETURN),
                    opcodesOf(instructions));
        }

        @Test
        void plainFunctionCallsStillCompileToCall() {
            // Regression: an ordinary user function is completely unaffected
            // by the builtin dispatch table.
            BytecodeModule module = compile("num add(num a, num b) { give a + b; } none main() { show(add(1, 2)); }");
            List<Instruction> instructions = functionInstructions(module, "main");

            assertEquals(
                    List.of(Opcode.PUSH_CONST, Opcode.PUSH_CONST, Opcode.CALL, Opcode.PRINT, Opcode.RETURN),
                    opcodesOf(instructions));
        }
    }
}
