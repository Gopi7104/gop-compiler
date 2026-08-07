package com.gopilang.selfhost;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.errors.Diagnostic;
import com.gopilang.lexer.Lexer;
import com.gopilang.lexer.Token;
import com.gopilang.lexer.TokenType;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.vm.VirtualMachine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Project 2, Phase 1 (self-hosted Lexer). Behavioral differential harness:
 * both the real Java {@link Lexer} and the self-hosted lexer (compiled and
 * run by the completely unmodified Java pipeline) are observed only through
 * the same canonical text dump - never by comparing Java {@code Token}
 * objects against self-hosted internals directly. A token's dump is five
 * lines (kind number, lexeme, literal-or-empty, line, column); the dump
 * begins with the token count and diagnostic count, followed by three lines
 * per diagnostic (message, line, column). Text fields are escaped
 * (identically on both sides) so an embedded real newline from a `\n`
 * string escape can never be mistaken for a dump-format line break.
 */
class SelfhostLexerTest {

    private static final List<Path> LIBRARY = List.of(
            Path.of("selfhost/collections/vector_num.gopi"),
            Path.of("selfhost/collections/vector_text.gopi"),
            Path.of("selfhost/text/text_utils.gopi"),
            Path.of("selfhost/text/string_builder.gopi"),
            Path.of("selfhost/collections/hash_map_text_to_num.gopi"),
            Path.of("selfhost/diagnostics/diagnostic_buffer.gopi"),
            Path.of("selfhost/lexer/vector_dec.gopi"),
            Path.of("selfhost/lexer/lexer.gopi"));

    // Test-only scaffolding - never part of the compiler itself. Escapes
    // newline/tab/backslash/carriage-return in a text field before printing
    // it, purely so the line-per-field dump protocol can't be confused by a
    // literal newline coming from inside a STRING_LITERAL's escaped content.
    private static final String DUMP_HELPER = """
            text dumpEscapeText(text s) {
                text result = "";
                num length = textLength(s);
                num i = 0;
                text backslash = textFromCharCode(92);
                loop (i < length) {
                    num code = charCodeAt(s, i);
                    if (code == 10) {
                        result = result + backslash + "n";
                    } else if (code == 9) {
                        result = result + backslash + "t";
                    } else if (code == 13) {
                        result = result + backslash + "r";
                    } else if (code == 92) {
                        result = result + backslash + backslash;
                    } else {
                        result = result + textFromCharCode(code);
                    }
                    i = i + 1;
                }
                give result;
            }

            none dumpTokens(TokenStream stream, DiagnosticBuffer diagnostics) {
                show(stream.types.count);
                show(diagnostics.phases.count);
                num i = 0;
                loop (i < stream.types.count) {
                    num kind = stream.types.items[i];
                    show(kind);
                    show(dumpEscapeText(stream.lexemes.items[i]));
                    if (kind == tokenKindIntLiteral()) {
                        show(stream.literalNumbers.items[i]);
                    } else if (kind == tokenKindFloatLiteral()) {
                        show(stream.literalDecimals.items[i]);
                    } else if (kind == tokenKindBooleanLiteral()) {
                        show(stream.literalBools.items[i] == 1);
                    } else if (kind == tokenKindStringLiteral()) {
                        show(dumpEscapeText(stream.literalTexts.items[i]));
                    } else {
                        show("");
                    }
                    show(stream.lines.items[i]);
                    show(stream.columns.items[i]);
                    i = i + 1;
                }
                num j = 0;
                loop (j < diagnostics.phases.count) {
                    show(dumpEscapeText(diagnostics.messages.items[j]));
                    show(diagnostics.lines.items[j]);
                    show(diagnostics.columns.items[j]);
                    j = j + 1;
                }
            }
            """;

    private static final String MAIN = """
            none main() {
                text source = readFile(argAt(0));
                LexerState state = lexerCreate(source);
                lexerScanTokens(state);
                dumpTokens(state.tokens, state.diagnostics);
            }
            """;

