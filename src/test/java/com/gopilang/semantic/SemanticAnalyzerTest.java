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

    // Unlike assertSingleDiagnostic, doesn't require the diagnostic to be the
    // ONLY one reported — needed for cases where a genuinely separate, correct
    // fact cascades alongside it (e.g. an undefined struct return type also
    // failing reachability, since an unreachable-in-practice return type
    // still needs a real return statement on every path).
    private static Diagnostic assertContainsDiagnostic(String source, ErrorPhase phase, String messageFragment) {
        List<Diagnostic> diagnostics = analyze(source);
        return diagnostics.stream()
                .filter(d -> d.phase() == phase && d.message().contains(messageFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a diagnostic containing '" + messageFragment + "', got: " + diagnostics));
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
            assertTrue(d.message().contains("cannot return a value from a function declared 'none'"));
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
            assertTrue(d.message().contains("if condition must be 'flag'"));
        }

        @Test
        void nonBoolWhileConditionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { loop (5) { } }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("while condition must be 'flag'"));
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
            assertTrue(d.message().contains("array index must be 'num'"));
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
            assertTrue(d.message().contains("cannot assign 'text' to array element of type 'num'"));
        }

        @Test
        void arrayAssignmentRequiresExactElementTypeNoWidening() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { dec[] a = new num[3]; }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'num[]' to variable of type 'dec[]'"));
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
            assertTrue(d.message().contains("cannot be applied to 'num[]' and 'num[]'"));
        }

        @Test
        void printingAnArrayDirectlyIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "none main() { num[] a = new num[3]; show(a); }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot print a value of type 'num[]'"));
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

    // Structs (Milestone S1, v3): declarations register into a new, separate
    // structTable — reusing registerFunctions()'s own duplicate-name pattern
    // exactly, and reusing Scope.define()'s existing duplicate-detection
    // mechanism for fields, exactly like function parameters already do.
    // Struct names now parse in type position as of Milestone S2 (see
    // StructTypedDeclarations below), but are still rejected wherever they're
    // actually used — no struct-typed fields, construction, or field access
    // yet either.
    @Nested
    class Structs {

        private SemanticModel analyzeToModel(String source) {
            Parser parser = new Parser(new Lexer(source).scanTokens());
            Program program = parser.parseProgram();
            assertFalse(parser.reporter().hasErrors(), "expected no parser errors for: " + source);
            SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
            SemanticModel model = analyzer.analyze();
            assertTrue(analyzer.reporter().diagnostics().isEmpty(),
                    "expected no diagnostics, got: " + analyzer.reporter().diagnostics());
            return model;
        }

        @Test
        void emptyStructIsFine() {
            assertNoDiagnostics("struct Empty { } none main() { }");
        }

        @Test
        void structWithFieldsIsFine() {
            assertNoDiagnostics("struct Point { num x; num y; } none main() { }");
        }

        @Test
        void duplicateStructNameIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } struct Point { num y; } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("struct 'Point' is already declared"));
        }

        @Test
        void duplicateFieldNameIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; num x; } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("field 'x' is already declared"));
        }

        @Test
        void duplicateFieldNamesAcrossDifferentStructsDoNotConflict() {
            // Each struct's fields are their own flat namespace — 'x'
            // declared in TWO DIFFERENT structs is not a collision.
            assertNoDiagnostics("struct A { num x; } struct B { num x; } none main() { }");
        }

        @Test
        void structDeclaredAfterAFunctionStillRegisters() {
            // "Forward registration": declaration order between structs and
            // functions doesn't matter — structs register in Pass 1, before
            // any function body is analyzed, mirroring registerFunctions()'s
            // own forward-reference support.
            SemanticModel model = analyzeToModel("none main() { } struct Point { num x; }");
            assertTrue(model.structTable().containsKey("Point"));
        }

        @Test
        void structAndFunctionSharingANameDoNotConflict() {
            // Structs and functions are separate namespaces, resolved by
            // grammatical position, not a shared name check — a deliberate
            // design decision, not an oversight.
            SemanticModel model = analyzeToModel(
                    "struct Point { num x; } num Point() { give 5; } none main() { }");
            assertTrue(model.structTable().containsKey("Point"));
            assertTrue(model.functionTable().containsKey("Point"));
        }

        @Test
        void multipleStructsAllRegisterIndependently() {
            SemanticModel model = analyzeToModel(
                    "struct A { num x; } struct B { num y; } struct C { num z; } none main() { }");
            assertEquals(3, model.structTable().size());
        }
    }

    // Milestone S3, v3: struct names parsed in type position (variable,
    // parameter, return, and field type — see ParserTest.StructTypedDeclarations)
    // are now real, nominal types. Since there is still no construction syntax
    // or field access, a struct-typed PARAMETER (which begins definitely
    // assigned, per analyzeFunction()) is the only way to get a real,
    // usable struct value in these tests — every assignability/compatibility
    // test below is built on that.
    @Nested
    class StructTypedDeclarations {

        @Test
        void sameStructAssignmentSucceeds() {
            assertNoDiagnostics("struct Point { num x; } none f(Point p, Point q) { p = q; } none main() { }");
        }

        @Test
        void differentStructAssignmentIsRejected() {
            // Point and Line have identical field shapes — this only fails
            // because struct typing is nominal, not structural.
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } struct Line { num x; } "
                            + "none f(Point p, Line l) { p = l; } none main() { }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'Line' to variable of type 'Point'"));
        }

        @Test
        void sameStructArrayAssignmentSucceeds() {
            assertNoDiagnostics(
                    "struct Point { num x; } none f(Point[] a, Point[] b) { a = b; } none main() { }");
        }

        @Test
        void differentStructArrayAssignmentIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } struct Line { num x; } "
                            + "none f(Point[] a, Line[] b) { a = b; } none main() { }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'Line[]' to variable of type 'Point[]'"));
        }

        @Test
        void structToPrimitiveAssignmentIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } none f(Point p) { num n; n = p; } none main() { }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'Point' to variable of type 'num'"));
        }

        @Test
        void primitiveToStructAssignmentIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } none f(Point p) { p = 5; } none main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot assign 'num' to variable of type 'Point'"));
        }

        @Test
        void parameterCompatibilitySucceeds() {
            assertNoDiagnostics(
                    "struct Point { num x; } none takes(Point p) { } "
                            + "none caller(Point p) { takes(p); } none main() { }");
        }

        @Test
        void parameterCompatibilityIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } struct Line { num x; } none takes(Point p) { } "
                            + "none caller(Line l) { takes(l); } none main() { }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("argument 1 of 'takes': expected 'Point', found 'Line'"));
        }

        @Test
        void returnCompatibilitySucceeds() {
            assertNoDiagnostics("struct Point { num x; } Point identity(Point p) { give p; } none main() { }");
        }

        @Test
        void returnCompatibilityIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } struct Line { num x; } "
                            + "Point identity(Line l) { give l; } none main() { }",
                    ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot return 'Line' from a function declared to return 'Point'"));
        }

        @Test
        void forwardReferenceResolves() {
            // Point is used as a parameter type before its own declaration.
            assertNoDiagnostics("none f(Point p) { } struct Point { num x; } none main() { }");
        }

        @Test
        void undefinedStructAsVariableTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { NotAStruct p; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined struct 'NotAStruct'"));
        }

        @Test
        void undefinedStructAsParameterTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none f(NotAStruct p) { } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined struct 'NotAStruct'"));
        }

        @Test
        void undefinedStructAsReturnTypeIsRejected() {
            // An empty body also fails reachability here (an undefined return
            // type is never treated as 'none'), which is a separate, correct
            // fact rather than a duplicate report of the same problem —
            // assertContainsDiagnostic (not assertSingleDiagnostic) is exactly
            // for this.
            Diagnostic d = assertContainsDiagnostic(
                    "NotAStruct make() { } none main() { }", ErrorPhase.SEMANTIC, "undefined struct 'NotAStruct'");
            assertTrue(d.message().contains("undefined struct 'NotAStruct'"));
        }

        @Test
        void undefinedStructAsFieldTypeIsRejected() {
            Diagnostic d = assertSingleDiagnostic("struct Box { NotAStruct x; } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined struct 'NotAStruct'"));
        }

        @Test
        void undefinedStructArrayIsRejected() {
            Diagnostic d = assertSingleDiagnostic("none main() { NotAStruct[] arr; }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("undefined struct 'NotAStruct'"));
        }

        @Test
        void printingAStructIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } none f(Point p) { show(p); } none main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("cannot print a value of type 'Point'"));
        }

        @Test
        void binaryOperatorOnStructsIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } none f(Point p, Point q) { p + q; } none main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("operator '+' cannot be applied to 'Point' and 'Point'"));
        }

        @Test
        void unaryOperatorOnStructIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct Point { num x; } none f(Point p) { !p; } none main() { }", ErrorPhase.TYPE);
            assertTrue(d.message().contains("operator '!' cannot be applied to 'Point'"));
        }

        @Test
        void directSelfRecursionIsRejected() {
            Diagnostic d = assertSingleDiagnostic("struct Node { Node next; } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("struct 'Node' cannot contain itself"));
        }

        @Test
        void indirectRecursionIsRejected() {
            Diagnostic d = assertSingleDiagnostic(
                    "struct A { B b; } struct B { A a; } none main() { }", ErrorPhase.SEMANTIC);
            assertTrue(d.message().contains("cannot contain itself"));
        }

        @Test
        void arrayMediatedSelfReferenceIsAccepted() {
            // An array field is a runtime reference, not inline storage — it
            // cannot create an unbounded-size cycle, so this is legal.
            assertNoDiagnostics("struct Node { Node[] children; } none main() { }");
        }

        @Test
        void diamondDependencyGraphIsNotACycle() {
            // B and C both reference A — shared, not mutual, containment.
            // A naive "have I seen this struct name before" check (instead of
            // proper onPath/seen tracking) would misreport this as a cycle.
            assertNoDiagnostics(
                    "struct A { num x; } struct B { A a; } struct C { A a; } "
                            + "struct D { B b; C c; } none main() { }");
        }

        @Test
        void primitiveDeclarationsAreUnaffected() {
            assertNoDiagnostics("struct Point { num x; } none main() { num p; }");
        }
    }
}
