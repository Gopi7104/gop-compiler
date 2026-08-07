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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// BRL v1.0 Phase 2 (Vector library). There is no Java production code to
// test here - selfhost/collections/*.gopi IS the thing under test. This
// harness reads the real library source off disk, appends a test-specific
// main(), and runs it through the completely unmodified Java pipeline
// (Lexer -> Parser -> SemanticAnalyzer -> CodeGenerator -> VirtualMachine),
// the same one every other test suite in this repo already exercises.
// Nothing about GopiLang itself changes for this milestone; only new library
// source and this harness exist.
class VectorLibraryTest {

    private static final Path VECTOR_NUM_SOURCE = Path.of("selfhost/collections/vector_num.gopi");
    private static final Path VECTOR_TEXT_SOURCE = Path.of("selfhost/collections/vector_text.gopi");

    private static String runWithLibrary(Path librarySource, String mainBody) throws IOException {
        String library = Files.readString(librarySource);
        String source = library + "\nnone main() {\n" + mainBody + "\n}\n";

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

    private static String runVectorNum(String mainBody) throws IOException {
        return runWithLibrary(VECTOR_NUM_SOURCE, mainBody);
    }

    private static String runVectorText(String mainBody) throws IOException {
        return runWithLibrary(VECTOR_TEXT_SOURCE, mainBody);
    }

    @Nested
    class VectorNumTests {

        @Test
        void emptyVectorHasZeroCount() throws IOException {
            assertEquals("0\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    show(v.count);
                    """));
        }

        @Test
        void singlePush() throws IOException {
            assertEquals("1\n42\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    vectorNumPush(v, 42);
                    show(v.count);
                    show(v.items[0]);
                    """));
        }

        @Test
        void multiplePushesPreserveOrder() throws IOException {
            assertEquals("3\n10\n20\n30\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    vectorNumPush(v, 10);
                    vectorNumPush(v, 20);
                    vectorNumPush(v, 30);
                    show(v.count);
                    show(v.items[0]);
                    show(v.items[1]);
                    show(v.items[2]);
                    """));
        }

        @Test
        void automaticGrowthDoublesCapacityOnlyWhenFull() throws IOException {
            // 4 pushes exactly fill the initial capacity-4 backing array
            // without growing; the 5th push must grow it before inserting.
            assertEquals("4\n4\n8\n5\n1\n5\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    num initialCapacity = v.items.len();
                    vectorNumPush(v, 1);
                    vectorNumPush(v, 2);
                    vectorNumPush(v, 3);
                    vectorNumPush(v, 4);
                    num capacityBeforeGrowth = v.items.len();
                    vectorNumPush(v, 5);
                    num capacityAfterGrowth = v.items.len();
                    show(initialCapacity);
                    show(capacityBeforeGrowth);
                    show(capacityAfterGrowth);
                    show(v.count);
                    show(v.items[0]);
                    show(v.items[4]);
                    """));
        }

        @Test
        void copyIsIndependentOfOriginal() throws IOException {
            assertEquals("2\n3\n99\n1\n3\n", runVectorNum("""
                    VectorNum a = vectorNumCreate();
                    vectorNumPush(a, 1);
                    vectorNumPush(a, 2);
                    VectorNum b = vectorNumCopy(a);
                    vectorNumPush(b, 3);
                    a.items[0] = 99;
                    show(a.count);
                    show(b.count);
                    show(a.items[0]);
                    show(b.items[0]);
                    show(b.items[2]);
                    """));
        }

