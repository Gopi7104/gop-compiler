package com.gopilang.selfhost;

import com.gopilang.ast.ArrayAccessExpression;
import com.gopilang.ast.ArrayLengthExpression;
import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BlockStatement;
import com.gopilang.ast.Expr;
import com.gopilang.ast.ExpressionStatement;
import com.gopilang.ast.FieldAccessExpression;
import com.gopilang.ast.FieldAssignmentExpression;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.FunctionDeclaration;
import com.gopilang.ast.GroupingExpression;
import com.gopilang.ast.IfStatement;
import com.gopilang.ast.IndexAssignmentExpression;
import com.gopilang.ast.LiteralExpression;
import com.gopilang.ast.NewArrayExpression;
import com.gopilang.ast.NewStructExpression;
import com.gopilang.ast.PrintStatement;
import com.gopilang.ast.Program;
import com.gopilang.ast.ReturnStatement;
import com.gopilang.ast.Stmt;
import com.gopilang.ast.UnaryExpression;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.errors.Diagnostic;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.FunctionSymbol;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.semantic.VariableSymbol;
import com.gopilang.types.TypeRef;
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
 * Project 3, Phase 4 (self-hosted Semantic Analyzer). Same differential
 * harness shape as {@link SelfhostParserTest}: both the real Java {@link
 * SemanticAnalyzer} and the self-hosted analyzer (compiled and run by the
 * completely unmodified Java pipeline) are observed only through a
 * canonical, flat, one-value-per-line pre-order dump — never by comparing
 * internal table layouts, row ids, or arena node ids directly. Per the
 * approved architecture revision, only OBSERVABLE facts are dumped: a
 * resolved declaration is identified by its own name/type/range (never a
 * raw id), a resolved function by its own name/return type/parameter
 * types, and every expression's computed type as a plain (elem, isArray,
 * structName) value. A match here proves the two implementations agree on
 * every diagnostic, every resolution, and every computed type, without
 * requiring either side to number anything the same way internally.
 */
class SelfhostSemanticAnalyzerTest {

    private static final List<Path> LIBRARY = List.of(
            Path.of("selfhost/collections/vector_num.gopi"),
            Path.of("selfhost/collections/vector_text.gopi"),
            Path.of("selfhost/text/text_utils.gopi"),
            Path.of("selfhost/text/string_builder.gopi"),
            Path.of("selfhost/collections/hash_map_text_to_num.gopi"),
            Path.of("selfhost/diagnostics/diagnostic_buffer.gopi"),
            Path.of("selfhost/lexer/vector_dec.gopi"),
            Path.of("selfhost/lexer/lexer.gopi"),
            Path.of("selfhost/ast/ast_arena.gopi"),
            Path.of("selfhost/parser/parser.gopi"),
            Path.of("selfhost/semantic/type_rules.gopi"),
            Path.of("selfhost/semantic/tables.gopi"),
            Path.of("selfhost/semantic/scope.gopi"),
            Path.of("selfhost/semantic/semantic_analyzer.gopi"));

