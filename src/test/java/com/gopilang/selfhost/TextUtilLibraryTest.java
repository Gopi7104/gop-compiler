package com.gopilang.selfhost;

import com.gopilang.ast.Program;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.vm.VirtualMachine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// BRL v1.0 Phase 3 (Text Utilities + StringBuilder). Same differential-style
// harness as VectorLibraryTest: read the real selfhost/*.gopi source off
// disk, append a test-specific main(), run through the completely
// unmodified Java pipeline.
class TextUtilLibraryTest {

    private static final Path TEXT_UTILS = Path.of("selfhost/text/text_utils.gopi");
    private static final Path VECTOR_NUM = Path.of("selfhost/collections/vector_num.gopi");
    private static final Path STRING_BUILDER = Path.of("selfhost/text/string_builder.gopi");

    private static String run(List<Path> librarySources, String mainBody) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path source : librarySources) {
            combined.append(Files.readString(source)).append('\n');
        }
        combined.append("none main() {\n").append(mainBody).append("\n}\n");
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors for:\n" + source);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors for:\n" + source);
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

    private static String runTextUtils(String mainBody) throws IOException {
        return run(List.of(TEXT_UTILS), mainBody);
    }

    private static String runStringBuilder(String mainBody) throws IOException {
        return run(List.of(VECTOR_NUM, TEXT_UTILS, STRING_BUILDER), mainBody);
    }

    @Nested
    class CharacterClassification {

        @Test
        void isDigitClassifiesDigitsAndRejectsLetters() throws IOException {
            assertEquals("true\ntrue\nfalse\nfalse\n", runTextUtils("""
                    show(isDigit(48));
                    show(isDigit(57));
                    show(isDigit(65));
                    show(isDigit(47));
                    """));
        }

        @Test
        void isAlphaClassifiesLettersAndUnderscoreOnly() throws IOException {
            assertEquals("true\ntrue\ntrue\nfalse\nfalse\n", runTextUtils("""
                    show(isAlpha(97));
                    show(isAlpha(90));
                    show(isAlpha(95));
                    show(isAlpha(48));
                    show(isAlpha(32));
                    """));
        }

        @Test
        void isAlphaNumericIsAlphaOrDigit() throws IOException {
            assertEquals("true\ntrue\nfalse\n", runTextUtils("""
                    show(isAlphaNumeric(65));
                    show(isAlphaNumeric(53));
                    show(isAlphaNumeric(32));
                    """));
        }

        @Test
        void isWhitespaceExcludesNewline() throws IOException {
            assertEquals("true\ntrue\ntrue\nfalse\n", runTextUtils("""
                    show(isWhitespace(32));
                    show(isWhitespace(9));
                    show(isWhitespace(13));
                    show(isWhitespace(10));
                    """));
        }

        @Test
        void isNewlineIsExactlyLineFeed() throws IOException {
            assertEquals("true\nfalse\n", runTextUtils("""
                    show(isNewline(10));
                    show(isNewline(13));
                    """));
        }
    }

    @Nested
    class TextOperations {

        @Test
        void substringExtractsHalfOpenRange() throws IOException {
            assertEquals("hello\nworld\n\n", runTextUtils("""
                    show(substring("hello world", 0, 5));
                    show(substring("hello world", 6, 11));
                    show(substring("hello world", 3, 3));
                    """));
        }

        @Test
        void repeatConcatenatesCountTimes() throws IOException {
            assertEquals("ababab\n\n^\n", runTextUtils("""
                    show(repeat("ab", 3));
                    show(repeat("^", 0));
                    show(repeat("^", 1));
                    """));
        }

        @Test
        void reverseTextReversesCharacterOrder() throws IOException {
            assertEquals("olleh\n\na\n", runTextUtils("""
                    show(reverseText("hello"));
                    show(reverseText(""));
                    show(reverseText("a"));
                    """));
        }

        @Test
        void numToTextHandlesZeroPositiveAndNegative() throws IOException {
            assertEquals("0\n42\n-17\n-1\n", runTextUtils("""
                    show(numToText(0));
                    show(numToText(42));
                    show(numToText(-17));
                    show(numToText(-1));
                    """));
        }

        @Test
        void numToTextRoundTripsThroughReverseTextAndSubstring() throws IOException {
            // Exercises numToText + substring + reverseText together, the
            // same combination diagnostic rendering relies on.
            assertEquals("123\n321\n", runTextUtils("""
                    text s = numToText(123);
                    show(s);
                    show(reverseText(s));
                    """));
        }
    }

    @Nested
    class StringBuilderTests {

        @Test
        void emptyBuilderBuildsEmptyText() throws IOException {
            assertEquals("\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void appendChar() throws IOException {
            assertEquals("A\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    stringBuilderAppendChar(sb, 65);
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void appendText() throws IOException {
            assertEquals("hello\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    stringBuilderAppendText(sb, "hello");
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void appendNum() throws IOException {
            assertEquals("count=42\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    stringBuilderAppendText(sb, "count=");
                    stringBuilderAppendNum(sb, 42);
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void mixedAppendsBuildInOrder() throws IOException {
            assertEquals("A-bc-42\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    stringBuilderAppendChar(sb, 65);
                    stringBuilderAppendText(sb, "-bc-");
                    stringBuilderAppendNum(sb, 42);
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void clearResetsAndBuilderStaysUsable() throws IOException {
            assertEquals("abc\n\nxyz\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    stringBuilderAppendText(sb, "abc");
                    show(stringBuilderBuild(sb));
                    stringBuilderClear(sb);
                    show(stringBuilderBuild(sb));
                    stringBuilderAppendText(sb, "xyz");
                    show(stringBuilderBuild(sb));
                    """));
        }

        @Test
        void largeBuilderAcrossManyGrowthCycles() throws IOException {
            assertEquals("1000\n", runStringBuilder("""
                    StringBuilder sb = stringBuilderCreate();
                    run (num i = 0; i < 1000; i = i + 1) {
                        stringBuilderAppendChar(sb, 65);
                    }
                    text built = stringBuilderBuild(sb);
                    show(textLength(built));
                    """));
        }

        @Test
        void independentBuildersDoNotShareState() throws IOException {
            assertEquals("a\nb\n", runStringBuilder("""
                    StringBuilder a = stringBuilderCreate();
                    StringBuilder b = stringBuilderCreate();
                    stringBuilderAppendText(a, "a");
                    stringBuilderAppendText(b, "b");
                    show(stringBuilderBuild(a));
                    show(stringBuilderBuild(b));
                    """));
        }
    }
}