        @Test
        void clearResetsCountAndKeepsTheVectorUsable() throws IOException {
            assertEquals("0\n1\n100\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    vectorNumPush(v, 1);
                    vectorNumPush(v, 2);
                    vectorNumClear(v);
                    show(v.count);
                    vectorNumPush(v, 100);
                    show(v.count);
                    show(v.items[0]);
                    """));
        }

        @Test
        void popReturnsLastPushedValueLifoOrder() throws IOException {
            assertEquals("3\n2\n1\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    vectorNumPush(v, 1);
                    vectorNumPush(v, 2);
                    vectorNumPush(v, 3);
                    num popped1 = vectorNumPop(v);
                    num popped2 = vectorNumPop(v);
                    show(popped1);
                    show(popped2);
                    show(v.count);
                    """));
        }

        @Test
        void manyGrowthCyclesPreserveEveryElement() throws IOException {
            // 4 -> 8 -> 16 -> 32 -> 64 -> 128 across 100 pushes.
            assertEquals("100\n128\n0\n50\n99\n", runVectorNum("""
                    VectorNum v = vectorNumCreate();
                    run (num i = 0; i < 100; i = i + 1) {
                        vectorNumPush(v, i);
                    }
                    show(v.count);
                    show(v.items.len());
                    show(v.items[0]);
                    show(v.items[50]);
                    show(v.items[99]);
                    """));
        }

        @Test
        void independentVectorsDoNotShareState() throws IOException {
            assertEquals("2\n1\n1\n100\n", runVectorNum("""
                    VectorNum a = vectorNumCreate();
                    VectorNum b = vectorNumCreate();
                    vectorNumPush(a, 1);
                    vectorNumPush(a, 2);
                    vectorNumPush(b, 100);
                    show(a.count);
                    show(b.count);
                    show(a.items[0]);
                    show(b.items[0]);
                    """));
        }
    }

    @Nested
    class VectorTextTests {

        @Test
        void emptyVectorHasZeroCount() throws IOException {
            assertEquals("0\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    show(v.count);
                    """));
        }

        @Test
        void singlePush() throws IOException {
            assertEquals("1\nhello\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    vectorTextPush(v, "hello");
                    show(v.count);
                    show(v.items[0]);
                    """));
        }

        @Test
        void multiplePushesPreserveOrder() throws IOException {
            assertEquals("3\na\nb\nc\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    vectorTextPush(v, "a");
                    vectorTextPush(v, "b");
                    vectorTextPush(v, "c");
                    show(v.count);
                    show(v.items[0]);
                    show(v.items[1]);
                    show(v.items[2]);
                    """));
        }

        @Test
        void automaticGrowthDoublesCapacityOnlyWhenFull() throws IOException {
            assertEquals("4\n4\n8\n5\ne\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    num initialCapacity = v.items.len();
                    vectorTextPush(v, "a");
                    vectorTextPush(v, "b");
                    vectorTextPush(v, "c");
                    vectorTextPush(v, "d");
                    num capacityBeforeGrowth = v.items.len();
                    vectorTextPush(v, "e");
                    num capacityAfterGrowth = v.items.len();
                    show(initialCapacity);
                    show(capacityBeforeGrowth);
                    show(capacityAfterGrowth);
                    show(v.count);
                    show(v.items[4]);
                    """));
        }

        @Test
        void copyIsIndependentOfOriginal() throws IOException {
            assertEquals("2\n3\nchanged\nx\nz\n", runVectorText("""
                    VectorText a = vectorTextCreate();
                    vectorTextPush(a, "x");
                    vectorTextPush(a, "y");
                    VectorText b = vectorTextCopy(a);
                    vectorTextPush(b, "z");
                    a.items[0] = "changed";
                    show(a.count);
                    show(b.count);
                    show(a.items[0]);
                    show(b.items[0]);
                    show(b.items[2]);
                    """));
        }

        @Test
        void clearResetsCountAndKeepsTheVectorUsable() throws IOException {
            assertEquals("0\n1\nnew\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    vectorTextPush(v, "a");
                    vectorTextPush(v, "b");
                    vectorTextClear(v);
                    show(v.count);
                    vectorTextPush(v, "new");
                    show(v.count);
                    show(v.items[0]);
                    """));
        }

        @Test
        void popReturnsLastPushedValueLifoOrder() throws IOException {
            assertEquals("c\nb\n1\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    vectorTextPush(v, "a");
                    vectorTextPush(v, "b");
                    vectorTextPush(v, "c");
                    text popped1 = vectorTextPop(v);
                    text popped2 = vectorTextPop(v);
                    show(popped1);
                    show(popped2);
                    show(v.count);
                    """));
        }

        @Test
        void manyGrowthCyclesPreserveEveryElement() throws IOException {
            assertEquals("100\n128\neven\nodd\neven\nodd\n", runVectorText("""
                    VectorText v = vectorTextCreate();
                    run (num i = 0; i < 100; i = i + 1) {
                        if (i % 2 == 0) {
                            vectorTextPush(v, "even");
                        } else {
                            vectorTextPush(v, "odd");
                        }
                    }
                    show(v.count);
                    show(v.items.len());
                    show(v.items[0]);
                    show(v.items[1]);
                    show(v.items[98]);
                    show(v.items[99]);
                    """));
        }

        @Test
        void independentVectorsDoNotShareState() throws IOException {
            assertEquals("2\n1\nfirst\nother\n", runVectorText("""
                    VectorText a = vectorTextCreate();
                    VectorText b = vectorTextCreate();
                    vectorTextPush(a, "first");
                    vectorTextPush(a, "second");
                    vectorTextPush(b, "other");
                    show(a.count);
                    show(b.count);
                    show(a.items[0]);
                    show(b.items[0]);
                    """));
        }
    }
}
