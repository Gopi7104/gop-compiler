package com.gopilang.parser;

import com.gopilang.ast.Program;
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
                    """, parseAndPrint("void main() { }"));
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
                    """, parseAndPrint("int add(int a, int b) { return a + b; }"));
        }

        @Test
        void everyPrimitiveReturnTypeParses() {
            String source = "int f1() { return 1; } float f2() { return 1.0; } "
                    + "bool f3() { return true; } string f4() { return \"x\"; } void f5() { }";
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
                    """, parseAndPrint("void main() { int x = 10; }"));
        }

        @Test
        void variableDeclarationWithoutInitializer() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── VariableDeclaration INT x
                    """, parseAndPrint("void main() { int x; }"));
        }

        @Test
        void returnWithValue() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration getFive() -> INT
                        └── BlockStatement
                            └── ReturnStatement
                                └── LiteralExpression 5 (INT)
                    """, parseAndPrint("int getFive() { return 5; }"));
        }

        @Test
        void returnWithoutValue() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration doNothing() -> VOID
                        └── BlockStatement
                            └── ReturnStatement
                    """, parseAndPrint("void doNothing() { return; }"));
        }

        @Test
        void printStatement() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── LiteralExpression 42 (INT)
                    """, parseAndPrint("void main() { print(42); }"));
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
            String source = "void main() { print(1); print(1.5); print(true); print(\"hi\"); }";
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
                    """, parseAndPrint("void main() { print((5)); }"));
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
                    """, parseAndPrint("void main() { print(-x); }"));
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
                    """, parseAndPrint("void main() { print(1 + 2 * 3); }"));
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
                    """, parseAndPrint("void main() { print(x < 5 && y); }"));
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
                    """, parseAndPrint("void main() { print((1 + 2) * 3); }"));
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
                    """, parseAndPrint("void main() { print(1 - 2 - 3); }"));
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
                    """, parseAndPrint("void main() { a = b = 5; print(a); }"));
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
                    """, parseAndPrint("void main() { print(- -x); }"));
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
                    """, parseAndPrint("void main() { print(add(1, 2)); }"));
        }

        @Test
        void callWithZeroArguments() {
            assertEquals("""
                    Program
                    └── FunctionDeclaration main() -> VOID
                        └── BlockStatement
                            └── PrintStatement
                                └── FunctionCallExpression now(0 args)
                    """, parseAndPrint("void main() { print(now()); }"));
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
                    """, parseAndPrint("void main() { { print(1); } }"));
        }

        @Test
        void whileBodyContainingAnIf() {
            String printed = parseAndPrint("void main() { while (x > 0) { if (x == 5) { print(x); } } }");
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
                    """, parseAndPrint("void main() { if (a) if (b) print(1); else print(2); }"));
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
                    """, parseAndPrint("void main() { if (a) { if (b) print(1); } else print(2); }"));
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
            Parser parser = new Parser(new Lexer("void main() { print(1) }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void missingClosingParenIsReported() {
            Parser parser = new Parser(new Lexer("void main() { print(1; }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }

        @Test
        void invalidTokenWhereTypeExpectedIsReported() {
            Parser parser = new Parser(new Lexer("+ main() { }").scanTokens());
            parser.parseProgram();
            assertTrue(parser.reporter().hasErrors());
        }
    }
}
