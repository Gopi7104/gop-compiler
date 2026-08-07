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
import com.gopilang.ast.Parameter;
import com.gopilang.ast.PrintStatement;
import com.gopilang.ast.Program;
import com.gopilang.ast.ReturnStatement;
import com.gopilang.ast.Stmt;
import com.gopilang.ast.StructDeclaration;
import com.gopilang.ast.UnaryExpression;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.CodeGenerator;
import com.gopilang.errors.Diagnostic;
import com.gopilang.lexer.Lexer;
import com.gopilang.parser.Parser;
import com.gopilang.semantic.SemanticAnalyzer;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.types.PrimitiveType;
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
 * Project 2, Phases 2 &amp; 3 (self-hosted AST Arena + recursive-descent
 * Parser). Same behavioral differential harness shape as
 * {@link SelfhostLexerTest}: both the real Java {@link Parser} and the
 * self-hosted parser (compiled and run by the completely unmodified Java
 * pipeline) are observed only through the same canonical, flat, one-value-
 * per-line pre-order dump of the resulting AST — never by comparing Java AST
 * records against arena internals directly. Node identity is never
 * compared (the arena's dense ids and Java's object identity have no
 * correspondence worth asserting); only the dump's *content* — kind codes,
 * source ranges, and every field/child in a fixed pre-order sequence — is
 * compared, so a match here proves the two trees are structurally and
 * positionally identical without requiring the self-hosted arena to
 * reproduce any particular id numbering.
 */
