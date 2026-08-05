package com.gopilang.parser;

import com.gopilang.ast.FunctionDeclaration;
import com.gopilang.ast.Program;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.ErrorPhase;
import com.gopilang.lexer.Lexer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserRecoveryTest {

    private static Parser parserFor(String source) {
        return new Parser(new Lexer(source).scanTokens());
    }

    private static List<String> functionNames(Program program) {
        return program.functions().stream().map(FunctionDeclaration::name).collect(Collectors.toList());
    }

    @Test
    void errorRightBeforeBlockClosingBraceDoesNotSwallowTheBrace() {
        // Regression test: synchronize() must not consume a '}' that
        // parseBlock()'s own loop is waiting to see, or parsing runs on into
        // whatever follows the block.
        Parser parser = parserFor("int add(int a, int b) { return a + } void main() { print(1); }");
        Program program = parser.parseProgram();

        assertEquals(List.of("add", "main"), functionNames(program));
        assertEquals(1, parser.reporter().diagnostics().size());
    }

    @Test
    void midBlockStatementErrorRecoversWithSiblingStatementsIntact() {
        Parser parser = parserFor("void main() { int x = 1; int y = ; int z = 3; }");
        Program program = parser.parseProgram();

        assertEquals(List.of("main"), functionNames(program));
        assertEquals(2, program.functions().get(0).body().statements().size());
        assertEquals(1, parser.reporter().diagnostics().size());
    }

    @Test
    void multipleIndependentErrorsAcrossFunctionsAreAllReported() {
        // Regression test: a stray '}' with no enclosing block waiting for it
        // (broken1's malformed parameter list) must not hang synchronize().
        Parser parser = parserFor("int broken1( { } void main() { print(1); } int broken2() { return + ; }");
        Program program = parser.parseProgram();

        assertEquals(List.of("main", "broken2"), functionNames(program));
        assertEquals(2, parser.reporter().diagnostics().size());
        for (Diagnostic d : parser.reporter().diagnostics()) {
            assertEquals(ErrorPhase.SYNTAX, d.phase());
        }
    }

    @Test
    void synchronizeMakesProgressPastATokenThatIsValidButUnexpectedHere() {
        // ')' is a perfectly valid token lexically — the lexer never touches
        // it — but it can't start a statement, so this exercises the general
        // "no special case matches, just advance and keep looking" path.
        Parser parser = parserFor("void main() { ) print(1); } void after() { print(2); }");
        Program program = parser.parseProgram();

        assertEquals(List.of("main", "after"), functionNames(program));
        assertTrue(parser.reporter().hasErrors());
    }

    @Test
    void consumeErrorNamesTheTokenThatWasActuallyFound() {
        Parser parser = parserFor("void main() { print(1) }"); // missing ';'
        parser.parseProgram();

        assertEquals(1, parser.reporter().diagnostics().size());
        String message = parser.reporter().diagnostics().get(0).message();
        assertTrue(message.contains("found"), "expected message to name the found token: " + message);
    }

    @Test
    void consumeErrorAtEndOfFileUsesEofPhrasingNotAnEmptyLexeme() {
        Parser parser = parserFor("void main() { print(1);"); // missing '}'
        parser.parseProgram();

        assertTrue(parser.reporter().hasErrors());
        String message = parser.reporter().diagnostics().get(0).message();
        assertTrue(message.contains("end of file"), "expected EOF phrasing: " + message);
    }
}
