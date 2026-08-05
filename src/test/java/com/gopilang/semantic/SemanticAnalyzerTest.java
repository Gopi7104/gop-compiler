package com.gopilang.semantic;

import com.gopilang.ast.Program;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.ErrorPhase;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Categorized per Milestone 4's completion requirements. Every assertion in
// this file was verified against the real analyzer before being encoded here
// (see the session's manual verification passes) rather than predicted.
class SemanticAnalyzerTest {

    private static List<Diagnostic> analyze(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for: " + source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        analyzer.analyze();
        return analyzer.reporter().diagnostics();
    }

    private static void assertNoDiagnostics(String source) {
        List<Diagnostic> diagnostics = analyze(source);
        assertTrue(diagnostics.isEmpty(), "expected no diagnostics, got: " + diagnostics);
    }

    private static Diagnostic assertSingleDiagnostic(String source, ErrorPhase phase) {
        List<Diagnostic> diagnostics = analyze(source);
        assertEquals(1, diagnostics.size(), "expected exactly one diagnostic, got: " + diagnostics);
        assertEquals(phase, diagnostics.get(0).phase());
        return diagnostics.get(0);
    }

    @Nested
    class DuplicateFunctions {

        @Test
        void duplicateFunctionIsReported() {
            Diagnostic d = assertSingleDiagnostic(
                    "int add(int a, int b) { return a + b; } "
                            + "int add(int x, int y) { return x - y; } void main() { }",
                    ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared"));
        }

        @Test
        void distinctFunctionNamesAreFine() {
            assertNoDiagnostics("int add(int a, int b) { return a + b; } "
                    + "int sub(int a, int b) { return a - b; } void main() { }");
        }
    }

    @Nested
    class DuplicateVariables {

        @Test
        void duplicateInSameScopeIsReported() {
            Diagnostic d = assertSingleDiagnostic("void main() { int x = 1; int x = 2; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared in this scope"));
        }

