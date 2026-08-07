package com.gopilang.selfhost;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.vm.VirtualMachine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BRL v1.0 Phase 5 (DiagnosticBuffer). Same differential harness pattern as
// the other BRL test classes.
class DiagnosticBufferTest {

    private static final List<Path> LIBRARY = List.of(
            Path.of("selfhost/collections/vector_text.gopi"),
            Path.of("selfhost/collections/vector_num.gopi"),
            Path.of("selfhost/text/text_utils.gopi"),
            Path.of("selfhost/text/string_builder.gopi"),
            Path.of("selfhost/diagnostics/diagnostic_buffer.gopi"));

    private static String run(String mainBody) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path source : LIBRARY) {
            combined.append(Files.readString(source)).append('\n');
        }
        combined.append("none main() {\n").append(mainBody).append("\n}\n");
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for:\n" + source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors for:\n" + source);
        BytecodeModule module = new CodeGenerator(program, model).generate();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            new VirtualMachine(module).run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    @Test
    void emptyBufferHasZeroSize() throws IOException {
        assertEquals("0\n", run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                show(diagnosticBufferSize(d));
                """));
    }

    @Test
    void singleDiagnosticIncreasesSize() throws IOException {
        assertEquals("1\n", run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "Semantic Error", 1, 1, "boom", "");
                show(diagnosticBufferSize(d));
                """));
    }

    @Test
    void multipleDiagnosticsAccumulateInOrder() throws IOException {
        assertEquals("3\n", run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "Semantic Error", 1, 1, "a", "");
                diagnosticBufferAppend(d, "Type Error", 2, 2, "b", "");
                diagnosticBufferAppend(d, "Syntax Error", 3, 3, "c", "");
                show(diagnosticBufferSize(d));
                """));
    }

    @Test
    void renderFormatsPhaseMessageLineAndColumn() throws IOException {
        // render() produces "message line" + "position line" + a blank
        // separator line for this one diagnostic; show() then adds its own
        // trailing newline on top of that.
        String expected = "Semantic Error: struct 'Point' has no field 'y'\n"
                + "  --> line 12, column 5\n"
                + "\n"
                + "\n";
        assertEquals(expected, run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "Semantic Error", 12, 5, "struct 'Point' has no field 'y'", "");
                show(diagnosticBufferRender(d));
                """));
    }

    @Test
    void renderIncludesSuggestionWhenPresent() throws IOException {
        String rendered = run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "Type Error", 9, 5, "cannot access field 'y'", "declare it first");
                show(diagnosticBufferRender(d));
                """);
        assertTrue(rendered.contains("Type Error: cannot access field 'y'"));
        assertTrue(rendered.contains("--> line 9, column 5"));
        assertTrue(rendered.contains("Suggestion: declare it first"));
    }

    @Test
    void renderOmitsSuggestionLineWhenAbsent() throws IOException {
        String rendered = run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "Semantic Error", 1, 1, "no suggestion here", "");
                show(diagnosticBufferRender(d));
                """);
        assertFalse(rendered.contains("Suggestion:"));
    }

    @Test
    void renderOfManyDiagnosticsPreservesOrderAndAlignment() throws IOException {
        // Fields must stay aligned across all five parallel vectors even
        // after growth - this pushes past the initial capacity of 4.
        StringBuilder mainBody = new StringBuilder("DiagnosticBuffer d = diagnosticBufferCreate();\n");
        for (int i = 0; i < 10; i++) {
            mainBody.append("diagnosticBufferAppend(d, \"Phase").append(i)
                    .append("\", ").append(i).append(", ").append(i * 2)
                    .append(", \"message").append(i).append("\", \"\");\n");
        }
        mainBody.append("show(diagnosticBufferSize(d));\n");
        mainBody.append("show(diagnosticBufferRender(d));\n");

        String output = run(mainBody.toString());
        String[] lines = output.split("\n", 2);
        assertEquals("10", lines[0]);
        String rendered = lines[1];
        for (int i = 0; i < 10; i++) {
            assertTrue(rendered.contains("Phase" + i + ": message" + i),
                    "expected diagnostic " + i + " in:\n" + rendered);
            assertTrue(rendered.contains("line " + i + ", column " + (i * 2)),
                    "expected position for diagnostic " + i + " in:\n" + rendered);
        }
        // Order must be preserved: diagnostic 0's block appears before diagnostic 9's.
        assertTrue(rendered.indexOf("Phase0:") < rendered.indexOf("Phase9:"));
    }

    @Test
    void appendNeverMisalignsTheParallelVectors() throws IOException {
        // Directly checks every parallel vector's own count stays equal
        // after a mix of appends with and without suggestions.
        assertEquals("3\n3\n3\n3\n3\n", run("""
                DiagnosticBuffer d = diagnosticBufferCreate();
                diagnosticBufferAppend(d, "A", 1, 1, "m1", "");
                diagnosticBufferAppend(d, "B", 2, 2, "m2", "s2");
                diagnosticBufferAppend(d, "C", 3, 3, "m3", "");
                show(d.phases.count);
                show(d.lines.count);
                show(d.columns.count);
                show(d.messages.count);
                show(d.suggestions.count);
                """));
    }
}
