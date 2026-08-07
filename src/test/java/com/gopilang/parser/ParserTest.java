package com.gopilang.parser;

import com.gopilang.ast.Parameter;
import com.gopilang.ast.Program;
import com.gopilang.ast.StructDeclaration;
import com.gopilang.lexer.Lexer;
import com.gopilang.printer.AstPrinter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Categorized per Milestone 3's wrap-up requirements. Recovery-specific tests
// live in ParserRecoveryTest — this suite covers everything recovery isn't:
// the shape of a well-formed parse, across every construct in the grammar.
class ParserTest {

    private static String parseAndPrint(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for: " + source);
        return AstPrinter.print(program);
    }

    // Function parsing matters because it's the ONLY top-level construct in
    // v1's grammar (Program holds nothing else) — every other test in this
    // suite depends on functions parsing correctly first.
    @Nested
    class FunctionParsing {

        @Test
        void noParameters() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("none main() { }"));
        }

        @Test
        void multipleParameters() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration add() -> INT
                        ├── Parameter INT a
                        ├── Parameter INT b
                        └── BlockStatement
                            └── ReturnStatement
                                └── BinaryExpression [ADD]
                                    ├── VariableExpression a
                                    └── VariableExpression b
                    """, parseAndPrint("num add(num a, num b) { give a + b; }"));
        }

        @Test
        void everyPrimitiveReturnTypeParses() {
            String source = "num f1() { give 1; } dec f2() { give 1.0; } "
                    + "flag f3() { give yes; } text f4() { give \"x\"; } none f5() { }";
            Parser parser = new Parser(new Lexer(source).scanTokens());
            Program program = parser.parseProgram();
            assertFalse(parser.reporter().hasErrors());
            assertEquals(5, program.functions().size());
        }
    }

    // Statement parsing matters because it's where most of the grammar's
    // actual variety lives — six distinct constructs, each with its own
    // optional pieces (initializer, return value) that need their own check.
    @Nested
    class StatementParsing {

        @Test
        void variableDeclarationWithInitializer() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT x
                                └── LiteralExpression 10 (INT)
                    """, parseAndPrint("none main() { num x = 10; }"));
        }

        @Test
        void variableDeclarationWithoutInitializer() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT x
                    """, parseAndPrint("none main() { num x; }"));
        }

        @Test
        void returnWithValue() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration getFive() -> INT
                        └── BlockStatement
                            └── ReturnStatement
                                └── LiteralExpression 5 (INT)
                    """, parseAndPrint("num getFive() { give 5; }"));
        }

        @Test
        void returnWithoutValue() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration doNothing() -> VOID
                        └── BlockStatement
                            └── ReturnStatement
                    """, parseAndPrint("none doNothing() { give; }"));
        }

        @Test
        void printStatement() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── LiteralExpression 42 (INT)
                    """, parseAndPrint("none main() { show(42); }"));
        }
    }

    // Expression parsing matters because primary/grouping/unary are the base
    // cases every higher ladder level eventually bottoms out at — if these
    // are wrong, everything built on top of them is unreliable regardless of
    // how correct the ladder's own loops are.
    @Nested
    class ExpressionParsing {

        @Test
        void literalsOfEveryType() {
            String source = "none main() { show(1); show(1.5); show(yes); show(\"hi\"); }";
            Parser parser = new Parser(new Lexer(source).scanTokens());
            Program program = parser.parseProgram();
            assertFalse(parser.reporter().hasErrors());
        }

        @Test
        void groupingPreservesExplicitParentheses() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── GroupingExpression
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { show((5)); }"));
        }

        @Test
        void unaryNegation() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── UnaryExpression [NEGATE]
                                    └── VariableExpression x
                    """, parseAndPrint("none main() { show(-x); }"));
        }
    }

    // Precedence matters because it's the entire reason the grammar is a
    // ladder of nine levels instead of one flat rule — these tests are the
    // direct, executable proof that the ladder produces conventional
    // arithmetic/logical precedence, not just "a" tree shape.
    @Nested
    class OperatorPrecedence {

        @Test
        void multiplicationBindsTighterThanAddition() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── BinaryExpression [ADD]
                                    ├── LiteralExpression 1 (INT)
                                    └── BinaryExpression [MULTIPLY]
                                        ├── LiteralExpression 2 (INT)
                                        └── LiteralExpression 3 (INT)
                    """, parseAndPrint("none main() { show(1 + 2 * 3); }"));
        }

        @Test
        void comparisonBindsTighterThanLogicalAnd() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── BinaryExpression [AND]
                                    ├── BinaryExpression [LESS]
                                    │   ├── VariableExpression x
                                    │   └── LiteralExpression 5 (INT)
                                    └── VariableExpression y
                    """, parseAndPrint("none main() { show(x < 5 && y); }"));
        }

        @Test
        void parenthesesOverrideDefaultPrecedence() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── BinaryExpression [MULTIPLY]
                                    ├── GroupingExpression
                                    │   └── BinaryExpression [ADD]
                                    │       ├── LiteralExpression 1 (INT)
                                    │       └── LiteralExpression 2 (INT)
                                    └── LiteralExpression 3 (INT)
                    """, parseAndPrint("none main() { show((1 + 2) * 3); }"));
        }
    }

    // Associativity matters separately from precedence — it answers a
    // different question ("same precedence, which order?") and the ladder
    // enforces it two different ways (loops for left-assoc, recursion for
    // right-assoc), so both mechanisms need their own direct test.
    @Nested
    class Associativity {

        @Test
        void subtractionIsLeftAssociative() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── BinaryExpression [SUBTRACT]
                                    ├── BinaryExpression [SUBTRACT]
                                    │   ├── LiteralExpression 1 (INT)
                                    │   └── LiteralExpression 2 (INT)
                                    └── LiteralExpression 3 (INT)
                    """, parseAndPrint("none main() { show(1 - 2 - 3); }"));
        }

        @Test
        void assignmentIsRightAssociative() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── ExpressionStatement
                            │   └── AssignmentExpression a =
                            │       └── AssignmentExpression b =
                            │           └── LiteralExpression 5 (INT)
                            └── PrintStatement
                                └── VariableExpression a
                    """, parseAndPrint("none main() { a = b = 5; show(a); }"));
        }

        @Test
        void unaryNegationIsRightAssociative() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── UnaryExpression [NEGATE]
                                    └── UnaryExpression [NEGATE]
                                        └── VariableExpression x
                    """, parseAndPrint("none main() { show(- -x); }"));
        }
    }

    // Function calls matter as their own category because parseCall() was
    // the one method added beyond the originally-listed ladder, restoring
    // FunctionCallExpression's only construction path — it deserves direct
    // coverage precisely because of that history.
    @Nested
    class FunctionCalls {

        @Test
        void callWithArguments() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── FunctionCallExpression add(2 args)
                                    ├── LiteralExpression 1 (INT)
                                    └── LiteralExpression 2 (INT)
                    """, parseAndPrint("none main() { show(add(1, 2)); }"));
        }

        @Test
        void callWithZeroArguments() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── FunctionCallExpression now(0 args)
                    """, parseAndPrint("none main() { show(now()); }"));
        }
    }

    // Arrays (v2 Milestone 1): array-typed declarations/parameters/returns,
    // creation, indexing, index-assignment, and '.len()' — the array-specific
    // grammar additions layered on top of the existing type/expression rules
    // without changing how any pre-existing construct parses.
    @Nested
    class Arrays {

        @Test
        void arrayTypeDeclarationAndCreation() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT[] a
                                └── NewArrayExpression INT[]
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { num[] a = new num[5]; }"));
        }

        @Test
        void arrayIndexingReadsAnElement() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── ArrayAccessExpression
                                    ├── VariableExpression a
                                    └── LiteralExpression 0 (INT)
                    """, parseAndPrint("none main() { show(a[0]); }"));
        }

        @Test
        void indexAssignmentIsADistinctNodeFromPlainAssignment() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── IndexAssignmentExpression
                                    ├── VariableExpression a
                                    ├── LiteralExpression 0 (INT)
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { a[0] = 5; }"));
        }

        @Test
        void dotLenReadsArrayLength() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── ArrayLengthExpression
                                    └── VariableExpression a
                    """, parseAndPrint("none main() { show(a.len()); }"));
        }

        @Test
        void arrayTypeIsValidAsParameterAndReturnType() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration f() -> INT[]
                        ├── Parameter INT[] a
                        └── BlockStatement
                            └── ReturnStatement
                                └── VariableExpression a
                    """, parseAndPrint("num[] f(num[] a) { give a; }"));
        }

        @Test
        void dotSuffixOtherThanLenIsAParseError() {
            Parser parser = new Parser(new Lexer("none main() { show(a.size()); }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void assigningToALiteralIsAParseError() {
            Parser parser = new Parser(new Lexer("none main() { 5 = 6; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }
    }

    // run (Milestone 2, v2): a for-loop is pure syntactic sugar over the
    // existing BlockStatement/WhileStatement/ExpressionStatement nodes — no
    // ForStatement node exists anywhere in the AST. These tests are the
    // executable proof of that desugaring shape, not just of "it parses".
    @Nested
    class ForLoop {

        @Test
        void desugarsToOuterBlockWithDeclarationAndWhile() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── BlockStatement
                                ├── VariableDeclaration INT i
                                │   └── LiteralExpression 0 (INT)
                                └── WhileStatement
                                    ├── condition
                                    │   └── BinaryExpression [LESS]
                                    │       ├── VariableExpression i
                                    │       └── LiteralExpression 5 (INT)
                                    └── body
                                        └── BlockStatement
                                            ├── BlockStatement
                                            │   └── PrintStatement
                                            │       └── VariableExpression i
                                            └── ExpressionStatement
                                                └── AssignmentExpression i =
                                                    └── BinaryExpression [ADD]
                                                        ├── VariableExpression i
                                                        └── LiteralExpression 1 (INT)
                    """, parseAndPrint("""
                    none main() {
                        run (num i = 0; i < 5; i = i + 1) {
                            show(i);
                        }
                    }
                    """));
        }

        @Test
        void initClauseCanReuseAnExistingVariableInsteadOfDeclaringOne() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT i
                            └── BlockStatement
                                ├── ExpressionStatement
                                │   └── AssignmentExpression i =
                                │       └── LiteralExpression 0 (INT)
                                └── WhileStatement
                                    ├── condition
                                    │   └── BinaryExpression [LESS]
                                    │       ├── VariableExpression i
                                    │       └── LiteralExpression 5 (INT)
                                    └── body
                                        └── BlockStatement
                                            ├── BlockStatement
                                            │   └── PrintStatement
                                            │       └── VariableExpression i
                                            └── ExpressionStatement
                                                └── AssignmentExpression i =
                                                    └── BinaryExpression [ADD]
                                                        ├── VariableExpression i
                                                        └── LiteralExpression 1 (INT)
                    """, parseAndPrint("""
                    none main() {
                        num i;
                        run (i = 0; i < 5; i = i + 1) {
                            show(i);
                        }
                    }
                    """));
        }

        @Test
        void missingSemicolonBetweenClausesIsAParseError() {
            Parser parser = new Parser(new Lexer(
                    "none main() { run (num i = 0 i < 5; i = i + 1) { show(i); } }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingIncrementClauseIsAParseError() {
            Parser parser = new Parser(new Lexer(
                    "none main() { run (num i = 0; i < 5;) { show(i); } }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingParenthesesIsAParseError() {
            Parser parser = new Parser(new Lexer(
                    "none main() { run num i = 0; i < 5; i = i + 1) { show(i); } }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }
    }

    // Structs (Milestone S1, v3): top-level struct declarations only — no
    // struct-typed fields/variables, no construction, no field access yet
    // (all explicitly out of scope for this milestone). Fields are plain
    // Parameters, the exact same shape as function parameters, so these
    // tests mirror FunctionParsing's own shape closely.
    @Nested
    class Structs {

        @Test
        void emptyStruct() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Empty
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Empty { } none main() { }"));
        }

        @Test
        void structWithOneField() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } none main() { }"));
        }

        @Test
        void structWithManyFields() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   ├── Parameter INT x
                    │   ├── Parameter INT y
                    │   └── Parameter STRING label
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; num y; text label; } none main() { }"));
        }

        @Test
        void duplicateFieldNameParsesStructurally() {
            // The parser has no duplicate-name concept of its own (matching
            // how it never rejects duplicate function names either) — both
            // fields appear in the tree exactly as written. Semantic
            // analysis, not the parser, is what rejects this (see
            // SemanticAnalyzerTest.Structs).
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   ├── Parameter INT x
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; num x; } none main() { }"));
        }

        @Test
        void multipleStructDeclarations() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Line
                    │   └── Parameter INT length
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint(
                    "struct Point { num x; } struct Line { num length; } none main() { }"));
        }

        @Test
        void structDeclarationSourceRangeSpansKeywordToClosingBrace() {
            Parser parser = new Parser(new Lexer("struct Point {\n    num x;\n}").scanTokens());
            Program program = parser.parseProgram();
            assertFalse(parser.reporter().hasErrors());

            StructDeclaration point = program.structs().get(0);
            assertEquals(1, point.range().start().line());
            assertEquals(1, point.range().start().column());
            assertEquals(3, point.range().end().line());
            assertEquals(1, point.range().end().column());
        }

        @Test
        void fieldSourceRangeSpansTypeToSemicolon() {
            Parser parser = new Parser(new Lexer("struct Point {\n    num x;\n}").scanTokens());
            Program program = parser.parseProgram();
            assertFalse(parser.reporter().hasErrors());

            Parameter field = program.structs().get(0).fields().get(0);
            assertEquals(2, field.range().start().line());
            assertEquals(5, field.range().start().column());
            assertEquals(2, field.range().end().line());
            assertEquals(10, field.range().end().column());
        }

        @Test
        void missingStructNameIsAParseError() {
            Parser parser = new Parser(new Lexer("struct { num x; } none main() { }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingFieldSemicolonIsAParseError() {
            Parser parser = new Parser(new Lexer("struct Point { num x } none main() { }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingClosingBraceIsAParseError() {
            Parser parser = new Parser(new Lexer("struct Point { num x; none main() { }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }
    }

    // Struct names are accepted in type position (variable type, parameter
    // type, return type, and — as of Milestone S3 — field type) via the
    // smallest lookahead needed to resolve the "Point p;" (declaration) vs
    // "p = ...;" (assignment) vs "foo();" (call) ambiguity at statement start
    // — see Parser.isStructTypedDeclarationStart(). As of Milestone S3, an
    // array of a struct type ("Point[] arr;") is also legal everywhere a type
    // is legal, which adds a second ambiguity at statement start ("Point[]
    // arr;" vs "arr[i] = 5;", both starting IDENTIFIER LEFT_BRACKET) resolved
    // by one more token of fixed lookahead. Whether a struct name actually
    // resolves to a real, non-cyclic struct is semantic analysis's job (see
    // SemanticAnalyzerTest.StructTypedDeclarations), not the parser's.
    @Nested
    class StructTypedDeclarations {

        @Test
        void structTypedVariableDeclarationParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration Point p
                    """, parseAndPrint("struct Point { num x; } none main() { Point p; }"));
        }

        @Test
        void structTypedParameterParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration takesPoint() -> VOID
                        ├── Parameter Point p
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } none takesPoint(Point p) { }"));
        }

        @Test
        void structTypedReturnTypeParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration makePoint() -> Point
                        └── BlockStatement
                            └── ReturnStatement
                                └── LiteralExpression 5 (INT)
                    """, parseAndPrint("struct Point { num x; } Point makePoint() { give 5; }"));
        }

        @Test
        void primitiveDeclarationsAreUnaffected() {
            // Same shape as StatementParsing.variableDeclarationWithoutInitializer
            // — re-asserted here as the direct "unchanged" counterpart to the
            // new struct-typed case above.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT x
                    """, parseAndPrint("none main() { num x; }"));
        }

        @Test
        void assignmentStatementStillParsesAsAnExpressionStatement() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT p
                            └── ExpressionStatement
                                └── AssignmentExpression p =
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { num p; p = 5; }"));
        }

        @Test
        void functionCallStatementStillParsesAsAnExpressionStatement() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── FunctionCallExpression foo(0 args)
                    """, parseAndPrint("none main() { foo(); }"));
        }

        @Test
        void twoIdentifiersInARowParseAsAStructTypedDeclaration() {
            // The exact ambiguity this milestone resolves: IDENTIFIER
            // IDENTIFIER is the only shape that means "declaration".
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration Point p
                    """, parseAndPrint("none main() { Point p; }"));
        }

        @Test
        void singleIdentifierFollowedByEqualsIsNotADeclaration() {
            // "Point = 5;" is one identifier followed by '=', not two
            // identifiers in a row — an ordinary assignment, exactly as if
            // "Point" were any other already-declared variable name.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT Point
                            └── ExpressionStatement
                                └── AssignmentExpression Point =
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { num Point; Point = 5; }"));
        }

        @Test
        void singleIdentifierFollowedByLeftParenIsNotADeclaration() {
            // "Point();" is one identifier followed by '(', not two
            // identifiers in a row — an ordinary function call.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── FunctionCallExpression Point(0 args)
                    """, parseAndPrint("none main() { Point(); }"));
        }

        @Test
        void structFieldParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Box
                    │   └── Parameter Point corner
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } struct Box { Point corner; } none main() { }"));
        }

        @Test
        void selfReferencingFieldParsesSyntactically() {
            // The parser has no cycle concept — "Node next;" parses exactly
            // like any other struct-typed field. Semantic analysis is what
            // rejects this (see SemanticAnalyzerTest.StructTypedDeclarations).
            assertEquals("""
                    Program
                    ├── StructDeclaration Node
                    │   └── Parameter Node next
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Node { Node next; } none main() { }"));
        }

        @Test
        void arrayOfStructVariableDeclarationParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration Point[] arr
                    """, parseAndPrint("struct Point { num x; } none main() { Point[] arr; }"));
        }

        @Test
        void arrayOfStructParameterParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration takesPoints() -> VOID
                        ├── Parameter Point[] p
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } none takesPoints(Point[] p) { }"));
        }

        @Test
        void arrayOfStructReturnTypeParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration makePoints() -> Point[]
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } Point[] makePoints() { }"));
        }

        @Test
        void arrayOfStructFieldParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Box
                    │   └── Parameter Point[] corners
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Point { num x; } struct Box { Point[] corners; } none main() { }"));
        }

        @Test
        void arrayOfSelfReferencingFieldParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Node
                    │   └── Parameter Node[] children
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                    """, parseAndPrint("struct Node { Node[] children; } none main() { }"));
        }

        @Test
        void indexAssignmentStatementStillParsesAsAnExpressionStatement() {
            // "arr[0] = 5;" starts IDENTIFIER LEFT_BRACKET, the same as
            // "Point[] arr;" — the third token (a real index expression, not
            // an immediate ']') is what keeps this parsing as an ordinary
            // index assignment rather than being misread as a declaration.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT[] arr
                            └── ExpressionStatement
                                └── IndexAssignmentExpression
                                    ├── VariableExpression arr
                                    ├── LiteralExpression 0 (INT)
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { num[] arr; arr[0] = 5; }"));
        }
    }

    // Milestone S4, v3: struct construction, "new StructName(arguments...)".
    // The "new" dispatch is lexically unambiguous — a primitive keyword
    // (array creation, unchanged) and an IDENTIFIER (struct construction)
    // are disjoint token sets, so no lookahead beyond the token right after
    // "new" is needed. Semantic analysis (undefined struct, argument
    // count/type checking) is a separate milestone phase, not tested here —
    // this suite covers only the parse shape.
    @Nested
    class NewStructExpressions {

        @Test
        void noArgumentConstructionParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── NewStructExpression Point(0 args)
                    """, parseAndPrint("struct Point { num x; } none main() { new Point(); }"));
        }

        @Test
        void twoArgumentConstructionParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   ├── Parameter INT x
                    │   └── Parameter INT y
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── NewStructExpression Point(2 args)
                                    ├── LiteralExpression 1 (INT)
                                    └── LiteralExpression 2 (INT)
                    """, parseAndPrint("struct Point { num x; num y; } none main() { new Point(1, 2); }"));
        }

        @Test
        void threeArgumentConstructionWithExpressionsParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   ├── Parameter INT x
                    │   ├── Parameter INT y
                    │   └── Parameter INT z
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT a
                            ├── VariableDeclaration INT b
                            ├── VariableDeclaration INT c
                            └── ExpressionStatement
                                └── NewStructExpression Point(3 args)
                                    ├── VariableExpression a
                                    ├── VariableExpression b
                                    └── VariableExpression c
                    """, parseAndPrint(
                    "struct Point { num x; num y; num z; } "
                            + "none main() { num a; num b; num c; new Point(a, b, c); }"));
        }

        @Test
        void constructionAsAVariableInitializerParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration Point p
                                └── NewStructExpression Point(1 args)
                                    └── LiteralExpression 1 (INT)
                    """, parseAndPrint("struct Point { num x; } none main() { Point p = new Point(1); }"));
        }

        @Test
        void arrayCreationStillParsesUnchanged() {
            // Regression: "new" dispatching on a primitive keyword still
            // takes the existing array-creation path, untouched by the new
            // struct-construction branch.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT[] a
                                └── NewArrayExpression INT[]
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("none main() { num[] a = new num[5]; }"));
        }

        @Test
        void arrayOfStructCreationIsUnsupportedByDesign() {
            // "new Point[5]" is intentionally NOT implemented — Point[] stays
            // a legal declarable type (see ParserTest.StructTypedDeclarations)
            // with no way to construct one, exactly as before this milestone.
            // parseElementType() (the array-creation path) only ever matches
            // a primitive keyword, so an IDENTIFIER here is a parse error.
            Parser parser = new Parser(new Lexer(
                    "struct Point { num x; } none main() { Point[] arr = new Point[5]; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingClosingParenIsAParseError() {
            Parser parser = new Parser(
                    new Lexer("struct Point { num x; } none main() { new Point(1; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingOpenParenIsAParseError() {
            Parser parser = new Parser(
                    new Lexer("struct Point { num x; } none main() { new Point; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }
    }

    // Milestone S5, v3: struct field access and assignment, "target.field"
    // and "target.field = value". parseCall() became a LOOP over suffixes
    // (call/index/dot) rather than "at most one", specifically so these
    // chain arbitrarily (point.inner.left.right, arr[0].x, foo().x). Field
    // resolution/type checking is a later phase — this suite covers only the
    // parse shape (semantic placeholders always yield no diagnostics here).
    @Nested
    class FieldAccessExpressions {

        @Test
        void basicFieldReadParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Point p
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── VariableExpression p
                    """, parseAndPrint("struct Point { num x; } none main() { Point p; p.x; }"));
        }

        @Test
        void basicFieldAssignmentParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Point p
                            └── ExpressionStatement
                                └── FieldAssignmentExpression .x =
                                    ├── VariableExpression p
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint("struct Point { num x; } none main() { Point p; p.x = 5; }"));
        }

        @Test
        void nestedFieldReadParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Box
                    │   └── Parameter Point inner
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Box b
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── FieldAccessExpression .inner
                                        └── VariableExpression b
                    """, parseAndPrint(
                    "struct Point { num x; } struct Box { Point inner; } none main() { Box b; b.inner.x; }"));
        }

        @Test
        void nestedFieldAssignmentParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Box
                    │   └── Parameter Point inner
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Box b
                            └── ExpressionStatement
                                └── FieldAssignmentExpression .x =
                                    ├── FieldAccessExpression .inner
                                    │   └── VariableExpression b
                                    └── LiteralExpression 5 (INT)
                    """, parseAndPrint(
                    "struct Point { num x; } struct Box { Point inner; } none main() { Box b; b.inner.x = 5; }"));
        }

        @Test
        void fieldReadOnArrayIndexResultParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Point[] arr
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── ArrayAccessExpression
                                        ├── VariableExpression arr
                                        └── LiteralExpression 0 (INT)
                    """, parseAndPrint("struct Point { num x; } none main() { Point[] arr; arr[0].x; }"));
        }

        @Test
        void fieldReadOnFunctionCallResultParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── FunctionDeclaration foo() -> Point
                    │   └── BlockStatement
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── FunctionCallExpression foo(0 args)
                    """, parseAndPrint("struct Point { num x; } Point foo() { } none main() { foo().x; }"));
        }

        @Test
        void fieldReadOnConstructionResultParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── NewStructExpression Point(1 args)
                                        └── LiteralExpression 1 (INT)
                    """, parseAndPrint("struct Point { num x; } none main() { new Point(1).x; }"));
        }

        @Test
        void fieldReadOnParenthesizedConstructionResultParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── ExpressionStatement
                                └── FieldAccessExpression .x
                                    └── GroupingExpression
                                        └── NewStructExpression Point(1 args)
                                            └── LiteralExpression 1 (INT)
                    """, parseAndPrint("struct Point { num x; } none main() { (new Point(1)).x; }"));
        }

        @Test
        void fieldReadYieldingAnArrayCanBeFurtherIndexed() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    ├── StructDeclaration Box
                    │   └── Parameter Point[] corners
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Box b
                            └── ExpressionStatement
                                └── ArrayAccessExpression
                                    ├── FieldAccessExpression .corners
                                    │   └── VariableExpression b
                                    └── LiteralExpression 0 (INT)
                    """, parseAndPrint(
                    "struct Point { num x; } struct Box { Point[] corners; } "
                            + "none main() { Box b; b.corners[0]; }"));
        }

        @Test
        void fieldNamedLenIsFieldAccessNotArrayLength() {
            // "b.len" (no parens) is a field access, even though "len" is the
            // one reserved dot-suffix — the trailing "(" is what selects
            // ArrayLengthExpression, not the identifier spelling alone.
            assertEquals("""
                    Program
                    ├── StructDeclaration Box
                    │   └── Parameter INT len
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Box b
                            └── ExpressionStatement
                                └── FieldAccessExpression .len
                                    └── VariableExpression b
                    """, parseAndPrint("struct Box { num len; } none main() { Box b; b.len; }"));
        }

        @Test
        void arrayDotLenStillParsesAsArrayLength() {
            // Regression: the loop-ified parseCall() must not disturb the
            // existing ".len()" shape, even in a program that also declares
            // structs.
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT[] arr
                            └── PrintStatement
                                └── ArrayLengthExpression
                                    └── VariableExpression arr
                    """, parseAndPrint(
                    "struct Point { num x; } none main() { num[] arr; show(arr.len()); }"));
        }

        @Test
        void dotWithNothingAfterIsAParseError() {
            Parser parser = new Parser(
                    new Lexer("struct Point { num x; } none main() { Point p; p.; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void callingAFieldAccessIsAParseError() {
            // "p.foo()" — no methods exist. ".foo" parses as a
            // FieldAccessExpression, and the loop's next iteration rejects
            // the "(" via the same "only a plain function name can be
            // called" check a plain call already uses.
            Parser parser = new Parser(
                    new Lexer("struct Point { num x; } none main() { Point p; p.foo(); }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void callingACallResultIsStillAParseError() {
            // "foo()()" — call-chaining stays unsupported; the loop's second
            // "(" is rejected by the same existing guard, just one token
            // later than before.
            Parser parser = new Parser(new Lexer("none foo() { } none main() { foo()(); }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void doubleIndexingNowParsesButIsNotASemanticGap() {
            // "arr[0][1]" — GopiLang has no nested array types, so this can
            // never type-check (SemanticAnalyzer's existing "cannot index
            // into non-array type" check rejects it unchanged); it's
            // accepted here as a deliberate, harmless side effect of the
            // suffix loop, not a new capability.
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration INT[] arr
                            └── ExpressionStatement
                                └── ArrayAccessExpression
                                    ├── ArrayAccessExpression
                                    │   ├── VariableExpression arr
                                    │   └── LiteralExpression 0 (INT)
                                    └── LiteralExpression 1 (INT)
                    """, parseAndPrint("none main() { num[] arr; arr[0][1]; }"));
        }

        @Test
        void chainedFieldAssignmentParses() {
            assertEquals("""
                    Program
                    ├── StructDeclaration Point
                    │   └── Parameter INT x
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            ├── VariableDeclaration Point a
                            ├── VariableDeclaration Point b
                            └── ExpressionStatement
                                └── FieldAssignmentExpression .x =
                                    ├── VariableExpression a
                                    └── FieldAssignmentExpression .x =
                                        ├── VariableExpression b
                                        └── LiteralExpression 5 (INT)
                    """, parseAndPrint(
                    "struct Point { num x; } none main() { Point a; Point b; a.x = b.x = 5; }"));
        }
    }

    // Nested blocks matter because they exercise parseBlock() and
    // parseStatement() calling each other recursively — the mutual-recursion
    // structure that made these two methods impossible to compile/test in
    // total isolation from each other in the first place.
    @Nested
    class NestedBlocks {

        @Test
        void blockNestedInsideAnotherBlock() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── BlockStatement
                                └── PrintStatement
                                    └── LiteralExpression 1 (INT)
                    """, parseAndPrint("none main() { { show(1); } }"));
        }

        @Test
        void whileBodyContainingAnIf() {
            String printed = parseAndPrint("none main() { loop (x > 0) { if (x == 5) { show(x); } } }");
            assertTrue(printed.contains("WhileStatement"));
            assertTrue(printed.contains("IfStatement"));
        }
    }

    // The dangling-else problem is its own category because it's a genuine
    // grammar ambiguity, not just another construct — these tests are the
    // executable proof that recursive descent resolves it correctly (nearest
    // unmatched if) purely from call-stack order, with no special-case code.
    @Nested
    class DanglingElse {

        @Test
        void elseAttachesToNearestIf() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── IfStatement
                                ├── condition
                                │   └── VariableExpression a
                                └── then
                                    └── IfStatement
                                        ├── condition
                                        │   └── VariableExpression b
                                        ├── then
                                        │   └── PrintStatement
                                        │       └── LiteralExpression 1 (INT)
                                        └── else
                                            └── PrintStatement
                                                └── LiteralExpression 2 (INT)
                    """, parseAndPrint("none main() { if (a) if (b) show(1); else show(2); }"));
        }

        @Test
        void bracesForceElseToTheOuterIf() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── IfStatement
                                ├── condition
                                │   └── VariableExpression a
                                ├── then
                                │   └── BlockStatement
                                │       └── IfStatement
                                │           ├── condition
                                │           │   └── VariableExpression b
                                │           └── then
                                │               └── PrintStatement
                                │                   └── LiteralExpression 1 (INT)
                                └── else
                                    └── PrintStatement
                                        └── LiteralExpression 2 (INT)
                    """, parseAndPrint("none main() { if (a) { if (b) show(1); } else show(2); }"));
        }
    }

    // Invalid syntax matters as a category distinct from "error recovery":
    // recovery tests ask "does parsing continue correctly afterward?"; these
    // ask the simpler prior question, "is a real, malformed program actually
    // detected as an error at all?"
    @Nested
    class InvalidSyntax {

        @Test
        void missingSemicolonIsReported() {
            Parser parser = new Parser(new Lexer("none main() { show(1) }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingClosingParenIsReported() {
            Parser parser = new Parser(new Lexer("none main() { show(1; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void invalidTokenWhereTypeExpectedIsReported() {
            Parser parser = new Parser(new Lexer("+ main() { }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void oldKeywordSpellingIsNowParsedAsAStructTypeName() {
            // "void" is retired by the v1.1 keyword migration — it now lexes
            // as a plain identifier. Before Milestone S2 that made it a parse
            // error here (no identifier was ever valid in type position); as
            // of S2, any identifier in type position is read as a struct
            // name, so this now parses as a function named "main" whose
            // declared return type is the (nonexistent) struct "void" —
            // rejected by semantic analysis's temporary struct-type stub
            // (SemanticAnalyzerTest), not by the parser.
            Parser parser = new Parser(new Lexer("void main() { }").scanTokens());
            parser.parseProgram();
            assertFalse(parser.reporter().hasErrors());
        }
    }
}