    // Maps Java's real TokenType to the numeric kind code the self-hosted
    // lexer uses (selfhost/lexer/lexer.gopi's tokenKindXxx() functions),
    // assigned in the same order TokenType.java itself declares them.
    private static int kindOf(TokenType type) {
        return switch (type) {
            case INT_LITERAL -> 0;
            case FLOAT_LITERAL -> 1;
            case STRING_LITERAL -> 2;
            case BOOLEAN_LITERAL -> 3;
            case IDENTIFIER -> 4;
            case KW_INT -> 5;
            case KW_FLOAT -> 6;
            case KW_BOOL -> 7;
            case KW_STRING -> 8;
            case KW_VOID -> 9;
            case KW_IF -> 10;
            case KW_ELSE -> 11;
            case KW_WHILE -> 12;
            case KW_FOR -> 13;
            case KW_RETURN -> 14;
            case KW_PRINT -> 15;
            case KW_NEW -> 16;
            case KW_STRUCT -> 17;
            case PLUS -> 18;
            case MINUS -> 19;
            case STAR -> 20;
            case SLASH -> 21;
            case PERCENT -> 22;
            case ASSIGN -> 23;
            case EQUAL_EQUAL -> 24;
            case BANG_EQUAL -> 25;
            case LESS -> 26;
            case GREATER -> 27;
            case LESS_EQUAL -> 28;
            case GREATER_EQUAL -> 29;
            case AND_AND -> 30;
            case OR_OR -> 31;
            case BANG -> 32;
            case SEMICOLON -> 33;
            case COMMA -> 34;
            case DOT -> 35;
            case LEFT_PAREN -> 36;
            case RIGHT_PAREN -> 37;
            case LEFT_BRACE -> 38;
            case RIGHT_BRACE -> 39;
            case LEFT_BRACKET -> 40;
            case RIGHT_BRACKET -> 41;
            case EOF -> 42;
        };
    }

    private static String escapeForDump(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String javaDump(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scanTokens();
        List<Diagnostic> diagnostics = lexer.reporter().diagnostics();
        StringBuilder sb = new StringBuilder();
        sb.append(tokens.size()).append('\n');
        sb.append(diagnostics.size()).append('\n');
        for (Token t : tokens) {
            sb.append(kindOf(t.type())).append('\n');
            sb.append(escapeForDump(t.lexeme())).append('\n');
            sb.append(t.literal() == null ? "" : escapeForDump(String.valueOf(t.literal()))).append('\n');
            sb.append(t.location().line()).append('\n');
            sb.append(t.location().column()).append('\n');
        }
        for (Diagnostic d : diagnostics) {
            sb.append(escapeForDump(d.message())).append('\n');
            sb.append(d.range().start().line()).append('\n');
            sb.append(d.range().start().column()).append('\n');
        }
        return sb.toString();
    }

    private static String selfhostDump(Path sourceFile) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path lib : LIBRARY) {
            combined.append(Files.readString(lib)).append('\n');
        }
        combined.append(DUMP_HELPER).append('\n').append(MAIN);
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors compiling the self-hosted lexer itself");
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors compiling the self-hosted lexer itself");
        BytecodeModule module = new CodeGenerator(program, model).generate();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            new VirtualMachine(module, List.of(sourceFile.toString())).run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    private static void assertDifferential(String source, Path fileWithThatContent) throws IOException {
        String expected = javaDump(source);
        String actual = selfhostDump(fileWithThatContent);
        assertEquals(expected, actual, "self-hosted lexer diverged from Java's Lexer for source:\n" + source);
    }

    @Nested
    class HandWrittenCases {

        @TempDir
        Path tempDir;

        private void check(String source) throws IOException {
            Path file = tempDir.resolve("case.gopi");
            Files.writeString(file, source);
            assertDifferential(source, file);
        }

        @Test
        void emptyFile() throws IOException {
            check("");
        }

        @Test
        void whitespaceOnly() throws IOException {
            check("   \t\t  \n\n   \r\n  ");
        }

        @Test
        void commentsOnly() throws IOException {
            check("// a comment\n// another one\n// no trailing newline after this");
        }

