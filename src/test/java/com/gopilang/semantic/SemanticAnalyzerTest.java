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
                    "num add(num a, num b) { give a + b; } "
                            + "num add(num x, num y) { give x - y; } none main() { }",
                    ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared"));
        }

        @Test
        void distinctFunctionNamesAreFine() {
            assertNoDiagnostics("num add(num a, num b) { give a + b; } "
                    + "num sub(num a, num b) { give a - b; } none main() { }");
        }
    }

    @Nested
    class DuplicateVariables {

        @Test
        void duplicateInSameScopeIsReported() {
            Diagnostic d = assertSingleDiagnostic("none main() { num x = 1; num x = 2; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared in this scope"));
        }

        @Test
        void duplicateParameterIsReported() {
            Diagnostic d = assertSingleDiagnostic("none f(num a, num a) { } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("already declared"));
        }
    }

    @Nested
    class UndefinedVariables {

        @Test
        void undefinedVariableReadIsReported() {
            Diagnostic d = assertSingleDiagnostic("none main() { show(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined variable 'x'"));
        }

        @Test
        void undefinedAssignmentTargetIsReported() {
            Diagnostic d = assertSingleDiagnostic("none main() { x = 5; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined variable 'x'"));
        }
    }

    @Nested
    class UndefinedFunctions {

        @Test
        void undefinedFunctionCallIsReported() {
            Diagnostic d = assertSingleDiagnostic("none main() { foo(); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined function 'foo'"));
        }
    }

    @Nested
    class Shadowing {

        @Test
        void shadowingOuterVariableIsReported() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num x = 1; { num x = 2; show(x); } }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("shadows a variable from an enclosing scope"));
        }

        @Test
        void sameNameInSiblingScopesIsFine() {
            assertNoDiagnostics("none main() { { num x = 1; show(x); } { num x = 2; show(x); } }");
        }
    }

    @Nested
    class AssignmentCompatibility {

        @Test
        void wideningIntToFloatIsAllowed() {
            assertNoDiagnostics("none main() { dec x = 5; show(x); }");
        }

        @Test
        void narrowingFloatToIntIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { num x = 5.0; show(x); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign"));
        }

        @Test
        void mismatchedAssignmentExpressionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { num x = 1; x = yes; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign"));
        }
    }

    @Nested
    class ReturnCompatibility {

        @Test
        void returningWideningValueIsAllowed() {
            assertNoDiagnostics("dec f() { give 5; } none main() { show(f()); }");
        }

        @Test
        void returningWrongTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "num f() { give yes; } none main() { show(f()); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot return"));
        }

        @Test
        void returningValueFromVoidFunctionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none f() { give 5; } none main() { f(); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot return a value from a function declared 'VOID'"));
        }

        @Test
        void missingReturnValueInNonVoidFunctionIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "num f() { give; } none main() { show(f()); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("missing return value"));
        }
    }

    @Nested
    class ArgumentCount {

        @Test
        void tooFewArgumentsIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "num add(num a, num b) { give a + b; } none main() { show(add(1)); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("expects 2 argument(s), found 1"));
        }

        @Test
        void tooManyArgumentsIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "num add(num a, num b) { give a + b; } none main() { show(add(1, 2, 3)); }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("expects 2 argument(s), found 3"));
        }

        @Test
        void exactArgumentCountIsFine() {
            assertNoDiagnostics("num add(num a, num b) { give a + b; } none main() { show(add(1, 2)); }");
        }
    }

    @Nested
    class ArgumentType {

        @Test
        void wrongArgumentTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "num add(num a, num b) { give a + b; } none main() { show(add(1, yes)); }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("argument 2 of 'add'"));
        }

        @Test
        void wideningArgumentIsAllowed() {
            assertNoDiagnostics("dec f(dec x) { give x; } none main() { show(f(1)); }");
        }
    }

    @Nested
    class BoolConditions {

        @Test
        void nonBoolIfConditionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { if (5) { } }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("if condition must be 'BOOL'"));
        }

        @Test
        void nonBoolWhileConditionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { loop (5) { } }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("while condition must be 'BOOL'"));
        }

        @Test
        void boolConditionsAreFine() {
            assertNoDiagnostics("none main() { if (yes) { } loop (no) { } }");
        }
    }

    @Nested
    class Reachability {

        @Test
        void ifWithoutElseDoesNotGuaranteeReturn() {
            Diagnostic d = assertSingleDiagnostic(
                    "num f(flag x) { if (x) { give 1; } } none main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("does not return a value on all paths"));
        }

        @Test
        void ifWithElseGuaranteesReturn() {
            assertNoDiagnostics("num f(flag x) { if (x) { give 1; } else { give 2; } } none main() { }");
        }

        @Test
        void infiniteWhileTrueGuaranteesReturn() {
            assertNoDiagnostics("num f() { loop (yes) { give 1; } } none main() { }");
        }

        @Test
        void voidFunctionNeverNeedsToReturn() {
            assertNoDiagnostics("none f() { } none main() { }");
        }
    }

    @Nested
    class DefiniteAssignment {

        @Test
        void readBeforeAssignmentIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { num x; show(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void assignThenReadIsFine() {
            assertNoDiagnostics("none main() { num x; x = 5; show(x); }");
        }

        @Test
        void initializerCountsAsAssigned() {
            assertNoDiagnostics("none main() { num x = 5; show(x); }");
        }

        @Test
        void ifWithoutElseDoesNotGuaranteeAssignment() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num x; if (yes) { x = 5; } show(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void ifWithElseGuaranteesAssignment() {
            assertNoDiagnostics("none main() { num x; if (yes) { x = 5; } else { x = 6; } show(x); }");
        }

        @Test
        void whileBodyAssignmentDoesNotPersistAfterLoop() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num x; loop (yes) { x = 5; } show(x); }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("might not have been assigned"));
        }

        @Test
        void readWithinWhileBodyAfterAssignmentIsFine() {
            assertNoDiagnostics("none main() { num x = 0; loop (x < 10) { x = x + 1; } show(x); }");
        }

        @Test
        void parametersBeginAssigned() {
            assertNoDiagnostics("none f(num a) { show(a); } none main() { }");
        }

        @Test
        void nestedBlockAssignmentPersistsUnconditionally() {
            assertNoDiagnostics("none main() { num x; { x = 5; } show(x); }");
        }

        @Test
        void shadowedInnerAssignmentDoesNotSatisfyOuterRead() {
            List<Diagnostic> diagnostics =
                    analyze("none main() { num x; { num x = 5; show(x); } show(x); }");
            assertEquals(2, diagnostics.size());
            assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("shadows")));
            assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("might not have been assigned")));
        }
    }

    @Nested
    class PoisonPropagation {

        @Test
        void undefinedVariableInBinaryExpressionProducesOnlyOneDiagnostic() {
            List<Diagnostic> diagnostics = analyze("none main() { show(x + 5); }");
            assertEquals(1, diagnostics.size());
            assertTrue(diagnostics.get(0).message().contains("undefined variable 'x'"));
        }

        @Test
        void undefinedCalleeArgumentPoisonsOnlyThatArgument() {
            // x is undefined (1 diagnostic); the OTHER argument (yes) is
            // independently wrong against add's second parameter (1 more) —
            // two genuinely independent problems, not a cascade from one.
            List<Diagnostic> diagnostics = analyze(
                    "num add(num a, num b) { give a + b; } none main() { show(add(x, yes)); }");
            assertEquals(2, diagnostics.size());
        }

        @Test
        void poisonedInitializerStillInsertsSymbolWithDeclaredType() {
            // y's initializer (undefined x) is poisoned, but y itself is
            // still declared INT and usable afterward with no second,
            // cascading diagnostic about y's own type.
            List<Diagnostic> diagnostics = analyze("none main() { num y = x; show(y); }");
            assertEquals(1, diagnostics.size());
            assertTrue(diagnostics.get(0).message().contains("undefined variable 'x'"));
        }
    }

    @Nested
    class MainValidation {

        @Test
        void missingMainIsReported() {
            Diagnostic d = assertSingleDiagnostic(
                    "num add(num a, num b) { give a + b; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("no 'main' function"));
        }

        @Test
        void mainWithWrongReturnTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic("num main() { give 1; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("'main' must have the signature"));
        }

        @Test
        void mainWithParametersIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main(num argc) { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("'main' must have the signature"));
        }

        @Test
        void correctMainIsAccepted() {
            assertNoDiagnostics("none main() { }");
        }
    }

    // Arrays (v2 Milestone 1): typeOf's new NewArrayExpression/
    // ArrayAccessExpression/ArrayLengthExpression/IndexAssignmentExpression
    // cases, plus the array-rejection guard added to the existing
    // BinaryExpression/UnaryExpression/isPrintable checks. Every diagnostic
    // message here was verified against the real analyzer first.
    @Nested
    class Arrays {

        @Test
        void creationIndexingAssignmentAndLengthAllTypeCheck() {
            assertNoDiagnostics("""
                    none main() {
                        num[] a = new num[5];
                        a[0] = 1;
                        num x = a[0];
                        num n = a.len();
                    }
                    """);
        }

        @Test
        void arrayIsAValidParameterAndReturnType() {
            assertNoDiagnostics("""
                    num[] identity(num[] a) { give a; }
                    none main() {
                        num[] a = new num[3];
                        num[] b = identity(a);
                    }
                    """);
        }

        @Test
        void indexingANonArrayIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num x = 5; show(x[0]); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot index into non-array type"));
        }

        @Test
        void nonNumIndexIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num[] a = new num[3]; show(a[\"oops\"]); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("array index must be 'INT'"));
        }

        @Test
        void dotLenOnNonArrayIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num x = 5; show(x.len()); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("'.len()' can only be called on an array"));
        }

        @Test
        void assigningWrongElementTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num[] a = new num[3]; a[0] = \"hello\"; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'STRING' to array element of type 'INT'"));
        }

        @Test
        void arrayAssignmentRequiresExactElementTypeNoWidening() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { dec[] a = new num[3]; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'INT[]' to variable of type 'FLOAT[]'"));
        }

        @Test
        void arrayOfNoneIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { none[] a = new none[3]; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("array element type cannot be 'none'"));
        }

        @Test
        void arithmeticOnArraysIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num[] a = new num[3]; num[] b = new num[3]; show(a + b); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot be applied to 'INT[]' and 'INT[]'"));
        }

        @Test
        void printingAnArrayDirectlyIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num[] a = new num[3]; show(a); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot print a value of type 'INT[]'"));
        }

        @Test
        void arraysOfEveryElementTypeTypeCheck() {
            assertNoDiagnostics("""
                    none main() {
                        num[] nums = new num[1];
                        dec[] decs = new dec[1];
                        flag[] flags = new flag[1];
                        text[] texts = new text[1];
                        nums[0] = 1;
                        decs[0] = 1.5;
                        flags[0] = yes;
                        texts[0] = "hi";
                    }
                    """);
        }
    }
}
