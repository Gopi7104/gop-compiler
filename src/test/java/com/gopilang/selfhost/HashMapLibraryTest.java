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
import static org.junit.jupiter.api.Assertions.assertTrue;

// BRL v1.0 Phase 4 (HashMapTextToNum + HashSetOfText). Same differential
// harness pattern as VectorLibraryTest/TextUtilLibraryTest.
class HashMapLibraryTest {

    private static final Path VECTOR_TEXT = Path.of("selfhost/collections/vector_text.gopi");
    private static final Path VECTOR_NUM = Path.of("selfhost/collections/vector_num.gopi");
    private static final Path TEXT_UTILS = Path.of("selfhost/text/text_utils.gopi");
    private static final Path HASH_MAP = Path.of("selfhost/collections/hash_map_text_to_num.gopi");
    private static final Path HASH_SET = Path.of("selfhost/collections/hash_set_of_text.gopi");

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

    private static String runMap(String mainBody) throws IOException {
        return run(List.of(VECTOR_TEXT, VECTOR_NUM, TEXT_UTILS, HASH_MAP), mainBody);
    }

    private static String runSet(String mainBody) throws IOException {
        return run(List.of(VECTOR_TEXT, VECTOR_NUM, TEXT_UTILS, HASH_MAP, HASH_SET), mainBody);
    }

    @Nested
    class HashMapTests {

        @Test
        void emptyMapHasZeroCount() throws IOException {
            assertEquals("0\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    show(m.count);
                    """));
        }

        @Test
        void singleInsertIsRetrievable() throws IOException {
            assertEquals("1\ntrue\n1\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    show(m.count);
                    show(hashMapContains(m, "a"));
                    show(hashMapGet(m, "a"));
                    """));
        }

