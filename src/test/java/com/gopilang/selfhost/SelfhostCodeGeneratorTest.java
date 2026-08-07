package com.gopilang.selfhost;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeFunction;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.BytecodeStruct;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.bytecode.Instruction;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.vm.VirtualMachine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Project 4, Phase 5 (self-hosted Code Generator). Same differential
 * harness shape as {@link SelfhostSemanticAnalyzerTest}: both the real Java
 * {@link CodeGenerator} and the self-hosted code generator (compiled and
 * run by the completely unmodified Java pipeline) are observed only through
 * a canonical, flat, one-value-per-line dump of the resulting {@link
 * BytecodeModule} — constant pool, function table, struct table, and the
 * full instruction stream. Opcodes are compared by NAME (matching {@code
 * Opcode.XXX.name()} verbatim on the Java side, and a small self-hosted
 * {@code opcodeName()} lookup on the other), never by raw ordinal, so the
 * two implementations never need to agree on internal numbering — only on
 * observable bytecode shape.
 */
class SelfhostCodeGeneratorTest {

    private static final List<Path> LIBRARY = List.of(
            Path.of("selfhost/collections/vector_num.gopi"),
            Path.of("selfhost/collections/vector_text.gopi"),
            Path.of("selfhost/text/text_utils.gopi"),
            Path.of("selfhost/text/string_builder.gopi"),
            Path.of("selfhost/collections/hash_map_text_to_num.gopi"),
            Path.of("selfhost/diagnostics/diagnostic_buffer.gopi"),
            Path.of("selfhost/lexer/vector_dec.gopi"),
            Path.of("selfhost/lexer/lexer.gopi"),
            Path.of("selfhost/ast/ast_arena.gopi"),
            Path.of("selfhost/parser/parser.gopi"),
            Path.of("selfhost/semantic/type_rules.gopi"),
            Path.of("selfhost/semantic/tables.gopi"),
            Path.of("selfhost/semantic/scope.gopi"),
            Path.of("selfhost/semantic/semantic_analyzer.gopi"),
            Path.of("selfhost/bytecode/opcodes.gopi"),
            Path.of("selfhost/bytecode/code_generator.gopi"));

