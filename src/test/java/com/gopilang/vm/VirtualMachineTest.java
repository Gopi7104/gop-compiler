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

    // Milestone S4, v3: struct construction, "new StructName(...)". Structs
    // still can't be printed and have no field access, so correctness here is
    // observed indirectly: the program must not crash, execution must reach
    // code positioned after a construction, and evaluation order must be
    // exactly left-to-right — every check reuses the same "print a marker"
    // technique the short-circuit tests above already rely on.
    @Nested
    class NewStructConstruction {

        @Test
        void constructionPassedThroughAFunctionCallRunsToCompletion() {
            assertEquals("took it\n", run("""
                    struct Point { num x; num y; }
                    none takes(Point p) { show("took it"); }
                    none main() { takes(new Point(1, 2)); }
                    """));
        }

        @Test
        void discardedConstructionLeavesTheStackBalanced() {
            // If NEW_STRUCT's POP-after-statement bookkeeping ever left an
            // extra value behind, these two subsequent, unrelated shows would
            // read the wrong operand-stack slot instead of their own literal.
            assertEquals("1\n2\n", run("""
                    struct Point { num x; num y; }
                    none main() {
                        new Point(1, 2);
                        show(1);
                        show(2);
                    }
                    """));
        }

        @Test
        void evaluationOrderIsLeftToRightAtRuntime() {
            assertEquals("A\nB\ndone\n", run("""
                    struct Point { num x; num y; }
                    num a() { show("A"); give 1; }
                    num b() { show("B"); give 2; }
                    none main() {
                        Point p = new Point(a(), b());
                        show("done");
                    }
                    """));
        }

        @Test
        void zeroFieldStructConstructionRunsToCompletion() {
            assertEquals("ok\n", run("""
                    struct Empty { }
                    none main() {
                        Empty e = new Empty();
                        show("ok");
                    }
                    """));
        }

        @Test
        void nestedConstructionRunsToCompletion() {
            assertEquals("nested ok\n", run("""
                    struct Point { num x; }
                    struct Box { Point corner; }
                    none main() {
                        Box b = new Box(new Point(1));
                        show("nested ok");
                    }
                    """));
        }

        @Test
        void differentStructsAllocateTheirOwnCorrectFieldCount() {
            // Indirect check that NEW_STRUCT's struct-index operand reads the
            // right BytecodeStruct back out — A and B have different field
            // counts, so a mixed-up index would pop the wrong number of
            // values off the operand stack and corrupt everything after it.
            assertEquals("both ok\n", run("""
                    struct A { num x; }
                    struct B { num x; num y; num z; }
                    none main() {
                        A a = new A(1);
                        B b = new B(1, 2, 3);
                        show("both ok");
                    }
                    """));
        }

        @Test
        void coexistsWithArraysInTheSameProgram() {
            // Regression: struct construction and the existing array opcodes
            // operate correctly side by side.
            assertEquals("3\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point p = new Point(5);
                        num[] arr = new num[2];
                        arr[0] = 1;
                        arr[1] = 2;
                        show(arr[0] + arr[1]);
                    }
                    """));
        }
    }

    // Phase 3, v3 Milestone S5: field access/assignment, end-to-end. VirtualMachine.java
    // itself is unchanged — these tests confirm ARRAY_GET/ARRAY_SET reused for
    // field access actually produce correct values at runtime, not just the
    // right instruction shape (CodeGeneratorTest covers that half).
    @Nested
    class FieldAccessExpressions {

        @Test
        void constructThenReadReturnsTheConstructorArgument() {
            assertEquals("5\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point p = new Point(5);
                        show(p.x);
                    }
                    """));
        }

        @Test
        void constructThenWriteThenReadObservesTheNewValue() {
            assertEquals("42\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point p = new Point(5);
                        p.x = 42;
                        show(p.x);
                    }
                    """));
        }

        @Test
        void nestedReadReturnsTheInnerStructsField() {
            assertEquals("7\n", run("""
                    struct Point { num x; }
                    struct Box { Point corner; }
                    none main() {
                        Box b = new Box(new Point(7));
                        show(b.corner.x);
                    }
                    """));
        }

        @Test
        void nestedWriteMutatesTheInnerStructInPlace() {
            assertEquals("99\n", run("""
                    struct Point { num x; }
                    struct Box { Point corner; }
                    none main() {
                        Box b = new Box(new Point(7));
                        b.corner.x = 99;
                        show(b.corner.x);
                    }
                    """));
        }

        @Test
        void aliasingTwoVariablesSharingAStructSeeEachOthersWrites() {
            // A struct is a plain Object[] reference at runtime (like arrays) —
            // no copy-on-assign, so `other` and `p` are the same instance.
            assertEquals("10\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point p = new Point(1);
                        Point other = p;
                        other.x = 10;
                        show(p.x);
                    }
                    """));
        }

        @Test
        void fieldContainingAnArrayReadsAndWritesThroughTheField() {
            assertEquals("3\n", run("""
                    struct Holder { num[] items; }
                    none main() {
                        Holder h = new Holder(new num[2]);
                        h.items[0] = 1;
                        h.items[1] = 2;
                        show(h.items[0] + h.items[1]);
                    }
                    """));
        }

        @Test
        void chainedAssignmentSetsBothTargetsToTheSameValue() {
            assertEquals("5\n5\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point a = new Point(0);
                        Point b = new Point(0);
                        a.x = b.x = 5;
                        show(a.x);
                        show(b.x);
                    }
                    """));
        }

        @Test
        void structsAndArraysCoexistCorrectly() {
            // Arrays of structs still can't be constructed (a deliberate,
            // tracked gap — see CLAUDE.md), so this exercises struct field
            // access and plain arrays living side by side in one program.
            assertEquals("12\n", run("""
                    struct Point { num x; }
                    none main() {
                        Point a = new Point(5);
                        Point b = new Point(7);
                        num[] sums = new num[1];
                        sums[0] = a.x + b.x;
                        show(sums[0]);
                    }
                    """));
        }

        @Test
        void regressionPlainArrayReadsAndWritesStillWork() {
            assertEquals("6\n", run("""
                    none main() {
                        num[] arr = new num[3];
                        arr[0] = 1;
                        arr[1] = 2;
                        arr[2] = 3;
                        show(arr[0] + arr[1] + arr[2]);
                    }
                    """));
        }

        @Test
        void regressionOrdinaryFunctionCallsStillWork() {
            assertEquals("3\n", run("""
                    num add(num a, num b) { give a + b; }
                    none main() { show(add(1, 2)); }
                    """));
        }
    }
}