        @Test
        void duplicateParameterIsReported() {
            Diagnostic d = assertSingleDiagnostic("void f(int a, int a) { } void main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared"));
        }
    }

    @Nested
    class UndefinedVariables {

        @Test
        void undefinedVariableReadIsReported() {
            Diagnostic d = assertSingleDiagnostic("void main() { print(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined variable 'x'"));
        }

        @Test
        void undefinedAssignmentTargetIsReported() {
            Diagnostic d = assertSingleDiagnostic("void main() { x = 5; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined variable 'x'"));
        }
    }

    @Nested
    class UndefinedFunctions {

        @Test
        void undefinedFunctionCallIsReported() {
            Diagnostic d = assertSingleDiagnostic("void main() { foo(); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined function 'foo'"));
        }
    }

    @Nested
    class Shadowing {

        @Test
        void shadowingOuterVariableIsReported() {
            Diagnostic d = assertSingleDiagnostic(
                    "void main() { int x = 1; { int x = 2; print(x); } }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("shadows a variable from an enclosing scope"));
        }

        @Test
        void sameNameInSiblingScopesIsFine() {
            assertNoDiagnostics("void main() { { int x = 1; print(x); } { int x = 2; print(x); } }");
        }
    }

    @Nested
    class AssignmentCompatibility {

        @Test
        void wideningIntToFloatIsAllowed() {
            assertNoDiagnostics("void main() { float x = 5; print(x); }");
        }

        @Test
        void narrowingFloatToIntIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main() { int x = 5.0; print(x); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign"));
        }

        @Test
        void mismatchedAssignmentExpressionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main() { int x = 1; x = true; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign"));
        }
    }

    @Nested
    class ReturnCompatibility {

        @Test
        void returningWideningValueIsAllowed() {
            assertNoDiagnostics("float f() { return 5; } void main() { print(f()); }");
        }

        @Test
        void returningWrongTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "int f() { return true; } void main() { print(f()); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot return"));
        }

        @Test
        void returningValueFromVoidFunctionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void f() { return 5; } void main() { f(); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot return a value from a function declared 'VOID'"));
        }

        @Test
        void missingReturnValueInNonVoidFunctionIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "int f() { return; } void main() { print(f()); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("missing return value"));
        }
    }

    @Nested
    class ArgumentCount {

        @Test
        void tooFewArgumentsIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "int add(int a, int b) { return a + b; } void main() { print(add(1)); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("expects 2 argument(s), found 1"));
        }

        @Test
        void tooManyArgumentsIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "int add(int a, int b) { return a + b; } void main() { print(add(1, 2, 3)); }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("expects 2 argument(s), found 3"));
        }

        @Test
        void exactArgumentCountIsFine() {
            assertNoDiagnostics("int add(int a, int b) { return a + b; } void main() { print(add(1, 2)); }");
        }
    }

    @Nested
    class ArgumentType {

        @Test
        void wrongArgumentTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "int add(int a, int b) { return a + b; } void main() { print(add(1, true)); }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("argument 2 of 'add'"));
        }

        @Test
        void wideningArgumentIsAllowed() {
            assertNoDiagnostics("float f(float x) { return x; } void main() { print(f(1)); }");
        }
    }

    @Nested
    class BoolConditions {

        @Test
        void nonBoolIfConditionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main() { if (5) { } }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("if condition must be 'BOOL'"));
        }

        @Test
        void nonBoolWhileConditionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main() { while (5) { } }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("while condition must be 'BOOL'"));
        }

        @Test
        void boolConditionsAreFine() {
            assertNoDiagnostics("void main() { if (true) { } while (false) { } }");
        }
    }

    @Nested
    class Reachability {

        @Test
        void ifWithoutElseDoesNotGuaranteeReturn() {
            Diagnostic d = assertSingleDiagnostic(
                    "int f(bool x) { if (x) { return 1; } } void main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("does not return a value on all paths"));
        }

        @Test
        void ifWithElseGuaranteesReturn() {
            assertNoDiagnostics("int f(bool x) { if (x) { return 1; } else { return 2; } } void main() { }");
        }

        @Test
        void infiniteWhileTrueGuaranteesReturn() {
            assertNoDiagnostics("int f() { while (true) { return 1; } } void main() { }");
        }

        @Test
        void voidFunctionNeverNeedsToReturn() {
            assertNoDiagnostics("void f() { } void main() { }");
        }
    }

    @Nested
    class DefiniteAssignment {

        @Test
        void readBeforeAssignmentIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main() { int x; print(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void assignThenReadIsFine() {
            assertNoDiagnostics("void main() { int x; x = 5; print(x); }");
        }

        @Test
        void initializerCountsAsAssigned() {
            assertNoDiagnostics("void main() { int x = 5; print(x); }");
        }

        @Test
        void ifWithoutElseDoesNotGuaranteeAssignment() {
            Diagnostic d = assertSingleDiagnostic(
                    "void main() { int x; if (true) { x = 5; } print(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void ifWithElseGuaranteesAssignment() {
            assertNoDiagnostics("void main() { int x; if (true) { x = 5; } else { x = 6; } print(x); }");
        }

        @Test
        void whileBodyAssignmentDoesNotPersistAfterLoop() {
            Diagnostic d = assertSingleDiagnostic(
                    "void main() { int x; while (true) { x = 5; } print(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void readWithinWhileBodyAfterAssignmentIsFine() {
            assertNoDiagnostics("void main() { int x = 0; while (x < 10) { x = x + 1; } print(x); }");
        }

        @Test
        void parametersBeginAssigned() {
            assertNoDiagnostics("void f(int a) { print(a); } void main() { }");
        }

        @Test
        void nestedBlockAssignmentPersistsUnconditionally() {
            assertNoDiagnostics("void main() { int x; { x = 5; } print(x); }");
        }

        @Test
        void shadowedInnerAssignmentDoesNotSatisfyOuterRead() {
            List<Diagnostic> diagnostics =
                    analyze("void main() { int x; { int x = 5; print(x); } print(x); }");
            assertEquals(2, diagnostics.size());
            assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("shadows")));
            assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("might not have been assigned")));
        }
    }

    @Nested
    class PoisonPropagation {

        @Test
        void undefinedVariableInBinaryExpressionProducesOnlyOneDiagnostic() {
            List<Diagnostic> diagnostics = analyze("void main() { print(x + 5); }");
            assertEquals(1, diagnostics.size());
            assertTrue(diagnostics.get(0).message().contains("undefined variable 'x'"));
        }

        @Test
        void undefinedCalleeArgumentPoisonsOnlyThatArgument() {
            // x is undefined (1 diagnostic); the OTHER argument (true) is
            // independently wrong against add's second parameter (1 more) —
            // two genuinely independent problems, not a cascade from one.
            List<Diagnostic> diagnostics = analyze(
                    "int add(int a, int b) { return a + b; } void main() { print(add(x, true)); }");
            assertEquals(2, diagnostics.size());
        }

        @Test
        void poisonedInitializerStillInsertsSymbolWithDeclaredType() {
            // y's initializer (undefined x) is poisoned, but y itself is
            // still declared INT and usable afterward with no second,
            // cascading diagnostic about y's own type.
            List<Diagnostic> diagnostics = analyze("void main() { int y = x; print(y); }");
            assertEquals(1, diagnostics.size());
            assertTrue(diagnostics.get(0).message().contains("undefined variable 'x'"));
        }
    }

    @Nested
    class MainValidation {

        @Test
        void missingMainIsReported() {
            Diagnostic d = assertSingleDiagnostic(
                    "int add(int a, int b) { return a + b; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("no 'main' function"));
        }

        @Test
        void mainWithWrongReturnTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic("int main() { return 1; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("'main' must have the signature"));
        }

        @Test
        void mainWithParametersIsRejected() {
            Diagnostic d = assertSingleDiagnostic("void main(int argc) { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("'main' must have the signature"));
        }

        @Test
        void correctMainIsAccepted() {
            assertNoDiagnostics("void main() { }");
        }
    }
}
