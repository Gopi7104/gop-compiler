package com.gopilang.bytecode;

import com.gopilang.ast.ArrayAccessExpression;
import com.gopilang.ast.ArrayLengthExpression;
import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BinaryOperator;
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
import com.gopilang.semantic.FunctionSymbol;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.semantic.StructSymbol;
import com.gopilang.semantic.VariableSymbol;
import com.gopilang.types.PrimitiveType;
import com.gopilang.types.TypeRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers a semantically-valid {@code Program} into a {@code BytecodeModule}.
 * Builder-style: mutable accumulators (constant pool, function table,
 * instruction list, slot maps) are filled in while walking the AST, then
 * assembled into one immutable {@code BytecodeModule} at the end — the same
 * builder-then-freeze shape {@code SemanticAnalyzer} uses for
 * {@code SemanticModel}. Never re-validates anything {@code SemanticModel}
 * already resolved; a missing resolution is treated as an internal
 * invariant violation ({@code IllegalStateException}), not a user-facing
 * error, since only an already-valid {@code Program} should ever reach here.
 */
public final class CodeGenerator {

    private final Program program;
    private final SemanticModel semanticModel;

    private final List<Object> constantPool = new ArrayList<>();
    private final Map<Object, Integer> constantIndices = new HashMap<>();
    private final List<BytecodeFunction> functions = new ArrayList<>();
    private final List<BytecodeStruct> structs = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final Map<VariableSymbol, Integer> slots = new HashMap<>();
    private final Map<FunctionSymbol, Integer> functionIndices = new HashMap<>();
    private final Map<StructSymbol, Integer> structIndices = new HashMap<>();

    // Milestone B1: the one place a builtin's name maps to its dedicated
    // opcode. Builtins are ordinary FunctionSymbols in functionTable (see
    // SemanticAnalyzer.registerBuiltins()) — this map is consulted only here,
    // to decide CALL vs. a dedicated opcode; every other check (argument
    // count/type, return type) already ran generically, unchanged.
    private static final Map<String, Opcode> BUILTIN_OPCODES = Map.of(
            "charCodeAt", Opcode.CHAR_CODE_AT,
            "textLength", Opcode.TEXT_LENGTH,
            "textFromCharCode", Opcode.TEXT_FROM_CHAR_CODE,
            "readFile", Opcode.READ_FILE,
            "argCount", Opcode.ARG_COUNT,
            "argAt", Opcode.ARG_AT);

    public CodeGenerator(Program program, SemanticModel semanticModel) {
        this.program = program;
        this.semanticModel = semanticModel;
    }

    /**
     * Assigns every function a stable index, assigns every struct a stable
     * index (mirroring the function case exactly — {@code NEW_STRUCT}
     * addresses {@code structs()} by index just like {@code CALL} addresses
     * {@code functions()}), emits the two-instruction entry stub ({@code CALL
     * main; HALT}), compiles each function in declaration order, and returns
     * the assembled module.
     */
    public BytecodeModule generate() {
        List<FunctionDeclaration> declarations = program.functions();
        for (int i = 0; i < declarations.size(); i++) {
            FunctionSymbol symbol = semanticModel.functionTable().get(declarations.get(i).name());
            functionIndices.put(symbol, i);
        }

        List<StructDeclaration> structDeclarations = program.structs();
        for (int i = 0; i < structDeclarations.size(); i++) {
            StructSymbol symbol = semanticModel.structTable().get(structDeclarations.get(i).name());
            structIndices.put(symbol, i);
            structs.add(new BytecodeStruct(symbol.name(), symbol.fields().size()));
        }

        FunctionSymbol mainSymbol = semanticModel.functionTable().get("main");
        if (mainSymbol == null) {
            throw new IllegalStateException("No main function in functionTable");
        }
        instructions.add(new Instruction(Opcode.CALL, functionIndices.get(mainSymbol)));
        instructions.add(new Instruction(Opcode.HALT, 0));

        for (FunctionDeclaration function : declarations) {
            compileFunction(function);
        }

        return new BytecodeModule(constantPool, functions, structs, instructions);
    }