        @Test
        void overwritingExistingKeyUpdatesValueWithoutGrowingCount() throws IOException {
            assertEquals("1\n1\n2\n1\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    num countAfterFirst = m.count;
                    hashMapPut(m, "a", 2);
                    show(countAfterFirst);
                    show(m.count);
                    show(hashMapGet(m, "a"));
                    hashMapPut(m, "a", 3);
                    show(m.count);
                    """));
        }

        @Test
        void containsIsFalseForAnAbsentKey() throws IOException {
            assertEquals("false\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    show(hashMapContains(m, "nope"));
                    """));
        }

        @Test
        void removeMakesKeyAbsentButLeavesOtherKeysIntact() throws IOException {
            assertEquals("true\ntrue\nfalse\ntrue\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    hashMapPut(m, "b", 2);
                    show(hashMapContains(m, "a"));
                    show(hashMapContains(m, "b"));
                    hashMapRemove(m, "a");
                    show(hashMapContains(m, "a"));
                    show(hashMapContains(m, "b"));
                    """));
        }

        @Test
        void removingAnAbsentKeyIsANoOp() throws IOException {
            assertEquals("1\ntrue\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    hashMapRemove(m, "not-there");
                    show(m.count);
                    show(hashMapContains(m, "a"));
                    """));
        }

        @Test
        void clearRemovesEveryEntryAndResetsCount() throws IOException {
            assertEquals("0\nfalse\nfalse\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    hashMapPut(m, "b", 2);
                    hashMapClear(m);
                    show(m.count);
                    show(hashMapContains(m, "a"));
                    show(hashMapContains(m, "b"));
                    """));
        }

        @Test
        void clearedMapStaysUsable() throws IOException {
            assertEquals("42\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    hashMapClear(m);
                    hashMapPut(m, "z", 42);
                    show(hashMapGet(m, "z"));
                    """));
        }

        @Test
        void growthPreservesEveryEntryAcrossManyInserts() throws IOException {
            assertEquals("true\ntrue\ntrue\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    run (num i = 0; i < 100; i = i + 1) {
                        hashMapPut(m, numToText(i), i * 2);
                    }
                    show(m.count >= 100);
                    flag allPresent = yes;
                    run (num i = 0; i < 100; i = i + 1) {
                        if (!hashMapContains(m, numToText(i))) {
                            allPresent = no;
                        }
                    }
                    flag allCorrect = yes;
                    run (num i = 0; i < 100; i = i + 1) {
                        if (hashMapGet(m, numToText(i)) != i * 2) {
                            allCorrect = no;
                        }
                    }
                    show(allPresent);
                    show(allCorrect);
                    """));
        }

        @Test
        void loadFactorNeverReaches100PercentBeforeGrowing() throws IOException {
            // Capacity starts at 8; after exactly 5 inserts (5/8 = 62.5%,
            // below 70%) it must not have grown yet, confirming growth is
            // demand-driven, not eager.
            assertEquals("8\n8\n16\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    show(m.keys.count);
                    run (num i = 0; i < 5; i = i + 1) {
                        hashMapPut(m, numToText(i), i);
                    }
                    show(m.keys.count);
                    hashMapPut(m, "six", 6);
                    show(m.keys.count);
                    """));
        }

        @Test
        void manyRemovalsLeaveOnlySurvivingKeysPresent() throws IOException {
            assertEquals("true\ntrue\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    run (num i = 0; i < 60; i = i + 1) {
                        hashMapPut(m, numToText(i), i);
                    }
                    run (num i = 0; i < 60; i = i + 1) {
                        if (i % 2 == 0) {
                            hashMapRemove(m, numToText(i));
                        }
                    }
                    flag oddSurvive = yes;
                    run (num i = 1; i < 60; i = i + 2) {
                        if (!hashMapContains(m, numToText(i))) {
                            oddSurvive = no;
                        }
                    }
                    flag evenGone = yes;
                    run (num i = 0; i < 60; i = i + 2) {
                        if (hashMapContains(m, numToText(i))) {
                            evenGone = no;
                        }
                    }
                    show(oddSurvive);
                    show(evenGone);
                    """));
        }

        @Test
        void collidingKeysBothResolveCorrectlyUnderLinearProbing() throws IOException {
            // "Aa" and "BB" hash identically under a base-31 rolling hash
            // (both differ by exactly the same offset in their two chars'
            // codes summed with the same weighting) - a genuine, deliberate
            // collision, not a probabilistic one.
            assertEquals("true\n1\n2\n", runMap("""
                    num hA = hashMapHash("Aa", 1000003);
                    num hB = hashMapHash("BB", 1000003);
                    show(hA == hB);
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "Aa", 1);
                    hashMapPut(m, "BB", 2);
                    show(hashMapGet(m, "Aa"));
                    show(hashMapGet(m, "BB"));
                    """));
        }

        @Test
        void tombstoneSlotIsReusedByALaterInsert() throws IOException {
            assertEquals("false\n999\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "a", 1);
                    hashMapRemove(m, "a");
                    show(hashMapContains(m, "a"));
                    hashMapPut(m, "a", 999);
                    show(hashMapGet(m, "a"));
                    """));
        }

        @Test
        void heavyTombstoneChurnNeverCorruptsOrHangs() throws IOException {
            // Repeatedly inserting and removing the SAME key stresses the
            // exact failure mode a live-entry-only growth trigger would
            // hit: tombstones accumulating without bound until every probe
            // loop runs forever. This must complete and leave the map in a
            // fully correct, usable state.
            assertEquals("true\n7\n", runMap("""
                    HashMapTextToNum m = hashMapCreate();
                    hashMapPut(m, "keeper", 7);
                    run (num i = 0; i < 500; i = i + 1) {
                        hashMapPut(m, "churn", i);
                        hashMapRemove(m, "churn");
                    }
                    show(hashMapContains(m, "keeper"));
                    show(hashMapGet(m, "keeper"));
                    """));
        }

        @Test
        void independentMapsDoNotShareState() throws IOException {
            assertEquals("1\n2\n", runMap("""
                    HashMapTextToNum a = hashMapCreate();
                    HashMapTextToNum b = hashMapCreate();
                    hashMapPut(a, "x", 1);
                    hashMapPut(b, "x", 2);
                    show(hashMapGet(a, "x"));
                    show(hashMapGet(b, "x"));
                    """));
        }
    }

    @Nested
    class HashSetTests {

        @Test
        void emptySetContainsNothing() throws IOException {
            assertEquals("false\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    show(hashSetContains(s, "a"));
                    """));
        }

        @Test
        void addMakesValuePresent() throws IOException {
            assertEquals("true\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    hashSetAdd(s, "a");
                    show(hashSetContains(s, "a"));
                    """));
        }

        @Test
        void containsIsFalseForAnAbsentValue() throws IOException {
            assertEquals("false\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    hashSetAdd(s, "a");
                    show(hashSetContains(s, "z"));
                    """));
        }

        @Test
        void duplicateAddsDoNotGrowTheEntryCount() throws IOException {
            assertEquals("1\n1\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    hashSetAdd(s, "a");
                    show(s.entries.count);
                    hashSetAdd(s, "a");
                    show(s.entries.count);
                    """));
        }

        @Test
        void removeMakesValueAbsent() throws IOException {
            assertEquals("true\nfalse\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    hashSetAdd(s, "a");
                    show(hashSetContains(s, "a"));
                    hashSetRemove(s, "a");
                    show(hashSetContains(s, "a"));
                    """));
        }

        @Test
        void clearRemovesEverything() throws IOException {
            assertEquals("false\nfalse\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    hashSetAdd(s, "a");
                    hashSetAdd(s, "b");
                    hashSetClear(s);
                    show(hashSetContains(s, "a"));
                    show(hashSetContains(s, "b"));
                    """));
        }

        @Test
        void growthPreservesEveryMember() throws IOException {
            assertEquals("true\nfalse\n", runSet("""
                    HashSetOfText s = hashSetCreate();
                    run (num i = 0; i < 50; i = i + 1) {
                        hashSetAdd(s, numToText(i));
                    }
                    show(hashSetContains(s, "25"));
                    show(hashSetContains(s, "99"));
                    """));
        }
    }
}
