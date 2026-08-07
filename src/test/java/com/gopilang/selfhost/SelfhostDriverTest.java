package com.gopilang.selfhost;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.lexer.Lexer;
import com.gopilang.lexer.Token;
import com.gopilang.parser.Parser;
import com.gopilang.printer.AstPrinter;
import com.gopilang.printer.BytecodeDisassembler;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project 2, Phase 7 (self-hosted Compiler Driver). Same differential
 * harness shape as every prior self-hosted phase, but exercising the whole
 * pipeline through the driver's own CLI-mode dispatch rather than calling
 * each phase directly. Java's reference output for {@code --tokens}/
 * {@code --ast}/{@code --disassemble} is reproduced here by calling the
 * exact same classes {@code GopiC.java} itself calls ({@code AstPrinter},
 * {@code BytecodeDisassembler}, plain token iteration) - deliberately NOT
 * by invoking {@code GopiC.main()} directly, since several of its code
 * paths call {@code System.exit(...)} on a diagnostic, which would
 * terminate the test JVM itself.
 *
 * <p>Diagnostic-bearing cases are NOT byte-compared against Java's real CLI
 * output: {@code Diagnostic.render()} draws a source-line-and-caret window
 * that {@code DiagnosticBuffer} (frozen BRL, from Phase 1) never captured
 * the data for - already documented and accepted as "a close, readable
 * approximation" there, and inherited unchanged by this phase's CLI layer.
 * Those cases are instead asserted by content (phase, message, line,
 * column, count, and ordering across phases) via the already-exhaustive
 * per-phase differential suites; this file only additionally confirms the
 * driver's own sequencing (lexical/syntax stop the pipeline before
 * semantic; semantic stops it before codegen) and mode dispatch behave
 * correctly.
 */