    // Node-kind numbering mirrors selfhost/ast/ast_arena.gopi's own
    // nodeKindXxx() functions exactly (only the 14 Expr kinds are needed
    // here - see SelfhostParserTest for the full inventory).
    private static int kindOf(Class<?> exprClass) {
        if (exprClass == LiteralExpression.class) return 11;
        if (exprClass == VariableExpression.class) return 12;
        if (exprClass == GroupingExpression.class) return 13;
        if (exprClass == UnaryExpression.class) return 14;
        if (exprClass == BinaryExpression.class) return 15;
        if (exprClass == AssignmentExpression.class) return 16;
        if (exprClass == FunctionCallExpression.class) return 17;
        if (exprClass == NewArrayExpression.class) return 18;
        if (exprClass == ArrayAccessExpression.class) return 19;
        if (exprClass == ArrayLengthExpression.class) return 20;
        if (exprClass == IndexAssignmentExpression.class) return 21;
        if (exprClass == NewStructExpression.class) return 22;
        if (exprClass == FieldAccessExpression.class) return 23;
        if (exprClass == FieldAssignmentExpression.class) return 24;
        throw new IllegalArgumentException("unhandled expr class: " + exprClass);
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

    // ------------------------------------------------------------------
    // Java-side canonical dump
    // ------------------------------------------------------------------

    private static void dumpTypeOf(StringBuilder sb, SemanticModel model, Expr expr) {
        TypeRef type = model.expressionTypes().get(expr);
        if (type == null) {
            sb.append(-1).append('\n').append(0).append('\n').append('\n');
        } else {
            sb.append(type.elementType().ordinal()).append('\n');
            sb.append(type.isArray() ? 1 : 0).append('\n');
            sb.append(escapeForDump(type.structName().orElse(""))).append('\n');
        }
    }

    private static void dumpTypeRef(StringBuilder sb, TypeRef type) {
        sb.append(type.elementType().ordinal()).append('\n');
        sb.append(type.isArray() ? 1 : 0).append('\n');
        sb.append(escapeForDump(type.structName().orElse(""))).append('\n');
    }

    private static void dumpExpr(StringBuilder sb, SemanticModel model, Expr expr) {
        sb.append(kindOf(expr.getClass())).append('\n');
        dumpTypeOf(sb, model, expr);

        if (expr instanceof VariableExpression || expr instanceof AssignmentExpression) {
            VariableSymbol symbol = (expr instanceof VariableExpression v)
                    ? model.variableResolutions().get(v)
                    : model.assignmentTargetResolutions().get((AssignmentExpression) expr);
            if (symbol == null) {
                sb.append(0).append('\n');
            } else {
                sb.append(1).append('\n');
                sb.append(escapeForDump(symbol.name())).append('\n');
                dumpTypeRef(sb, symbol.type());
                sb.append(symbol.declaredAt().start().line()).append('\n');
                sb.append(symbol.declaredAt().start().column()).append('\n');
                sb.append(symbol.declaredAt().end().line()).append('\n');
                sb.append(symbol.declaredAt().end().column()).append('\n');
            }
        } else if (expr instanceof FunctionCallExpression call) {
            FunctionSymbol callee = model.callResolutions().get(call);
            if (callee == null) {
                sb.append(0).append('\n');
            } else {
                sb.append(1).append('\n');
                sb.append(escapeForDump(callee.name())).append('\n');
                dumpTypeRef(sb, callee.returnType());
                sb.append(callee.parameterTypes().size()).append('\n');
                for (TypeRef pt : callee.parameterTypes()) {
                    dumpTypeRef(sb, pt);
                }
            }
        }

        switch (expr) {
            case LiteralExpression literal -> { }
            case VariableExpression variable -> { }
            case GroupingExpression grouping -> dumpExpr(sb, model, grouping.inner());
            case UnaryExpression unary -> dumpExpr(sb, model, unary.operand());
            case BinaryExpression binary -> {
                dumpExpr(sb, model, binary.left());
                dumpExpr(sb, model, binary.right());
            }
            case AssignmentExpression assignment -> dumpExpr(sb, model, assignment.value());
            case FunctionCallExpression call -> {
                for (Expr arg : call.arguments()) {
                    dumpExpr(sb, model, arg);
                }
            }
            case NewArrayExpression newArray -> dumpExpr(sb, model, newArray.size());
            case ArrayAccessExpression access -> {
                dumpExpr(sb, model, access.array());
                dumpExpr(sb, model, access.index());
            }
            case ArrayLengthExpression length -> dumpExpr(sb, model, length.array());
            case IndexAssignmentExpression indexAssignment -> {
                dumpExpr(sb, model, indexAssignment.array());
                dumpExpr(sb, model, indexAssignment.index());
                dumpExpr(sb, model, indexAssignment.value());
            }
            case NewStructExpression construction -> {
                for (Expr arg : construction.arguments()) {
                    dumpExpr(sb, model, arg);
                }
            }
            case FieldAccessExpression fieldAccess -> dumpExpr(sb, model, fieldAccess.target());
            case FieldAssignmentExpression fieldAssign -> {
                dumpExpr(sb, model, fieldAssign.target());
                dumpExpr(sb, model, fieldAssign.value());
            }
        }
    }

    private static void dumpStmt(StringBuilder sb, SemanticModel model, Stmt stmt) {
        switch (stmt) {
            case BlockStatement block -> {
                for (Stmt s : block.statements()) {
                    dumpStmt(sb, model, s);
                }
            }
            case ExpressionStatement exprStmt -> dumpExpr(sb, model, exprStmt.expression());
            case VariableDeclaration decl -> decl.initializer().ifPresent(init -> dumpExpr(sb, model, init));
            case IfStatement ifStmt -> {
                dumpExpr(sb, model, ifStmt.condition());
                dumpStmt(sb, model, ifStmt.thenBranch());
                ifStmt.elseBranch().ifPresent(e -> dumpStmt(sb, model, e));
            }
            case WhileStatement whileStmt -> {
                dumpExpr(sb, model, whileStmt.condition());
                dumpStmt(sb, model, whileStmt.body());
            }
            case ReturnStatement returnStmt -> returnStmt.value().ifPresent(v -> dumpExpr(sb, model, v));
            case PrintStatement printStmt -> dumpExpr(sb, model, printStmt.value());
        }
    }

    private static void dumpProgram(StringBuilder sb, SemanticModel model, Program program) {
        for (FunctionDeclaration f : program.functions()) {
            dumpStmt(sb, model, f.body());
        }
    }

    private static String javaDump(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        StringBuilder sb = new StringBuilder();
        dumpProgram(sb, model, program);
        sb.append(DIAGNOSTICS_MARKER).append('\n');
        List<Diagnostic> diagnostics = analyzer.reporter().diagnostics();
        sb.append(diagnostics.size()).append('\n');
        for (Diagnostic d : diagnostics) {
            sb.append(escapeForDump(d.phase().label())).append('\n');
            sb.append(escapeForDump(d.message())).append('\n');
            sb.append(d.range().start().line()).append('\n');
            sb.append(d.range().start().column()).append('\n');
        }
        return sb.toString();
    }

    // Separates the AST-shaped portion of the dump (whose traversal order is
    // already deterministic - List-based iteration on both sides) from the
    // diagnostics portion, whose relative order can legitimately differ:
    // detectStructCycles() (both the real Java implementation and the
    // self-hosted port) iterates its struct table's keys, and Java's
    // HashMap.keySet() iteration order is a hash-bucket accident of
    // java.util.HashMap, not documented or stable behavior - confirmed by
    // examples/semantic/cyclic_structs.gopi's own header comment ("which of
    // the two is named depends on registration order, not something this
    // example relies on"). Canonicalizing (sorting) only the diagnostics
    // portion before comparison verifies the SET of diagnostics matches
    // exactly - same count, same phase/message/position for each - without
    // asserting an incidental Java collections implementation detail as if
    // it were observable behavior.
    private static final String DIAGNOSTICS_MARKER = "---DIAGNOSTICS---";

    private static String canonicalizeDiagnosticsOrder(String dump) {
        int markerIndex = dump.indexOf(DIAGNOSTICS_MARKER + "\n");
        String astPart = dump.substring(0, markerIndex);
        String[] rest = dump.substring(markerIndex + DIAGNOSTICS_MARKER.length() + 1).split("\n", -1);
        int count = Integer.parseInt(rest[0]);
        List<String[]> records = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int base = 1 + i * 4;
            records.add(new String[] {rest[base], rest[base + 1], rest[base + 2], rest[base + 3]});
        }
        records.sort(java.util.Comparator
                .comparingInt((String[] r) -> Integer.parseInt(r[2]))
                .thenComparingInt(r -> Integer.parseInt(r[3]))
                .thenComparing(r -> r[0])
                .thenComparing(r -> r[1]));
        StringBuilder sb = new StringBuilder(astPart);
        sb.append(DIAGNOSTICS_MARKER).append('\n');
        sb.append(count).append('\n');
        for (String[] r : records) {
            sb.append(r[0]).append('\n').append(r[1]).append('\n').append(r[2]).append('\n').append(r[3]).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Self-hosted-side canonical dump
    // ------------------------------------------------------------------

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

            none dumpExpr(AstArena a, SemanticTables tab, num id) {
                num kind = arenaKind(a, id);
                show(kind);
                show(tab.typeElemByNode.items[id]);
                show(tab.typeIsArrayByNode.items[id]);
                show(dumpEscapeText(tab.typeStructNameByNode.items[id]));

                if (kind == nodeKindVariableExpression() || kind == nodeKindAssignmentExpression()) {
                    num declNodeId = tab.resolvedDeclByNode.items[id];
                    if (declNodeId == -1) {
                        show(0);
                    } else {
                        show(1);
                        show(dumpEscapeText(arenaText0(a, declNodeId)));
                        show(arenaTypeElem(a, declNodeId));
                        show(arenaTypeIsArray(a, declNodeId));
                        show(dumpEscapeText(arenaTypeStructName(a, declNodeId)));
                        show(arenaStartLine(a, declNodeId));
                        show(arenaStartCol(a, declNodeId));
                        show(arenaEndLine(a, declNodeId));
                        show(arenaEndCol(a, declNodeId));
                    }
                } else if (kind == nodeKindFunctionCallExpression()) {
                    num functionRow = tab.resolvedFunctionByNode.items[id];
                    if (functionRow == -1) {
                        show(0);
                    } else {
                        show(1);
                        num declNodeId = tab.functions.declNodeIds.items[functionRow];
                        show(dumpEscapeText(tab.functions.names.items[functionRow]));
                        show(functionReturnElem(a, declNodeId));
                        show(functionReturnIsArray(a, declNodeId));
                        show(dumpEscapeText(functionReturnStructName(a, declNodeId)));
                        num paramCount = functionParamCount(a, declNodeId);
                        show(paramCount);
                        num pi = 0;
                        loop (pi < paramCount) {
                            show(functionParamElemAt(a, declNodeId, pi));
                            show(functionParamIsArrayAt(a, declNodeId, pi));
                            show(dumpEscapeText(functionParamStructNameAt(a, declNodeId, pi)));
                            pi = pi + 1;
                        }
                    }
                }

                if (kind == nodeKindGroupingExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindUnaryExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindBinaryExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpExpr(a, tab, arenaChild2(a, id));
                } else if (kind == nodeKindAssignmentExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindFunctionCallExpression()) {
                    num argc = arenaListCount(a, id);
                    num i = 0;
                    loop (i < argc) {
                        dumpExpr(a, tab, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindNewArrayExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindArrayAccessExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpExpr(a, tab, arenaChild2(a, id));
                } else if (kind == nodeKindArrayLengthExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindIndexAssignmentExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpExpr(a, tab, arenaChild2(a, id));
                    dumpExpr(a, tab, arenaChild3(a, id));
                } else if (kind == nodeKindNewStructExpression()) {
                    num argc2 = arenaListCount(a, id);
                    num j = 0;
                    loop (j < argc2) {
                        dumpExpr(a, tab, arenaListAt(a, id, j));
                        j = j + 1;
                    }
                } else if (kind == nodeKindFieldAccessExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindFieldAssignmentExpression()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpExpr(a, tab, arenaChild2(a, id));
                }
            }

            none dumpStmt(AstArena a, SemanticTables tab, num id) {
                num kind = arenaKind(a, id);
                if (kind == nodeKindBlockStatement()) {
                    num n = arenaListCount(a, id);
                    num i = 0;
                    loop (i < n) {
                        dumpStmt(a, tab, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindExpressionStatement()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                } else if (kind == nodeKindVariableDeclaration()) {
                    num initId = arenaChild1(a, id);
                    if (initId != -1) {
                        dumpExpr(a, tab, initId);
                    }
                } else if (kind == nodeKindIfStatement()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpStmt(a, tab, arenaChild2(a, id));
                    num elseId = arenaChild3(a, id);
                    if (elseId != -1) {
                        dumpStmt(a, tab, elseId);
                    }
                } else if (kind == nodeKindWhileStatement()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                    dumpStmt(a, tab, arenaChild2(a, id));
                } else if (kind == nodeKindReturnStatement()) {
                    num valueId = arenaChild1(a, id);
                    if (valueId != -1) {
                        dumpExpr(a, tab, valueId);
                    }
                } else if (kind == nodeKindPrintStatement()) {
                    dumpExpr(a, tab, arenaChild1(a, id));
                }
            }

            none dumpProgram(AstArena a, SemanticTables tab, num programId) {
                num fc = arenaListCount(a, programId);
                num i = 0;
                loop (i < fc) {
                    num functionId = arenaListAt(a, programId, i);
                    dumpStmt(a, tab, arenaChild1(a, functionId));
                    i = i + 1;
                }
            }

            none dumpDiagnostics(DiagnosticBuffer d) {
                show(d.phases.count);
                num i = 0;
                loop (i < d.phases.count) {
                    show(dumpEscapeText(d.phases.items[i]));
                    show(dumpEscapeText(d.messages.items[i]));
                    show(d.lines.items[i]);
                    show(d.columns.items[i]);
                    i = i + 1;
                }
            }
            """;

    private static final String MAIN = """
            none main() {
                text source = readFile(argAt(0));
                LexerState lstate = lexerCreate(source);
                TokenStream tokens = lexerScanTokens(lstate);
                AstArena arena = arenaCreate();
                DiagnosticBuffer parseDiags = diagnosticBufferCreate();
                ParserState pstate = parserCreate(tokens, arena, parseDiags);
                num programId = parseProgram(pstate);
                DiagnosticBuffer semDiags = diagnosticBufferCreate();
                SemanticTables tab = analyze(arena, programId, semDiags);
                dumpProgram(arena, tab, programId);
                show("---DIAGNOSTICS---");
                dumpDiagnostics(semDiags);
            }
            """;

    private static String selfhostDump(Path sourceFile) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path lib : LIBRARY) {
            combined.append(Files.readString(lib)).append('\n');
        }
        combined.append(DUMP_HELPER).append('\n').append(MAIN);
        String source = combined.toString();

        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors compiling the self-hosted semantic-analyzer bundle itself");
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors compiling the self-hosted semantic-analyzer bundle itself");
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
        String expected = canonicalizeDiagnosticsOrder(javaDump(source));
        String actual = canonicalizeDiagnosticsOrder(selfhostDump(fileWithThatContent));
        assertEquals(expected, actual, "self-hosted semantic analyzer diverged from Java's SemanticAnalyzer for source:\n" + source);
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
        void minimalValidProgram() throws IOException {
            check("none main() { }");
        }

        @Test
        void duplicateFunctions() throws IOException {
            check("num add(num a, num b) { give a + b; } num add(num a) { give a; } none main() { }");
        }

        @Test
        void duplicateFunctionCollidesWithBuiltin() throws IOException {
            check("num readFile(num x) { give x; } none main() { }");
        }

        @Test
        void duplicateVariablesSameScope() throws IOException {
            check("none main() { num x = 1; num x = 2; show(x); }");
        }

        @Test
        void duplicateParameters() throws IOException {
            check("num f(num a, num a) { give a; } none main() { }");
        }

        @Test
        void undefinedVariableRead() throws IOException {
            check("none main() { show(missing); }");
        }

        @Test
        void undefinedVariableAssignmentTarget() throws IOException {
            check("none main() { missing = 5; }");
        }

        @Test
        void undefinedFunctionCall() throws IOException {
            check("none main() { missing(1, 2); }");
        }

        @Test
        void shadowingAcrossNestedBlock() throws IOException {
            check("none main() { num x = 1; { num x = 2; show(x); } show(x); }");
        }

        @Test
        void noShadowingWithinSameScope() throws IOException {
            check("none main() { num x = 1; num y = x + 1; show(y); }");
        }

        @Test
        void assignmentCompatibilityWidening() throws IOException {
            check("none main() { dec d = 5; show(d); }");
        }

        @Test
        void assignmentCompatibilityRejected() throws IOException {
            check("none main() { num x = yes; }");
        }

        @Test
        void assignmentCompatibilityStructNominal() throws IOException {
            check("struct A { num x; } struct B { num x; } none main() { A a = new A(1); B b = new B(1); a = b; }");
        }

        @Test
        void returnCompatibilityWidening() throws IOException {
            check("dec f() { give 5; } none main() { show(f()); }");
        }

        @Test
        void returnCompatibilityMismatch() throws IOException {
            check("num f() { give yes; } none main() { }");
        }

        @Test
        void argumentCountMismatchTooFew() throws IOException {
            check("num add(num a, num b) { give a + b; } none main() { show(add(1)); }");
        }

        @Test
        void argumentCountMismatchTooMany() throws IOException {
            check("num add(num a, num b) { give a + b; } none main() { show(add(1, 2, 3)); }");
        }

        @Test
        void argumentTypeMismatch() throws IOException {
            check("num add(num a, num b) { give a + b; } none main() { show(add(1, yes)); }");
        }

        @Test
        void boolConditionIfRejectsNonBool() throws IOException {
            check("none main() { if (1) { show(1); } }");
        }

        @Test
        void boolConditionWhileRejectsNonBool() throws IOException {
            check("none main() { loop (1) { show(1); } }");
        }

        @Test
        void reachabilityMissingReturnInBranch() throws IOException {
            check("num f(flag c) { if (c) { give 1; } } none main() { }");
        }

        @Test
        void reachabilityReturnsViaWhileTrue() throws IOException {
            check("num f() { loop (yes) { give 1; } } none main() { }");
        }

        @Test
        void reachabilityReturnsViaIfElse() throws IOException {
            check("num f(flag c) { if (c) { give 1; } else { give 2; } } none main() { }");
        }

        @Test
        void definiteAssignmentIfWithElseBothBranches() throws IOException {
            check("none main() { num x; if (yes) { x = 1; } else { x = 2; } show(x); }");
        }

        @Test
        void definiteAssignmentIfWithoutElse() throws IOException {
            check("none main() { num x; if (yes) { x = 1; } show(x); }");
        }

        @Test
        void definiteAssignmentWhileConservative() throws IOException {
            check("none main() { num x; loop (yes) { x = 1; } show(x); }");
        }

        @Test
        void poisonPropagationNoCascadeFromUndefined() throws IOException {
            check("none main() { num y = missing + 1; }");
        }

        @Test
        void poisonPropagationNoCascadeFromUnassigned() throws IOException {
            check("none main() { num x; num y = x + 1; }");
        }

        @Test
        void mainValidationMissing() throws IOException {
            check("num f() { give 1; }");
        }

        @Test
        void mainValidationWrongSignatureReturnType() throws IOException {
            check("num main() { give 1; }");
        }

        @Test
        void mainValidationWrongSignatureParameters() throws IOException {
            check("none main(num x) { }");
        }

        @Test
        void arraysValidAccessAndAssignment() throws IOException {
            check("none main() { num[] arr = new num[5]; arr[0] = 1; num x = arr[0]; num len = arr.len(); show(len); }");
        }

        @Test
        void arraysIndexMustBeNum() throws IOException {
            check("none main() { num[] arr = new num[5]; show(arr[yes]); }");
        }

        @Test
        void arraysLenOnNonArray() throws IOException {
            check("none main() { num x = 5; show(x.len()); }");
        }

        @Test
        void arraysElementTypeCannotBeVoid() throws IOException {
            check("none main() { none[] arr; }");
        }

        @Test
        void structsFieldDuplicate() throws IOException {
            check("struct Point { num x; num x; } none main() { }");
        }

        @Test
        void structsDuplicateDeclaration() throws IOException {
            check("struct Point { num x; } struct Point { num y; } none main() { }");
        }

        @Test
        void structsDirectCycle() throws IOException {
            check("struct Node { Node next; } none main() { }");
        }

        @Test
        void structsIndirectCycle() throws IOException {
            check("struct A { B b; } struct B { A a; } none main() { }");
        }

        @Test
        void structsCycleThroughArrayIsLegal() throws IOException {
            check("struct Tree { Tree[] children; } none main() { }");
        }

        @Test
        void structTypedDeclarationForwardReference() throws IOException {
            check("none main() { Later l = new Later(1); } struct Later { num x; }");
        }

        @Test
        void newStructExpressionsArgumentCountAndType() throws IOException {
            check("struct Point { num x; num y; } none main() { Point p = new Point(1, 2); show(p.x); }");
        }

        @Test
        void fieldAccessOnNonStruct() throws IOException {
            check("none main() { num x = 5; show(x.y); }");
        }

        @Test
        void fieldAccessUnknownField() throws IOException {
            check("struct Point { num x; } none main() { Point p = new Point(1); show(p.y); }");
        }

        @Test
        void fieldAccessChaining() throws IOException {
            check("struct Point { num x; num y; } struct Box { Point corner; } "
                    + "none main() { Box b = new Box(new Point(1, 2)); b.corner.x = 5; show(b.corner.x); }");
        }

        @Test
        void builtinsResolveAndTypeCheck() throws IOException {
            check("none main() { text s = readFile(argAt(0)); num n = charCodeAt(s, 0); "
                    + "text t = textFromCharCode(n); num len = textLength(s); num c = argCount(); show(len + c); }");
        }

        @Test
        void builtinArgumentTypeMismatch() throws IOException {
            check("none main() { num n = charCodeAt(1, 2); }");
        }

        @Test
        void undefinedStructConstruction() throws IOException {
            check("none main() { NotAStruct n = new NotAStruct(); }");
        }

        @Test
        void unaryOperatorInvalidOperand() throws IOException {
            check("none main() { text s = \"hi\"; flag r = !s; }");
        }

        @Test
        void binaryOperatorInvalidOperands() throws IOException {
            check("struct Point { num x; } none main() { Point p = new Point(1); Point q = new Point(2); flag r = p == q; }");
        }

        @Test
        void printRejectsStruct() throws IOException {
            check("struct Point { num x; } none main() { Point p = new Point(1); show(p); }");
        }

        @Test
        void chainedFieldAssignmentTypesCorrectly() throws IOException {
            check("struct Point { num x; } none main() { Point a = new Point(0); Point b = new Point(0); a.x = b.x = 5; }");
        }
    }

    @Nested
    class ExampleCorpus {

        @Test
        void everyExampleMatchesJavaSemanticAnalyzer() throws IOException {
            checkAll(Path.of("examples"));
        }

        @Test
        void everySemanticExampleMatchesJavaSemanticAnalyzer() throws IOException {
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