    // Resolved entirely at code-generation time, from struct.fields()'s
    // declaration order — no runtime field metadata, no lookup table, since
    // ARRAY_GET/ARRAY_SET address a struct's Object[] purely by integer index.
    private int fieldIndex(StructSymbol struct, String fieldName) {
        List<Parameter> fields = struct.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(fieldName)) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown field '" + fieldName + "' on struct '" + struct.name() + "'");
    }

    private int constantIndex(Object value) {
        Integer existing = constantIndices.get(value);
        if (existing != null) {
            return existing;
        }
        int index = constantPool.size();
        constantPool.add(value);
        constantIndices.put(value, index);
        return index;
    }

    private void compileExpr(Expr expr) {
        switch (expr) {
            case LiteralExpression literal -> {
                int index = constantIndex(literal.value());
                instructions.add(new Instruction(Opcode.PUSH_CONST, index));
            }
            case GroupingExpression grouping -> compileExpr(grouping.inner());
            case VariableExpression variable -> {
                VariableSymbol symbol = semanticModel.variableResolutions().get(variable);
                if (symbol == null) {
                    throw new IllegalStateException("Unresolved VariableExpression: " + variable.name());
                }
                Integer slot = slots.get(symbol);
                if (slot == null) {
                    throw new IllegalStateException("No slot allocated for variable: " + symbol.name());
                }
                instructions.add(new Instruction(Opcode.LOAD, slot));
            }
            case UnaryExpression unary -> {
                compileExpr(unary.operand());
                Opcode opcode = switch (unary.operator()) {
                    case NEGATE -> Opcode.NEG;
                    case NOT -> Opcode.NOT;
                };
                instructions.add(new Instruction(opcode, 0));
            }
            case BinaryExpression binary when binary.operator() == BinaryOperator.AND -> {
                // left && right, using only JMP_IF_FALSE/JMP (no JMP_IF_TRUE exists):
                //   compile(left); JMP_IF_FALSE -> FALSE; compile(right); JMP -> END;
                //   FALSE: PUSH_CONST no; END:
                // JMP_IF_FALSE unconditionally pops its operand, so the FALSE branch
                // must push its own `no` — left's value is already gone from the stack
                // by the time either branch is reached. Both branches leave exactly one
                // value on the stack, same invariant every other expression upholds.
                compileExpr(binary.left());
                int jmpFalseIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP_IF_FALSE, -1));
                compileExpr(binary.right());
                int jmpEndIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP, -1));
                instructions.set(jmpFalseIndex, new Instruction(Opcode.JMP_IF_FALSE, instructions.size()));
                instructions.add(new Instruction(Opcode.PUSH_CONST, constantIndex(false)));
                instructions.set(jmpEndIndex, new Instruction(Opcode.JMP, instructions.size()));
            }
            case BinaryExpression binary when binary.operator() == BinaryOperator.OR -> {
                // left || right: compile(left); JMP_IF_FALSE -> EVAL_RIGHT (left true
                // falls through to short-circuit); PUSH_CONST yes; JMP -> END;
                // EVAL_RIGHT: compile(right); END: — mirror image of AND above.
                compileExpr(binary.left());
                int jmpFalseIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP_IF_FALSE, -1));
                instructions.add(new Instruction(Opcode.PUSH_CONST, constantIndex(true)));
                int jmpEndIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP, -1));
                instructions.set(jmpFalseIndex, new Instruction(Opcode.JMP_IF_FALSE, instructions.size()));
                compileExpr(binary.right());
                instructions.set(jmpEndIndex, new Instruction(Opcode.JMP, instructions.size()));
            }
            case BinaryExpression binary -> {
                compileExpr(binary.left());
                compileExpr(binary.right());
                Opcode opcode = switch (binary.operator()) {
                    case ADD -> semanticModel.expressionTypes().get(binary).elementType() == PrimitiveType.STRING
                            ? Opcode.CONCAT
                            : Opcode.ADD;
                    case SUBTRACT -> Opcode.SUB;
                    case MULTIPLY -> Opcode.MUL;
                    case DIVIDE -> Opcode.DIV;
                    case MODULO -> Opcode.MOD;
                    case EQUAL -> Opcode.CMP_EQ;
                    case NOT_EQUAL -> Opcode.CMP_NE;
                    case LESS -> Opcode.CMP_LT;
                    case GREATER -> Opcode.CMP_GT;
                    case LESS_EQUAL -> Opcode.CMP_LE;
                    case GREATER_EQUAL -> Opcode.CMP_GE;
                    case AND, OR -> throw new IllegalStateException("unreachable: handled above");
                };
                instructions.add(new Instruction(opcode, 0));
            }
            case AssignmentExpression assignment -> {
                compileExpr(assignment.value());
                instructions.add(new Instruction(Opcode.DUP, 0));
                VariableSymbol symbol = semanticModel.assignmentTargetResolutions().get(assignment);
                if (symbol == null) {
                    throw new IllegalStateException("Unresolved AssignmentExpression target: " + assignment.target());
                }
                Integer slot = slots.get(symbol);
                if (slot == null) {
                    throw new IllegalStateException("No slot allocated for variable: " + symbol.name());
                }
                instructions.add(new Instruction(Opcode.STORE, slot));
            }
            case FunctionCallExpression call -> {
                for (Expr argument : call.arguments()) {
                    compileExpr(argument);
                }
                FunctionSymbol symbol = semanticModel.callResolutions().get(call);
                if (symbol == null) {
                    throw new IllegalStateException("Unresolved FunctionCallExpression: " + call.calleeName());
                }
                Opcode builtinOpcode = BUILTIN_OPCODES.get(symbol.name());
                if (builtinOpcode != null) {
                    instructions.add(new Instruction(builtinOpcode, 0));
                } else {
                    Integer functionIndex = functionIndices.get(symbol);
                    if (functionIndex == null) {
                        throw new IllegalStateException("No function index for: " + symbol.name());
                    }
                    instructions.add(new Instruction(Opcode.CALL, functionIndex));
                }
            }
            case NewArrayExpression newArray -> {
                compileExpr(newArray.size());
                instructions.add(new Instruction(Opcode.NEW_ARRAY, 0));
            }
            case ArrayAccessExpression access -> {
                compileExpr(access.array());
                compileExpr(access.index());
                instructions.add(new Instruction(Opcode.ARRAY_GET, 0));
            }
            case ArrayLengthExpression length -> {
                compileExpr(length.array());
                instructions.add(new Instruction(Opcode.ARRAY_LENGTH, 0));
            }
            case IndexAssignmentExpression indexAssignment -> {
                compileExpr(indexAssignment.array());
                compileExpr(indexAssignment.index());
                compileExpr(indexAssignment.value());
                // ARRAY_SET pushes the stored value back itself (unlike plain
                // STORE), so no DUP is needed here — a single 3-argument
                // opcode can't be split by DUP the way a 1-slot STORE can.
                instructions.add(new Instruction(Opcode.ARRAY_SET, 0));
            }
            case NewStructExpression construction -> {
                for (Expr argument : construction.arguments()) {
                    compileExpr(argument);
                }
                // Derived from SemanticModel exactly as approved — no
                // separate construction-resolution map: expressionTypes()
                // gives back the TypeRef this construction was typed as,
                // whose structName() looks up the same StructSymbol in
                // structTable() that registerStructs() already put there.
                TypeRef type = semanticModel.expressionTypes().get(construction);
                if (type == null || type.structName().isEmpty()) {
                    throw new IllegalStateException("Unresolved NewStructExpression: " + construction.structName());
                }
                StructSymbol symbol = semanticModel.structTable().get(type.structName().get());
                if (symbol == null) {
                    throw new IllegalStateException("Unknown struct: " + type.structName().get());
                }
                Integer structIndex = structIndices.get(symbol);
                if (structIndex == null) {
                    throw new IllegalStateException("No struct index for: " + symbol.name());
                }
                instructions.add(new Instruction(Opcode.NEW_STRUCT, structIndex));
            }
            case FieldAccessExpression access -> {
                compileExpr(access.target());
                // Derived from SemanticModel exactly as NewStructExpression
                // does above: the target's own recorded TypeRef carries the
                // struct name, which structTable() resolves to a StructSymbol.
                TypeRef targetType = semanticModel.expressionTypes().get(access.target());
                if (targetType == null || targetType.structName().isEmpty()) {
                    throw new IllegalStateException("Unresolved FieldAccessExpression target");
                }
                StructSymbol struct = semanticModel.structTable().get(targetType.structName().get());
                if (struct == null) {
                    throw new IllegalStateException("Unknown struct: " + targetType.structName().get());
                }
                int index = fieldIndex(struct, access.fieldName());
                instructions.add(new Instruction(Opcode.PUSH_CONST, constantIndex(index)));
                instructions.add(new Instruction(Opcode.ARRAY_GET, 0));
            }
            case FieldAssignmentExpression assignment -> {
                compileExpr(assignment.target());
                TypeRef targetType = semanticModel.expressionTypes().get(assignment.target());
                if (targetType == null || targetType.structName().isEmpty()) {
                    throw new IllegalStateException("Unresolved FieldAssignmentExpression target");
                }
                StructSymbol struct = semanticModel.structTable().get(targetType.structName().get());
                if (struct == null) {
                    throw new IllegalStateException("Unknown struct: " + targetType.structName().get());
                }
                int index = fieldIndex(struct, assignment.fieldName());
                instructions.add(new Instruction(Opcode.PUSH_CONST, constantIndex(index)));
                compileExpr(assignment.value());
                // ARRAY_SET pushes the stored value back itself, exactly as
                // IndexAssignmentExpression above relies on for chaining.
                instructions.add(new Instruction(Opcode.ARRAY_SET, 0));
            }
            default -> throw new UnsupportedOperationException(
                    "compileExpr not yet implemented for " + expr.getClass().getSimpleName());
        }
    }

    private void compileStatement(Stmt stmt) {
        switch (stmt) {
            case BlockStatement block -> {
                for (Stmt statement : block.statements()) {
                    compileStatement(statement);
                }
            }
            case VariableDeclaration decl -> {
                if (decl.initializer().isPresent()) {
                    compileExpr(decl.initializer().get());
                    VariableSymbol symbol = new VariableSymbol(decl.name(), decl.type(), decl.range());
                    Integer slot = slots.get(symbol);
                    if (slot == null) {
                        throw new IllegalStateException("No slot allocated for variable: " + decl.name());
                    }
                    instructions.add(new Instruction(Opcode.STORE, slot));
                }
            }
            case PrintStatement print -> {
                compileExpr(print.value());
                instructions.add(new Instruction(Opcode.PRINT, 0));
            }
            case ExpressionStatement exprStmt -> {
                compileExpr(exprStmt.expression());
                TypeRef type = semanticModel.expressionTypes().get(exprStmt.expression());
                if (type == null) {
                    throw new IllegalStateException("Untyped ExpressionStatement expression");
                }
                if (type.elementType() != PrimitiveType.VOID) {
                    instructions.add(new Instruction(Opcode.POP, 0));
                }
            }
            case ReturnStatement returnStmt -> {
                returnStmt.value().ifPresent(this::compileExpr);
                instructions.add(new Instruction(Opcode.RETURN, 0));
            }
            case IfStatement ifStmt -> {
                compileExpr(ifStmt.condition());
                int jmpIfFalseIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP_IF_FALSE, -1));

                compileStatement(ifStmt.thenBranch());

                if (ifStmt.elseBranch().isPresent()) {
                    int jmpEndIndex = instructions.size();
                    instructions.add(new Instruction(Opcode.JMP, -1));

                    instructions.set(jmpIfFalseIndex, new Instruction(Opcode.JMP_IF_FALSE, instructions.size()));
                    compileStatement(ifStmt.elseBranch().get());

                    instructions.set(jmpEndIndex, new Instruction(Opcode.JMP, instructions.size()));
                } else {
                    instructions.set(jmpIfFalseIndex, new Instruction(Opcode.JMP_IF_FALSE, instructions.size()));
                }
            }
            case WhileStatement whileStmt -> {
                int loopStart = instructions.size();
                compileExpr(whileStmt.condition());

                int jmpIfFalseIndex = instructions.size();
                instructions.add(new Instruction(Opcode.JMP_IF_FALSE, -1));

                compileStatement(whileStmt.body());
                instructions.add(new Instruction(Opcode.JMP, loopStart));

                instructions.set(jmpIfFalseIndex, new Instruction(Opcode.JMP_IF_FALSE, instructions.size()));
            }
            default -> throw new UnsupportedOperationException(
                    "compileStatement not yet implemented for " + stmt.getClass().getSimpleName());
        }
    }

    private void compileFunction(FunctionDeclaration function) {
        slots.clear();
        int nextSlot = 0;
        for (Parameter parameter : function.parameters()) {
            VariableSymbol symbol = new VariableSymbol(parameter.name(), parameter.type(), parameter.range());
            slots.put(symbol, nextSlot);
            nextSlot++;
        }
        nextSlot = assignLocalSlots(function.body(), nextSlot);

        int codeStart = instructions.size();
        compileStatement(function.body());
        instructions.add(new Instruction(Opcode.RETURN, 0));

        functions.add(new BytecodeFunction(function.name(), function.parameters().size(), nextSlot, codeStart));
    }

    private int assignLocalSlots(Stmt stmt, int nextSlot) {
        switch (stmt) {
            case BlockStatement block -> {
                for (Stmt statement : block.statements()) {
                    nextSlot = assignLocalSlots(statement, nextSlot);
                }
            }
            case VariableDeclaration decl -> {
                VariableSymbol symbol = new VariableSymbol(decl.name(), decl.type(), decl.range());
                slots.put(symbol, nextSlot);
                nextSlot++;
            }
            case IfStatement ifStmt -> {
                nextSlot = assignLocalSlots(ifStmt.thenBranch(), nextSlot);
                if (ifStmt.elseBranch().isPresent()) {
                    nextSlot = assignLocalSlots(ifStmt.elseBranch().get(), nextSlot);
                }
            }
            case WhileStatement whileStmt -> nextSlot = assignLocalSlots(whileStmt.body(), nextSlot);
            case PrintStatement ignored -> {
            }
            case ExpressionStatement ignored -> {
            }
            case ReturnStatement ignored -> {
            }
        }
        return nextSlot;
    }
}