class SelfhostDriverTest {

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
            Path.of("selfhost/bytecode/code_generator.gopi"),
            Path.of("selfhost/driver/text_format.gopi"),
            Path.of("selfhost/driver/ast_printer.gopi"),
            Path.of("selfhost/driver/disassembler.gopi"),
            Path.of("selfhost/driver/gopic.gopi"));

    // ------------------------------------------------------------------
    // Java-side reference output - reproduces GopiC.java's own private
    // printTokens()/printAst()/disassemble() logic exactly, without ever
    // risking a System.exit(...) call.
    // ------------------------------------------------------------------

    private static String javaTokensDump(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scanTokens();
        StringBuilder sb = new StringBuilder();
        for (Token token : tokens) {
            sb.append(String.format("%-16s lexeme=%-14s literal=%-12s %s%n",
                    token.type(),
                    "'" + token.lexeme() + "'",
                    token.literal() == null ? "-" : token.literal(),
                    token.location()));
        }
        return sb.toString();
    }

    private static String javaAstDump(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        return AstPrinter.print(program);
    }

    private static String javaDisassembleDump(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        BytecodeModule module = new CodeGenerator(program, model).generate();
        return BytecodeDisassembler.disassemble(module);
    }

    // ------------------------------------------------------------------
    // Self-hosted-side: compiles LIBRARY+gopic.gopi (which already defines
    // its own main() - no extra test-only MAIN needed, unlike every prior
    // phase's test), then runs it with the given CLI args, exactly the way
    // a real `gopic <mode> <file>` invocation would look from the outside.
    // ------------------------------------------------------------------

    private static String selfhostRun(List<String> cliArgs) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path lib : LIBRARY) {
            combined.append(Files.readString(lib)).append('\n');
        }
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors compiling the self-hosted driver bundle itself");
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors compiling the self-hosted driver bundle itself");
        BytecodeModule module = new CodeGenerator(program, model).generate();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            new VirtualMachine(module, cliArgs).run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    @Nested
    class TokensMode {

        @TempDir
        Path tempDir;

        private void check(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            String expected = javaTokensDump(source);
            String actual = selfhostRun(List.of("--tokens", file.toString()));
            assertEquals(expected, actual, "self-hosted --tokens diverged for source:\n" + source);
        }

        @Test
        void allLiteralKinds() throws IOException {
            check("num a = 1; dec b = 2.5; flag c = yes; text d = \"hi\";");
        }

        @Test
        void allOperatorsAndPunctuation() throws IOException {
            check("+ - * / % = == != < > <= >= && || ! ; , . ( ) { } [ ]");
        }

        @Test
        void multilineWithComments() throws IOException {
            check("// comment\nnum x = 1;\n// another\nshow(x);");
        }

        @Test
        void everyExample() throws IOException {
            checkAll(Path.of("examples"));
        }

        private void checkAll(Path directory) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty());
                for (Path file : gopiFiles) {
                    check(Files.readString(file));
                }
            }
        }
    }

    @Nested
    class AstMode {

        @TempDir
        Path tempDir;

        private void check(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            String expected = javaAstDump(source);
            String actual = selfhostRun(List.of("--ast", file.toString()));
            assertEquals(expected, actual, "self-hosted --ast diverged for source:\n" + source);
        }

        @Test
        void structsAndFunctions() throws IOException {
            check("struct Point { num x; num y; } num add(num a, num b) { give a + b; } none main() { }");
        }

        @Test
        void everyStatementKind() throws IOException {
            check("""
                    none main() {
                        num x = 1;
                        if (x == 1) { show(1); } else { show(2); }
                        loop (x < 5) { x = x + 1; }
                        show(x);
                    }
                    """);
        }

        @Test
        void everyExpressionKind() throws IOException {
            check("""
                    struct Point { num x; }
                    none main() {
                        num[] arr = new num[3];
                        arr[0] = 1;
                        Point p = new Point(1);
                        p.x = p.x + 1;
                        num len = arr.len();
                        flag r = !(1 < 2) && yes;
                        show(len);
                        show(r);
                        show((1 + 2));
                        show(-1);
                    }
                    """);
        }

        @Test
        void ifWithoutElseAndReturnWithoutValue() throws IOException {
            check("none f() { if (yes) { give; } } none main() { f(); }");
        }

        @Test
        void everyExample() throws IOException {
            checkAll(Path.of("examples"));
        }

        private void checkAll(Path directory) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty());
                for (Path file : gopiFiles) {
                    check(Files.readString(file));
                }
            }
        }
    }

    @Nested
    class DisassembleMode {

        @TempDir
        Path tempDir;

        private void check(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            String expected = javaDisassembleDump(source);
            String actual = selfhostRun(List.of("--disassemble", file.toString()));
            assertEquals(expected, actual, "self-hosted --disassemble diverged for source:\n" + source);
        }

        @Test
        void comprehensiveProgram() throws IOException {
            check("""
                    struct Point { num x; num y; }
                    num add(num a, num b) { give a + b; }
                    none main() {
                        num x = 1;
                        dec y = 2.5;
                        text s = "a" + "b";
                        flag f = x < 2 && y > 1.0;
                        if (f) { show(add(x, 3)); } else { show(0); }
                        loop (x < 3) { show(x); x = x + 1; }
                        num[] arr = new num[3];
                        arr[0] = 5;
                        show(arr[0]);
                        Point p = new Point(1, 2);
                        p.x = p.x + 1;
                        show(p.x);
                    }
                    """);
        }

        @Test
        void constantPoolDeduplication() throws IOException {
            check("none main() { num a = 5; num b = 5; show(a + b); }");
        }

        @Test
        void everyExample() throws IOException {
            checkAll(Path.of("examples"));
        }

        private void checkAll(Path directory) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty());
                for (Path file : gopiFiles) {
                    check(Files.readString(file));
                }
            }
        }
    }

    @Nested
    class DefaultMode {

        @TempDir
        Path tempDir;

        @Test
        void reportsCompiledSuccessfullyWithCorrectCounts() throws IOException {
            String source = "struct Point { num x; } num f() { give 1; } none main() { show(f()); }";
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);

            Parser parser = new Parser(new Lexer(source).scanTokens());
            Program program = parser.parseProgram();
            SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
            SemanticModel model = analyzer.analyze();
            BytecodeModule module = new CodeGenerator(program, model).generate();

            String actual = selfhostRun(List.of(file.toString()));
            String expected = "Compiled successfully: " + module.functions().size() + " function(s), "
                    + module.structs().size() + " struct(s), " + module.instructions().size() + " instruction(s).\n";
            assertEquals(expected, actual);
        }

        @Test
        void emptyProgramNoFlagsGivesUsage() throws IOException {
            String actual = selfhostRun(List.of());
            assertTrue(actual.contains("Usage: gopic"), "expected usage message, got:\n" + actual);
        }

        @Test
        void unrecognizedFlagGivesUsage() throws IOException {
            String actual = selfhostRun(List.of("--bogus"));
            assertTrue(actual.contains("Usage: gopic"), "expected usage message, got:\n" + actual);
        }
    }

    @Nested
    class DiagnosticSequencing {

        @TempDir
        Path tempDir;

        private String run(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            return selfhostRun(List.of(file.toString()));
        }

        @Test
        void syntaxErrorStopsBeforeSemanticAnalysis() throws IOException {
            String actual = run("none main() { num x = 1 }");
            assertTrue(actual.contains("syntax error(s):"), "expected a syntax-error section, got:\n" + actual);
            assertFalse(actual.contains("semantic error"), "semantic analysis must not run after a syntax error:\n" + actual);
        }

        @Test
        void semanticErrorStopsBeforeCodeGeneration() throws IOException {
            String actual = run("none main() { show(undefinedVar); }");
            assertTrue(actual.contains("1 semantic error(s):"), "expected exactly one semantic-error section, got:\n" + actual);
            assertTrue(actual.contains("undefined variable 'undefinedVar'"), "expected the specific diagnostic message, got:\n" + actual);
            assertFalse(actual.contains("Compiled successfully"), "must not report success after a semantic error:\n" + actual);
        }

        @Test
        void multipleSemanticErrorsAllReportedInOrder() throws IOException {
            String actual = run("none main() { num x; show(x); show(y); }");
            int firstIndex = actual.indexOf("variable 'x' might not have been assigned a value");
            int secondIndex = actual.indexOf("undefined variable 'y'");
            assertTrue(firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex,
                    "expected both diagnostics in source order, got:\n" + actual);
            assertTrue(actual.contains("2 semantic error(s):"));
        }

        @Test
        void nonGopiExtensionRejected() throws IOException {
            Path file = tempDir.resolve("case.txt");
            Files.writeString(file, "none main() { }");
            String actual = selfhostRun(List.of(file.toString()));
            assertTrue(actual.contains("Error: expected a .gopi source file"), "got:\n" + actual);
        }

        @Test
        void blankFileRejected() throws IOException {
            Path file = tempDir.resolve("blank.gopi");
            Files.writeString(file, "   \n\t\n  ");
            String actual = selfhostRun(List.of(file.toString()));
            assertTrue(actual.contains("Error: file is empty"), "got:\n" + actual);
        }
    }

    @Nested
    class ExampleCorpusEndToEnd {

        @Test
        void everyExampleCompilesSuccessfullyThroughDefaultMode() throws IOException {
            try (Stream<Path> files = Files.list(Path.of("examples"))) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty());
                for (Path file : gopiFiles) {
                    String actual = selfhostRun(List.of(file.toString()));
                    assertTrue(actual.startsWith("Compiled successfully:"),
                            "expected " + file + " to compile successfully, got:\n" + actual);
                }
            }
        }
    }
}