    private static String escapeForDump(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Java-side canonical dump
    // ------------------------------------------------------------------

    private static void dumpConstant(StringBuilder sb, Object value) {
        if (value instanceof Integer i) {
            sb.append("INT").append('\n').append(i).append('\n');
        } else if (value instanceof Double d) {
            sb.append("FLOAT").append('\n').append(d).append('\n');
        } else if (value instanceof Boolean b) {
            sb.append("BOOL").append('\n').append(b ? 1 : 0).append('\n');
        } else if (value instanceof String s) {
            sb.append("STRING").append('\n').append(escapeForDump(s)).append('\n');
        } else {
            throw new IllegalStateException("unexpected constant type: " + value.getClass());
        }
    }

    private static String javaDump(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        BytecodeModule module = new CodeGenerator(program, model).generate();

        StringBuilder sb = new StringBuilder();
        sb.append(module.constantPool().size()).append('\n');
        for (Object c : module.constantPool()) {
            dumpConstant(sb, c);
        }
        sb.append(module.functions().size()).append('\n');
        for (BytecodeFunction f : module.functions()) {
            sb.append(escapeForDump(f.name())).append('\n');
            sb.append(f.parameterCount()).append('\n');
            sb.append(f.slotCount()).append('\n');
            sb.append(f.codeStart()).append('\n');
        }
        sb.append(module.structs().size()).append('\n');
        for (BytecodeStruct s : module.structs()) {
            sb.append(escapeForDump(s.name())).append('\n');
            sb.append(s.fieldCount()).append('\n');
        }
        sb.append(module.instructions().size()).append('\n');
        for (Instruction instr : module.instructions()) {
            sb.append(instr.opcode().name()).append('\n');
            sb.append(instr.operand()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Self-hosted-side canonical dump
    // ------------------------------------------------------------------

    private static final String DUMP_HELPER = """
            text dumpEscapeText(text s) {
                text result = "";
                num length = textLength(s);
                num i = 0;
                text backslash = textFromCharCode(92);
                loop (i < length) {
                    num code = charCodeAt(s, i);
                    if (code == 10) {
                        result = result + backslash + "n";
                    } else if (code == 9) {
                        result = result + backslash + "t";
                    } else if (code == 13) {
                        result = result + backslash + "r";
                    } else if (code == 92) {
                        result = result + backslash + backslash;
                    } else {
                        result = result + textFromCharCode(code);
                    }
                    i = i + 1;
                }
                give result;
            }

            none dumpModule(BytecodeModuleData m) {
                show(m.constants.kinds.count);
                num i = 0;
                loop (i < m.constants.kinds.count) {
                    num kind = m.constants.kinds.items[i];
                    if (kind == primTypeInt()) {
                        show("INT");
                        show(m.constants.numValues.items[i]);
                    } else if (kind == primTypeFloat()) {
                        show("FLOAT");
                        show(m.constants.decValues.items[i]);
                    } else if (kind == primTypeBool()) {
                        show("BOOL");
                        show(m.constants.numValues.items[i]);
                    } else {
                        show("STRING");
                        show(dumpEscapeText(m.constants.textValues.items[i]));
                    }
                    i = i + 1;
                }

                show(m.functionNames.count);
                num f = 0;
                loop (f < m.functionNames.count) {
                    show(dumpEscapeText(m.functionNames.items[f]));
                    show(m.functionParamCounts.items[f]);
                    show(m.functionSlotCounts.items[f]);
                    show(m.functionCodeStarts.items[f]);
                    f = f + 1;
                }

                show(m.structNames.count);
                num s = 0;
                loop (s < m.structNames.count) {
                    show(dumpEscapeText(m.structNames.items[s]));
                    show(m.structFieldCounts.items[s]);
                    s = s + 1;
                }

                show(m.opcodes.count);
                num k = 0;
                loop (k < m.opcodes.count) {
                    show(opcodeName(m.opcodes.items[k]));
                    show(m.operands.items[k]);
                    k = k + 1;
                }
            }
            """;

    private static final String MAIN = """
            none main() {
                text source = readFile(argAt(0));
                LexerState lstate = lexerCreate(source);
                TokenStream tokens = lexerScanTokens(lstate);
                AstArena arena = arenaCreate();
                DiagnosticBuffer parseDiags = diagnosticBufferCreate();
                ParserState pstate = parserCreate(tokens, arena, parseDiags);
                num programId = parseProgram(pstate);
                DiagnosticBuffer semDiags = diagnosticBufferCreate();
                SemanticTables tab = analyze(arena, programId, semDiags);
                BytecodeModuleData module = generate(arena, programId, tab);
                dumpModule(module);
            }
            """;

    private static String selfhostDump(Path sourceFile) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path lib : LIBRARY) {
            combined.append(Files.readString(lib)).append('\n');
        }
        combined.append(DUMP_HELPER).append('\n').append(MAIN);
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors compiling the self-hosted code-generator bundle itself");
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors compiling the self-hosted code-generator bundle itself");
        BytecodeModule module = new CodeGenerator(program, model).generate();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            new VirtualMachine(module, List.of(sourceFile.toString())).run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    private static void assertDifferential(String source, Path fileWithThatContent) throws IOException {
        String expected = javaDump(source);
        String actual = selfhostDump(fileWithThatContent);
        assertEquals(expected, actual, "self-hosted code generator diverged from Java's CodeGenerator for source:\n" + source);
    }

    @Nested
    class HandWrittenCases {

        @TempDir
        Path tempDir;

        private void check(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            assertDifferential(source, file);
        }

        @Test
        void minimalProgram() throws IOException {
            check("none main() { }");
        }

        @Test
        void arithmeticAllOperators() throws IOException {
            check("none main() { num a = 1 + 2 - 3 * 4 / 2 % 3; show(a); }");
        }

        @Test
        void comparisonsAllOperators() throws IOException {
            check("none main() { flag r = 1 < 2; r = 1 > 2; r = 1 <= 2; r = 1 >= 2; r = 1 == 2; r = 1 != 2; show(r); }");
        }

        @Test
        void stringConcatenationUsesConcatOpcode() throws IOException {
            check("none main() { text s = \"a\" + \"b\" + \"c\"; show(s); }");
        }

        @Test
        void unaryNegateAndNot() throws IOException {
            check("none main() { num x = -5; flag f = !yes; show(x); show(f); }");
        }

        @Test
        void logicalAndShortCircuits() throws IOException {
            check("none main() { flag a = yes; flag b = no; flag r = a && b; show(r); }");
        }

        @Test
        void logicalOrShortCircuits() throws IOException {
            check("none main() { flag a = no; flag b = yes; flag r = a || b; show(r); }");
        }

        @Test
        void nestedShortCircuitAndOr() throws IOException {
            check("none main() { flag a = yes; flag b = no; flag c = yes; flag r = (a && b) || (c && a); show(r); }");
        }

        @Test
        void constantPoolDeduplication() throws IOException {
            check("none main() { num a = 5; num b = 5; num c = 5; show(a + b + c); }");
        }

        @Test
        void constantPoolDistinguishesTypesWithSameLexicalValue() throws IOException {
            check("none main() { num a = 1; flag b = yes; show(a); show(b); }");
        }

        @Test
        void ifWithElse() throws IOException {
            check("none main() { num x = 1; if (x == 1) { show(1); } else { show(2); } }");
        }

        @Test
        void ifWithoutElse() throws IOException {
            check("none main() { num x = 1; if (x == 1) { show(1); } show(2); }");
        }

        @Test
        void nestedIfElseChains() throws IOException {
            check("none main() { num x = 2; if (x == 1) { show(1); } else if (x == 2) { show(2); } else { show(3); } }");
        }

        @Test
        void whileLoop() throws IOException {
            check("none main() { num i = 0; loop (i < 5) { show(i); i = i + 1; } }");
        }

        @Test
        void forLoopDesugaredCorrectly() throws IOException {
            check("none main() { run (num i = 0; i < 5; i = i + 1) { show(i); } }");
        }

        @Test
        void nestedLoopsAndSlotAllocation() throws IOException {
            check("none main() { num i = 0; loop (i < 3) { num j = 0; loop (j < 3) { show(i * 3 + j); j = j + 1; } i = i + 1; } }");
        }

        @Test
        void functionCallsAndReturnValues() throws IOException {
            check("num add(num a, num b) { give a + b; } none main() { show(add(1, add(2, 3))); }");
        }

        @Test
        void recursiveFunctionCall() throws IOException {
            check("num fact(num n) { if (n <= 1) { give 1; } give n * fact(n - 1); } none main() { show(fact(5)); }");
        }

        @Test
        void voidFunctionCallDropsResultWithoutPop() throws IOException {
            check("none noop() { } none main() { noop(); show(1); }");
        }

        @Test
        void arraysCreationAccessAssignmentLength() throws IOException {
            check("none main() { num[] arr = new num[5]; arr[0] = 10; arr[1] = arr[0] + 1; show(arr[1]); show(arr.len()); }");
        }

        @Test
        void newStructConstruction() throws IOException {
            check("struct Point { num x; num y; } none main() { Point p = new Point(1, 2); show(p.x); }");
        }

        @Test
        void fieldAccessAndAssignment() throws IOException {
            check("struct Point { num x; num y; } none main() { Point p = new Point(1, 2); p.x = p.x + p.y; show(p.x); }");
        }

        @Test
        void chainedFieldAccessAndAssignment() throws IOException {
            check("struct Point { num x; num y; } struct Box { Point corner; } "
                    + "none main() { Box b = new Box(new Point(1, 2)); b.corner.x = b.corner.x + 1; show(b.corner.x); }");
        }

        @Test
        void multipleStructsFieldIndexIsPerStruct() throws IOException {
            check("struct A { num x; num y; num z; } struct B { num z; } "
                    + "none main() { A a = new A(1, 2, 3); B b = new B(9); show(a.z); show(b.z); }");
        }

        @Test
        void builtinCharCodeAt() throws IOException {
            check("none main() { text s = \"hi\"; num c = charCodeAt(s, 0); show(c); }");
        }

        @Test
        void builtinTextLength() throws IOException {
            check("none main() { show(textLength(\"hello\")); }");
        }

        @Test
        void builtinTextFromCharCode() throws IOException {
            check("none main() { show(textFromCharCode(65)); }");
        }

        @Test
        void builtinReadFileArgCountArgAt() throws IOException {
            check("none main() { show(argCount()); num c = argCount(); if (c > 0) { show(argAt(0)); } }");
        }

        @Test
        void userFunctionNamedLikeBuiltinNeverCollidesWithOpcodeDispatch() throws IOException {
            // Not a builtin name, so must compile to CALL, not a dedicated opcode.
            check("num myLen(text t) { give textLength(t); } none main() { show(myLen(\"abc\")); }");
        }

        @Test
        void assignmentChainDup() throws IOException {
            check("none main() { num a = 0; num b = 0; num c = 0; a = b = c = 5; show(a + b + c); }");
        }

        @Test
        void arrayIndexAssignmentChaining() throws IOException {
            check("none main() { num[] arr = new num[2]; num[] arr2 = new num[2]; arr[0] = arr2[0] = 7; show(arr[0]); show(arr2[0]); }");
        }

        @Test
        void expressionStatementDiscardsNonVoidValue() throws IOException {
            check("num f() { give 5; } none main() { f(); show(1); }");
        }

        @Test
        void groupingExpressionOverridesPrecedence() throws IOException {
            check("none main() { num x = (1 + 2) * 3; show(x); }");
        }

        @Test
        void deeplyNestedBlocksSlotAllocation() throws IOException {
            check("none main() { num a = 1; { num b = 2; { num c = 3; show(a + b + c); } } }");
        }

        @Test
        void mixedNumAndDecArithmeticWidening() throws IOException {
            check("none main() { num a = 3; dec b = 2.5; dec c = a + b; show(c); }");
        }
    }

    @Nested
    class ExampleCorpus {

        @Test
        void everyExampleMatchesJavaCodeGenerator() throws IOException {
            checkAll(Path.of("examples"));
        }

        private void checkAll(Path directory) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty(), "expected at least one .gopi file in " + directory);
                for (Path file : gopiFiles) {
                    String source = Files.readString(file);
                    assertDifferential(source, file);
                }
            }
        }
    }
}