class SelfhostParserTest {

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
            Path.of("selfhost/parser/parser.gopi"));

    // Node-kind numbering mirrors selfhost/ast/ast_arena.gopi's own
    // nodeKindXxx() functions exactly (see that file's declared order).
    private static int kindOf(Class<?> nodeClass) {
        if (nodeClass == Program.class) return 0;
        if (nodeClass == Parameter.class) return 1;
        if (nodeClass == FunctionDeclaration.class) return 2;
        if (nodeClass == StructDeclaration.class) return 3;
        if (nodeClass == BlockStatement.class) return 4;
        if (nodeClass == ExpressionStatement.class) return 5;
        if (nodeClass == VariableDeclaration.class) return 6;
        if (nodeClass == IfStatement.class) return 7;
        if (nodeClass == WhileStatement.class) return 8;
        if (nodeClass == ReturnStatement.class) return 9;
        if (nodeClass == PrintStatement.class) return 10;
        if (nodeClass == LiteralExpression.class) return 11;
        if (nodeClass == VariableExpression.class) return 12;
        if (nodeClass == GroupingExpression.class) return 13;
        if (nodeClass == UnaryExpression.class) return 14;
        if (nodeClass == BinaryExpression.class) return 15;
        if (nodeClass == AssignmentExpression.class) return 16;
        if (nodeClass == FunctionCallExpression.class) return 17;
        if (nodeClass == NewArrayExpression.class) return 18;
        if (nodeClass == ArrayAccessExpression.class) return 19;
        if (nodeClass == ArrayLengthExpression.class) return 20;
        if (nodeClass == IndexAssignmentExpression.class) return 21;
        if (nodeClass == NewStructExpression.class) return 22;
        if (nodeClass == FieldAccessExpression.class) return 23;
        if (nodeClass == FieldAssignmentExpression.class) return 24;
        throw new IllegalArgumentException("unhandled node class: " + nodeClass);
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
    // Java-side canonical dump: walks the real AST in the exact pre-order
    // the self-hosted dumper (see DUMP_HELPER below) also walks the arena
    // in, emitting one value per line.
    // ------------------------------------------------------------------

    private static void dumpType(StringBuilder sb, TypeRef type) {
        sb.append(type.structName().isPresent() ? PrimitiveType.BOOL.ordinal() : type.elementType().ordinal()).append('\n');
        sb.append(type.isArray() ? 1 : 0).append('\n');
        sb.append(escapeForDump(type.structName().orElse(""))).append('\n');
    }

    private static void dumpNodeHeader(StringBuilder sb, Class<?> nodeClass, com.gopilang.util.SourceRange range) {
        sb.append(kindOf(nodeClass)).append('\n');
        sb.append(range.start().line()).append('\n');
        sb.append(range.start().column()).append('\n');
        sb.append(range.end().line()).append('\n');
        sb.append(range.end().column()).append('\n');
    }

    private static void dumpProgram(StringBuilder sb, Program program) {
        dumpNodeHeader(sb, Program.class, program.range());
        sb.append(program.functions().size()).append('\n');
        for (FunctionDeclaration f : program.functions()) {
            dumpFunction(sb, f);
        }
        sb.append(program.structs().size()).append('\n');
        for (StructDeclaration s : program.structs()) {
            dumpStruct(sb, s);
        }
    }

    private static void dumpParameter(StringBuilder sb, Parameter p) {
        dumpNodeHeader(sb, Parameter.class, p.range());
        dumpType(sb, p.type());
        sb.append(escapeForDump(p.name())).append('\n');
    }

    private static void dumpFunction(StringBuilder sb, FunctionDeclaration f) {
        dumpNodeHeader(sb, FunctionDeclaration.class, f.range());
        dumpType(sb, f.returnType());
        sb.append(escapeForDump(f.name())).append('\n');
        sb.append(f.parameters().size()).append('\n');
        for (Parameter param : f.parameters()) {
            dumpParameter(sb, param);
        }
        dumpStatement(sb, f.body());
    }

    private static void dumpStruct(StringBuilder sb, StructDeclaration s) {
        dumpNodeHeader(sb, StructDeclaration.class, s.range());
        sb.append(escapeForDump(s.name())).append('\n');
        sb.append(s.fields().size()).append('\n');
        for (Parameter field : s.fields()) {
            dumpParameter(sb, field);
        }
    }

    private static void dumpStatement(StringBuilder sb, Stmt stmt) {
        switch (stmt) {
            case BlockStatement block -> {
                dumpNodeHeader(sb, BlockStatement.class, block.range());
                sb.append(block.statements().size()).append('\n');
                for (Stmt s : block.statements()) {
                    dumpStatement(sb, s);
                }
            }
            case ExpressionStatement exprStmt -> {
                dumpNodeHeader(sb, ExpressionStatement.class, exprStmt.range());
                dumpExpr(sb, exprStmt.expression());
            }
            case VariableDeclaration decl -> {
                dumpNodeHeader(sb, VariableDeclaration.class, decl.range());
                dumpType(sb, decl.type());
                sb.append(escapeForDump(decl.name())).append('\n');
                sb.append(decl.initializer().isPresent() ? 1 : 0).append('\n');
                decl.initializer().ifPresent(e -> dumpExpr(sb, e));
            }
            case IfStatement ifStmt -> {
                dumpNodeHeader(sb, IfStatement.class, ifStmt.range());
                sb.append(ifStmt.elseBranch().isPresent() ? 1 : 0).append('\n');
                dumpExpr(sb, ifStmt.condition());
                dumpStatement(sb, ifStmt.thenBranch());
                ifStmt.elseBranch().ifPresent(s -> dumpStatement(sb, s));
            }
            case WhileStatement whileStmt -> {
                dumpNodeHeader(sb, WhileStatement.class, whileStmt.range());
                dumpExpr(sb, whileStmt.condition());
                dumpStatement(sb, whileStmt.body());
            }
            case ReturnStatement returnStmt -> {
                dumpNodeHeader(sb, ReturnStatement.class, returnStmt.range());
                sb.append(returnStmt.value().isPresent() ? 1 : 0).append('\n');
                returnStmt.value().ifPresent(e -> dumpExpr(sb, e));
            }
            case PrintStatement printStmt -> {
                dumpNodeHeader(sb, PrintStatement.class, printStmt.range());
                dumpExpr(sb, printStmt.value());
            }
        }
    }

    private static void dumpExpr(StringBuilder sb, Expr expr) {
        switch (expr) {
            case LiteralExpression literal -> {
                dumpNodeHeader(sb, LiteralExpression.class, literal.range());
                sb.append(literal.type().ordinal()).append('\n');
                switch (literal.type()) {
                    case INT -> sb.append((int) literal.value()).append('\n');
                    case FLOAT -> sb.append((double) literal.value()).append('\n');
                    case BOOL -> sb.append(((boolean) literal.value()) ? 1 : 0).append('\n');
                    case STRING -> sb.append(escapeForDump((String) literal.value())).append('\n');
                    case VOID -> throw new IllegalStateException("literal cannot be VOID");
                }
            }
            case VariableExpression var -> {
                dumpNodeHeader(sb, VariableExpression.class, var.range());
                sb.append(escapeForDump(var.name())).append('\n');
            }
            case GroupingExpression grouping -> {
                dumpNodeHeader(sb, GroupingExpression.class, grouping.range());
                dumpExpr(sb, grouping.inner());
            }
            case UnaryExpression unary -> {
                dumpNodeHeader(sb, UnaryExpression.class, unary.range());
                sb.append(unary.operator().ordinal()).append('\n');
                dumpExpr(sb, unary.operand());
            }
            case BinaryExpression binary -> {
                dumpNodeHeader(sb, BinaryExpression.class, binary.range());
                sb.append(binary.operator().ordinal()).append('\n');
                dumpExpr(sb, binary.left());
                dumpExpr(sb, binary.right());
            }
            case AssignmentExpression assign -> {
                dumpNodeHeader(sb, AssignmentExpression.class, assign.range());
                sb.append(escapeForDump(assign.target())).append('\n');
                dumpExpr(sb, assign.value());
            }
            case FunctionCallExpression call -> {
                dumpNodeHeader(sb, FunctionCallExpression.class, call.range());
                sb.append(escapeForDump(call.calleeName())).append('\n');
                sb.append(call.arguments().size()).append('\n');
                for (Expr arg : call.arguments()) {
                    dumpExpr(sb, arg);
                }
            }
            case NewArrayExpression newArray -> {
                dumpNodeHeader(sb, NewArrayExpression.class, newArray.range());
                sb.append(newArray.elementType().ordinal()).append('\n');
                dumpExpr(sb, newArray.size());
            }
            case ArrayAccessExpression access -> {
                dumpNodeHeader(sb, ArrayAccessExpression.class, access.range());
                dumpExpr(sb, access.array());
                dumpExpr(sb, access.index());
            }
            case ArrayLengthExpression length -> {
                dumpNodeHeader(sb, ArrayLengthExpression.class, length.range());
                dumpExpr(sb, length.array());
            }
            case IndexAssignmentExpression indexAssign -> {
                dumpNodeHeader(sb, IndexAssignmentExpression.class, indexAssign.range());
                dumpExpr(sb, indexAssign.array());
                dumpExpr(sb, indexAssign.index());
                dumpExpr(sb, indexAssign.value());
            }
            case NewStructExpression newStruct -> {
                dumpNodeHeader(sb, NewStructExpression.class, newStruct.range());
                sb.append(escapeForDump(newStruct.structName())).append('\n');
                sb.append(newStruct.arguments().size()).append('\n');
                for (Expr arg : newStruct.arguments()) {
                    dumpExpr(sb, arg);
                }
            }
            case FieldAccessExpression fieldAccess -> {
                dumpNodeHeader(sb, FieldAccessExpression.class, fieldAccess.range());
                sb.append(escapeForDump(fieldAccess.fieldName())).append('\n');
                dumpExpr(sb, fieldAccess.target());
            }
            case FieldAssignmentExpression fieldAssign -> {
                dumpNodeHeader(sb, FieldAssignmentExpression.class, fieldAssign.range());
                sb.append(escapeForDump(fieldAssign.fieldName())).append('\n');
                dumpExpr(sb, fieldAssign.target());
                dumpExpr(sb, fieldAssign.value());
            }
        }
    }

    private static String javaDump(String source) {
        Parser parser = new Parser(new Lexer(source).scanTokens());
        Program program = parser.parseProgram();
        List<Diagnostic> diagnostics = parser.reporter().diagnostics();
        StringBuilder sb = new StringBuilder();
        dumpProgram(sb, program);
        sb.append(diagnostics.size()).append('\n');
        for (Diagnostic d : diagnostics) {
            sb.append(escapeForDump(d.message())).append('\n');
            sb.append(d.range().start().line()).append('\n');
            sb.append(d.range().start().column()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Self-hosted-side canonical dump: a test-only recursive dumper
    // appended to the compiled library bundle, walking the AstArena in
    // the identical pre-order the Java dumper above walks the real AST.
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

            none dumpHeader(AstArena a, num id) {
                show(arenaKind(a, id));
                show(arenaStartLine(a, id));
                show(arenaStartCol(a, id));
                show(arenaEndLine(a, id));
                show(arenaEndCol(a, id));
            }

            none dumpType(AstArena a, num id) {
                show(arenaTypeElem(a, id));
                show(arenaTypeIsArray(a, id));
                show(dumpEscapeText(arenaTypeStructName(a, id)));
            }

            none dumpNode(AstArena a, num id) {
                dumpHeader(a, id);
                num kind = arenaKind(a, id);
                num i = 0;
                if (kind == nodeKindProgram()) {
                    num fc = arenaListCount(a, id);
                    show(fc);
                    loop (i < fc) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                    num sc = arenaList2Count(a, id);
                    show(sc);
                    i = 0;
                    loop (i < sc) {
                        dumpNode(a, arenaList2At(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindParameter()) {
                    dumpType(a, id);
                    show(dumpEscapeText(arenaText0(a, id)));
                } else if (kind == nodeKindFunctionDeclaration()) {
                    dumpType(a, id);
                    show(dumpEscapeText(arenaText0(a, id)));
                    num pc = arenaListCount(a, id);
                    show(pc);
                    loop (i < pc) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindStructDeclaration()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                    num flc = arenaListCount(a, id);
                    show(flc);
                    loop (i < flc) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindBlockStatement()) {
                    num stc = arenaListCount(a, id);
                    show(stc);
                    loop (i < stc) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindExpressionStatement()) {
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindVariableDeclaration()) {
                    dumpType(a, id);
                    show(dumpEscapeText(arenaText0(a, id)));
                    num initId = arenaChild1(a, id);
                    if (initId == -1) {
                        show(0);
                    } else {
                        show(1);
                        dumpNode(a, initId);
                    }
                } else if (kind == nodeKindIfStatement()) {
                    num elseId = arenaChild3(a, id);
                    if (elseId == -1) {
                        show(0);
                    } else {
                        show(1);
                    }
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
                    if (elseId != -1) {
                        dumpNode(a, elseId);
                    }
                } else if (kind == nodeKindWhileStatement()) {
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
                } else if (kind == nodeKindReturnStatement()) {
                    num valueId = arenaChild1(a, id);
                    if (valueId == -1) {
                        show(0);
                    } else {
                        show(1);
                        dumpNode(a, valueId);
                    }
                } else if (kind == nodeKindPrintStatement()) {
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindLiteralExpression()) {
                    num litType = arenaTypeElem(a, id);
                    show(litType);
                    if (litType == primTypeInt()) {
                        show(arenaChild1(a, id));
                    } else if (litType == primTypeFloat()) {
                        show(arenaLitDec(a, id));
                    } else if (litType == primTypeBool()) {
                        show(arenaChild1(a, id));
                    } else {
                        show(dumpEscapeText(arenaText0(a, id)));
                    }
                } else if (kind == nodeKindVariableExpression()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                } else if (kind == nodeKindGroupingExpression()) {
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindUnaryExpression()) {
                    show(arenaOperator(a, id));
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindBinaryExpression()) {
                    show(arenaOperator(a, id));
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
                } else if (kind == nodeKindAssignmentExpression()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindFunctionCallExpression()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                    num argc = arenaListCount(a, id);
                    show(argc);
                    loop (i < argc) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindNewArrayExpression()) {
                    show(arenaTypeElem(a, id));
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindArrayAccessExpression()) {
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
                } else if (kind == nodeKindArrayLengthExpression()) {
                    dumpNode(a, arenaChild1(a, id));
                } else if (kind == nodeKindIndexAssignmentExpression()) {
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
                    dumpNode(a, arenaChild3(a, id));
                } else if (kind == nodeKindNewStructExpression()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                    num argc2 = arenaListCount(a, id);
                    show(argc2);
                    loop (i < argc2) {
                        dumpNode(a, arenaListAt(a, id, i));
                        i = i + 1;
                    }
                } else if (kind == nodeKindFieldAccessExpression()) {
                    show(dumpEscapeText(arenaText0(a, id)));
                    dumpNode(a, arenaChild1(a, id));
                } else {
                    show(dumpEscapeText(arenaText0(a, id)));
                    dumpNode(a, arenaChild1(a, id));
                    dumpNode(a, arenaChild2(a, id));
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
                dumpNode(arena, programId);
                show(parseDiags.phases.count);
                num i = 0;
                loop (i < parseDiags.phases.count) {
                    show(dumpEscapeText(parseDiags.messages.items[i]));
                    show(parseDiags.lines.items[i]);
                    show(parseDiags.columns.items[i]);
                    i = i + 1;
                }
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
        assertFalse(parser.reporter().hasErrors(), "expected no parser errors compiling the self-hosted arena+parser bundle itself");
        SemanticAnalyzer analyzer = new SemanticAnalyzer(program);
        SemanticModel model = analyzer.analyze();
        assertFalse(analyzer.reporter().hasErrors(), "expected no semantic errors compiling the self-hosted arena+parser bundle itself");
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
        assertEquals(expected, actual, "self-hosted parser diverged from Java's Parser for source:\n" + source);
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
        void minimalMain() throws IOException {
            check("none main() { }");
        }

        @Test
        void emptyProgramNoFunctions() throws IOException {
            check("");
        }

        @Test
        void variableDeclarations() throws IOException {
            check("none main() { num x = 1; dec y = 2.5; flag f = yes; text t = \"hi\"; }");
        }

        @Test
        void structTypedDeclarations() throws IOException {
            check("struct Point { num x; num y; } none main() { Point p = new Point(1, 2); Point[] arr; }");
        }

        @Test
        void ifElseChains() throws IOException {
            check("none main() { num x = 1; if (x == 1) { show(1); } else if (x == 2) { show(2); } else { show(3); } }");
        }

        @Test
        void danglingElseAttachesToNearestIf() throws IOException {
            check("none main() { num x = 1; if (x == 1) if (x == 2) show(1); else show(2); }");
        }

        @Test
        void whileLoop() throws IOException {
            check("none main() { num i = 0; loop (i < 10) { show(i); i = i + 1; } }");
        }

        @Test
        void forLoopDesugars() throws IOException {
            check("none main() { run (num i = 0; i < 10; i = i + 1) { show(i); } }");
        }

        @Test
        void returnWithAndWithoutValue() throws IOException {
            check("num id(num x) { give x; } none noop() { give; }");
        }

        @Test
        void fullPrecedenceLadder() throws IOException {
            check("none main() { flag r = 1 + 2 * 3 - 4 / 2 % 2 == 5 && 1 < 2 || !yes; }");
        }

        @Test
        void assignmentIsRightAssociative() throws IOException {
            check("none main() { num a = 0; num b = 0; num c = 0; a = b = c = 5; }");
        }

        @Test
        void unaryIsRightAssociative() throws IOException {
            check("none main() { num x = 5; flag r = !!yes; num y = --x; }");
        }

        @Test
        void groupingOverridesPrecedence() throws IOException {
            check("none main() { num x = (1 + 2) * 3; }");
        }

        @Test
        void functionCallsAndArguments() throws IOException {
            check("num add(num a, num b) { give a + b; } none main() { num r = add(1, add(2, 3)); }");
        }

        @Test
        void arraysAndIndexing() throws IOException {
            check("none main() { num[] arr = new num[5]; arr[0] = 1; num x = arr[0]; num len = arr.len(); }");
        }

        @Test
        void structConstructionFieldAccessAndAssignment() throws IOException {
            check("struct Point { num x; num y; } struct Box { Point corner; } "
                    + "none main() { Box b = new Box(new Point(1, 2)); b.corner.x = 5; num v = b.corner.x; }");
        }

        @Test
        void chainedFieldAssignmentReturnsValue() throws IOException {
            check("struct Point { num x; num y; } none main() { Point a = new Point(0, 0); Point b = new Point(0, 0); a.x = b.x = 5; }");
        }

        @Test
        void arrayOfStructsFieldChain() throws IOException {
            check("struct Point { num x; } none main() { Point[] arr; arr[0].x = 5; num v = arr[0].x; }");
        }

        @Test
        void shortCircuitOperators() throws IOException {
            check("none main() { flag a = yes; flag b = no; flag r = a && b || !a; }");
        }

        @Test
        void multipleFunctionsAndStructsInterleaved() throws IOException {
            check("struct A { num x; } num f() { give 1; } struct B { num y; } num g() { give 2; } none main() { }");
        }

        @Test
        void nestedBlocks() throws IOException {
            check("none main() { { { num x = 1; } } }");
        }

        @Test
        void multilineSourceRangesTrackCorrectly() throws IOException {
            check("num add(num a,\n    num b) {\n  give a\n    + b;\n}\n");
        }

        // --- Recovery / diagnostics ---

        @Test
        void missingSemicolonRecovers() throws IOException {
            check("none main() { num x = 1 num y = 2; }");
        }

        @Test
        void missingClosingBraceInBlock() throws IOException {
            check("none main() { num x = 1;");
        }

        @Test
        void invalidAssignmentTarget() throws IOException {
            check("none main() { 1 + 2 = 3; }");
        }

        @Test
        void onlyPlainFunctionNameCanBeCalled() throws IOException {
            check("none main() { num x = 1; x()(); }");
        }

        @Test
        void malformedTopLevelDeclarationRecovers() throws IOException {
            check("@ @ @ none main() { show(1); }");
        }

        @Test
        void malformedStructBodyRecovers() throws IOException {
            check("struct Point { num x num y; } none main() { }");
        }

        @Test
        void missingExpressionAfterOperator() throws IOException {
            check("none main() { num x = 1 + ; }");
        }

        @Test
        void multipleErrorsAcrossFunctions() throws IOException {
            check("num f( { give 1; } num g() { give 2 }");
        }
    }

    @Nested
    class ExampleCorpus {

        @Test
        void everyExampleMatchesJavaParser() throws IOException {
            checkAll(Path.of("examples"));
        }

        @Test
        void everySemanticExampleMatchesJavaParser() throws IOException {
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
