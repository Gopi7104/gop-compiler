package com.gopilang.lexer;

import com.gopilang.errors.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    private static List<TokenType> types(List<Token> tokens) {
        return tokens.stream().map(Token::type).toList();
    }

    @Test
    void workedExampleFromDesignDiscussion() {
        Lexer lexer = new Lexer("num x = 10;\nshow(x + 5);");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(
                List.of(TokenType.KW_INT, TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL,
                        TokenType.SEMICOLON, TokenType.KW_PRINT, TokenType.LEFT_PAREN, TokenType.IDENTIFIER,
                        TokenType.PLUS, TokenType.INT_LITERAL, TokenType.RIGHT_PAREN, TokenType.SEMICOLON,
                        TokenType.EOF),
                types(tokens));

        Token intLiteralTen = tokens.get(3);
        assertEquals(10, intLiteralTen.literal());
        assertEquals(new com.gopilang.util.SourceLocation(1, 9), intLiteralTen.location());

        Token printKeyword = tokens.get(5);
        assertEquals(new com.gopilang.util.SourceLocation(2, 1), printKeyword.location());
    }

    @Test
    void digitsFollowedByLettersIsTwoValidTokens() {
        Lexer lexer = new Lexer("123abc");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(List.of(TokenType.INT_LITERAL, TokenType.IDENTIFIER, TokenType.EOF), types(tokens));
        assertEquals(123, tokens.get(0).literal());
        assertEquals("abc", tokens.get(1).lexeme());
    }

    @Test
    void dotAfterAFloatLiteralIsItsOwnTokenNotAnotherDecimalPoint() {
        // Since GopiLang v2 added '.' as a real operator (array.len()), a
        // second decimal point right after a float literal is no longer a
        // lexical error — the lexer only ever consumes ONE '.' into a
        // number (scanNumber's own peek()/peekNext() lookahead), so a
        // second one here is simply the start of a new, separate token.
        // Whether "12.34.56" means anything is a parser/semantic question,
        // not a lexical one.
        Lexer lexer = new Lexer("12.34.56");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(
                List.of(TokenType.FLOAT_LITERAL, TokenType.DOT, TokenType.INT_LITERAL, TokenType.EOF),
                types(tokens));
        assertEquals(12.34, tokens.get(0).literal());
        assertEquals(56, tokens.get(2).literal());
    }

    @Test
    void unterminatedStringIsReportedAtItsOpeningQuote() {
        Lexer lexer = new Lexer("x = \"hello");
        lexer.scanTokens();

        List<Diagnostic> diagnostics = lexer.reporter().diagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals(1, diagnostics.get(0).range().start().line());
        assertEquals(5, diagnostics.get(0).range().start().column());
    }

    @Test
    void unexpectedCharacterRecoversAndKeepsLexing() {
        Lexer lexer = new Lexer("x = @ 5;");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(1, lexer.reporter().diagnostics().size());
        assertEquals(
                List.of(TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL, TokenType.SEMICOLON,
                        TokenType.EOF),
                types(tokens));
    }

    @Test
    void loneAmpersandAndPipeAreErrorsButDoubleFormsAreNot() {
        Lexer lexer = new Lexer("a & b || c");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(1, lexer.reporter().diagnostics().size());
        assertEquals(
                List.of(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.OR_OR, TokenType.IDENTIFIER,
                        TokenType.EOF),
                types(tokens));
    }

    @Test
    void twoCharOperatorsUseMaximalMunch() {
        Lexer lexer = new Lexer("== != <= >= && ||");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(
                List.of(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL, TokenType.LESS_EQUAL,
                        TokenType.GREATER_EQUAL, TokenType.AND_AND, TokenType.OR_OR, TokenType.EOF),
                types(tokens));
    }

    @Test
    void validEscapeSequencesDecodeCorrectly() {
        Lexer lexer = new Lexer("\"line1\\nline2\\ttabbed\"");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals("line1\nline2\ttabbed", tokens.get(0).literal());
    }

    @Test
    void unknownEscapeReportsOneDiagnosticAndStillProducesAValidToken() {
        Lexer lexer = new Lexer("\"bad \\q escape\"");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(1, lexer.reporter().diagnostics().size());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
        assertEquals(TokenType.EOF, tokens.get(1).type());
    }

    @Test
    void multipleUnknownEscapesInOneStringEachReportSeparately() {
        Lexer lexer = new Lexer("\"a \\q b \\z c\"");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(2, lexer.reporter().diagnostics().size());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
    }

    @Test
    void unknownEscapeFollowedByGenuinelyUnterminatedStringReportsBoth() {
        Lexer lexer = new Lexer("\"oops \\q");
        lexer.scanTokens();

        assertEquals(2, lexer.reporter().diagnostics().size());
    }

    @Test
    void emptySourceProducesOnlyEof() {
        Lexer lexer = new Lexer("");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(List.of(TokenType.EOF), types(tokens));
        assertFalse(lexer.reporter().hasErrors());
    }

    @Test
    void integerLiteralTooLargeIsReported() {
        Lexer lexer = new Lexer("99999999999999");
        lexer.scanTokens();

        assertTrue(lexer.reporter().hasErrors());
        assertEquals(1, lexer.reporter().diagnostics().size());
    }

    @Test
    void keywordsAreNotIdentifiers() {
        Lexer lexer = new Lexer("num numx yes no");
        List<Token> tokens = lexer.scanTokens();

        assertEquals(
                List.of(TokenType.KW_INT, TokenType.IDENTIFIER, TokenType.BOOLEAN_LITERAL,
                        TokenType.BOOLEAN_LITERAL, TokenType.EOF),
                types(tokens));
        assertEquals(Boolean.TRUE, tokens.get(2).literal());
        assertEquals(Boolean.FALSE, tokens.get(3).literal());
    }

    @Test
    void commentsProduceNoTokens() {
        Lexer lexer = new Lexer("num x = 1; // trailing comment\nnum y = 2;");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(
                List.of(TokenType.KW_INT, TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL,
                        TokenType.SEMICOLON, TokenType.KW_INT, TokenType.IDENTIFIER, TokenType.ASSIGN,
                        TokenType.INT_LITERAL, TokenType.SEMICOLON, TokenType.EOF),
                types(tokens));
    }

    @Test
    void oldKeywordSpellingsAreNowPlainIdentifiers() {
        // The v1.1 keyword migration retired these spellings entirely — the
        // lexer has no memory of them, so they lex as ordinary identifiers.
        Lexer lexer = new Lexer("int float bool string void while return print true false");
        List<Token> tokens = lexer.scanTokens();

        assertFalse(lexer.reporter().hasErrors());
        assertEquals(
                List.of(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.IDENTIFIER,
                        TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.IDENTIFIER,
                        TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.EOF),
                types(tokens));
    }
}
