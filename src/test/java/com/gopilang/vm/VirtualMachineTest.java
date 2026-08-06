package com.gopilang.vm;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// First test suite for the `vm` package (none existed before this milestone
// — see CONTRIBUTING.md). Built to verify &&/||'s short-circuit codegen
// actually behaves correctly at runtime, not just that it compiles to the
// expected instruction shape (CodeGeneratorTest covers that half). Every
// expected output here was captured from the real compiler+VM first (see
// the session's manual verification pass), not predicted. Intended as the
// permanent foundation for future end-to-end compiler regression tests, not
// a throwaway suite for this milestone alone.
class VirtualMachineTest {

    // Full pipeline: source -> lexer -> parser -> semantic analysis ->
    // codegen -> VM, capturing stdout exactly as `gopic <file>` would print it.
    private static String run(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for: " + source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors for: " + source);
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

    @Nested
    class LogicalAndShortCircuit {

        @Test
        void leftFalseSkipsRightEntirely() {
            assertEquals("A\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give yes; }
                    none main() { if (a() && b()) { show("inside"); } }
                    """));
        }

        @Test
        void leftTrueEvaluatesRight() {
            assertEquals("A\nB\ninside\n", run("""
                    flag a() { show("A"); give yes; }
                    flag b() { show("B"); give yes; }
                    none main() { if (a() && b()) { show("inside"); } }
                    """));
        }

        @Test
        void leftTrueRightFalseIsFalse() {
            assertEquals("A\nB\n", run("""
                    flag a() { show("A"); give yes; }
                    flag b() { show("B"); give no; }
                    none main() { if (a() && b()) { show("inside"); } }
                    """));
        }
    }

    @Nested
    class LogicalOrShortCircuit {

        @Test
        void leftTrueSkipsRightEntirely() {
            assertEquals("A\ninside\n", run("""
                    flag a() { show("A"); give yes; }
                    flag b() { show("B"); give no; }
                    none main() { if (a() || b()) { show("inside"); } }
                    """));
        }

        @Test
        void leftFalseEvaluatesRight() {
            assertEquals("A\nB\ninside\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give yes; }
                    none main() { if (a() || b()) { show("inside"); } }
                    """));
        }

        @Test
        void leftFalseRightFalseIsFalse() {
            assertEquals("A\nB\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give no; }
                    none main() { if (a() || b()) { show("inside"); } }
                    """));
        }
    }

    @Nested
    class ChainedExpressions {

        @Test
        void chainedAndStopsAtFirstFalse() {
            // a() && b() && c() parses as (a && b) && c — b's false short-circuits
            // the WHOLE expression before c() is ever reached.
            assertEquals("A\nB\n", run("""
                    flag a() { show("A"); give yes; }
                    flag b() { show("B"); give no; }
                    flag c() { show("C"); give yes; }
                    none main() { if (a() && b() && c()) { show("inside"); } }
                    """));
        }

        @Test
        void chainedOrStopsAtFirstTrue() {
            assertEquals("A\nB\ninside\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give yes; }
                    flag c() { show("C"); give no; }
                    none main() { if (a() || b() || c()) { show("inside"); } }
                    """));
        }

        @Test
        void mixedOrThenAndRespectsPrecedence() {
            // a() || b() && c() parses as a || (b && c) — a() is false, so the
            // OR must evaluate its right side, which itself short-circuit
            // evaluates b() && c() as a nested AND.
            assertEquals("A\nB\nC\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give yes; }
                    flag c() { show("C"); give no; }
                    none main() { if (a() || b() && c()) { show("inside"); } }
                    """));
        }

        @Test
        void groupedAndInsideOrShortCircuitsBEntirely() {
            // (a() && b()) || c() — a() is false, so the grouped AND
            // short-circuits WITHOUT ever calling b(); the OR then must
            // evaluate c() since its left (the grouped AND) came out false.
            assertEquals("A\nC\ninside\n", run("""
                    flag a() { show("A"); give no; }
                    flag b() { show("B"); give yes; }
                    flag c() { show("C"); give yes; }
                    none main() { if ((a() && b()) || c()) { show("inside"); } }
                    """));
        }
    }

    @Nested
    class UsageContexts {

        @Test
        void insideLoopCondition() {
            assertEquals("1\n", run("""
                    none main() {
                        run (num j = 0; j < 3; j = j + 1) {
                            if (j > 0 && j < 2) {
                                show(j);
                            }
                        }
                    }
                    """));
        }

        @Test
        void insideVariableAssignment() {
            assertEquals("false\n", run("""
                    none main() {
                        flag x = yes && no;
                        show(x);
                    }
                    """));
        }

        @Test
        void asAFunctionArgument() {
            assertEquals("false\n", run("""
                    flag identity(flag v) { give v; }
                    none main() { show(identity(yes && no)); }
                    """));
        }

        @Test
        void asAReturnExpression() {
            assertEquals("true\nfalse\n", run("""
                    flag f(flag a, flag b) { give a && b; }
                    none main() {
                        show(f(yes, yes));
                        show(f(yes, no));
                    }
                    """));
        }

        @Test
        void resultStoredIntoAnArrayElement() {
            assertEquals("false\ntrue\n", run("""
                    none main() {
                        flag[] results = new flag[2];
                        results[0] = yes && no;
                        results[1] = no || yes;
                        show(results[0]);
                        show(results[1]);
                    }
                    """));
        }

        @Test
        void arrayElementsAsOperands() {
            assertEquals("false\ntrue\n", run("""
                    none main() {
                        flag[] values = new flag[2];
                        values[0] = yes;
                        values[1] = no;
                        show(values[0] && values[1]);
                        show(values[0] || values[1]);
                    }
                    """));
        }

        @Test
        void resultOfOrAssignedThenReused() {
            // Stack-balance check: the OR's result must be a single, correctly
            // typed value usable exactly like any other flag afterward —
            // stored, then read back and branched on.
            assertEquals("yes-branch\n", run("""
                    none main() {
                        flag x = no || yes;
                        if (x) {
                            show("yes-branch");
                        } else {
                            show("no-branch");
                        }
                    }
                    """));
        }
    }
}