        @Test
        void allKeywords() throws IOException {
            check("num dec flag text none if else loop run give show new struct yes no");
        }

        @Test
        void plainIdentifiers() throws IOException {
            check("x foo_bar _leading camelCase123 a1 _ __");
        }

        @Test
        void keywordPrefixedIdentifiersAreNotMisrecognized() throws IOException {
            check("ifx elseVar loopCount forEach newValue structure yesable noNo");
        }

        @Test
        void integerLiterals() throws IOException {
            check("0 42 2147483647 007");
        }

        @Test
        void integerOverflowAtBoundary() throws IOException {
            check("2147483647 2147483648 99999999999");
        }

        @Test
        void decimalLiterals() throws IOException {
            check("0.5 3.14 123.456 2147483647.5 0.0");
        }

        @Test
        void trailingDotWithoutDigitIsIntThenDot() throws IOException {
            // Must lex as INT_LITERAL "5" + DOT, never as a float - Java's
            // own scanNumber only treats '.' as part of the number when a
            // digit follows it.
            check("5. 5.x");
        }

        @Test
        void stringLiterals() throws IOException {
            check("\"hello\" \"\" \"with spaces and 123\"");
        }

        @Test
        void allEscapeSequences() throws IOException {
            check("\"a\\nb\\tc\\\"d\\\\e\"");
        }

        @Test
        void invalidEscapeSequenceIsRecoverable() throws IOException {
            check("\"before\\xafter\" identifier_after_still_lexes");
        }

        @Test
        void unterminatedStringAtNewline() throws IOException {
            check("\"never closed\nnum x = 1;");
        }

        @Test
        void unterminatedStringAtEndOfFile() throws IOException {
            check("\"never closed");
        }

        @Test
        void unterminatedStringWithTrailingBackslash() throws IOException {
            check("\"never closed\\");
        }

        @Test
        void lineAndColumnTrackingAcrossMultipleLines() throws IOException {
            check("num x\n  = 5;\nnum y = 10;\n\nnum z\t= 1;");
        }

        @Test
        void allOperators() throws IOException {
            check("+ - * / % = == != < > <= >= && || !");
        }

        @Test
        void allPunctuation() throws IOException {
            check("; , . ( ) { } [ ]");
        }

        @Test
        void lineComment() throws IOException {
            check("num x = 1; // trailing comment\nnum y = 2;");
        }

        @Test
        void divisionIsNotMistakenForComment() throws IOException {
            check("num x = 10 / 2;");
        }

        @Test
        void invalidCharactersRecoverAndContinueLexing() throws IOException {
            check("num x @ = 5; # num y $ = 6;");
        }

        @Test
        void bitwiseAmpersandAndPipeAreDiagnosedNotSilentlyAccepted() throws IOException {
            check("a & b | c && d || e");
        }

        @Test
        void eofOnNonEmptySourceWithNoTrailingNewline() throws IOException {
            check("num x = 1;");
        }

        @Test
        void largeInputExercisesTokenStreamGrowth() throws IOException {
            StringBuilder src = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                src.append("num v").append(i).append(" = ").append(i).append(";\n");
            }
            check(src.toString());
        }

        @Test
        void mixedRecoveryManyBadCharactersInOneFile() throws IOException {
            check("@ # $ ` num x = 1; @ @ @ show(x);");
        }
    }

    @Nested
    class ExampleCorpus {

        @Test
        void everyExampleMatchesJavaLexer() throws IOException {
            checkAll(Path.of("examples"));
        }

        @Test
        void everySemanticExampleMatchesJavaLexer() throws IOException {
            checkAll(Path.of("examples/semantic"));
        }

        private void checkAll(Path directory) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                List<Path> gopiFiles = files.filter(p -> p.toString().endsWith(".gopi")).sorted().toList();
                assertFalse(gopiFiles.isEmpty(), "expected at least one .gopi file in " + directory);
                for (Path file : gopiFiles) {
                    String source = Files.readString(file);
                    assertDifferential(source, file);
                }
            }
        }
    }
}
