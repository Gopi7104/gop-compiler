package com.gopilang.bytecode;

import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BlockStatement;
import com.gopilang.ast.Expr;
import com.gopilang.ast.ExpressionStatement;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.FunctionDeclaration;
import com.gopilang.ast.IfStatement;
import com.gopilang.ast.LiteralExpression;
import com.gopilang.ast.Parameter;
import com.gopilang.ast.PrintStatement;
import com.gopilang.ast.Program;
import com.gopilang.ast.ReturnStatement;
import com.gopilang.ast.Stmt;
import com.gopilang.ast.UnaryExpression;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.semantic.FunctionSymbol;
import com.gopilang.semantic.SemanticModel;
import com.gopilang.semantic.VariableSymbol;
import com.gopilang.types.PrimitiveType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeGenerator {

    private final Program program;
    private final SemanticModel semanticModel;

    private final List<Object> constantPool = new ArrayList<>();
    private final Map<Object, Integer> constantIndices = new HashMap<>();
    private final List<BytecodeFunction> functions = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final Map<VariableSymbol, Integer> slots = new HashMap<>();
    private final Map<FunctionSymbol, Integer> functionIndices = new HashMap<>();

    public CodeGenerator(Program program, SemanticModel semanticModel) {
        this.program = program;
        this.semanticModel = semanticModel;
    }

    public BytecodeModule generate() {
        List<FunctionDeclaration> declarations = program.functions();
        for (int i = 0; i < declarations.size(); i++) {
            FunctionSymbol symbol = semanticModel.functionTable().get(declarations.get(i).name());
            functionIndices.put(symbol, i);
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

        return new BytecodeModule(constantPool, functions, instructions);
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
            case BinaryExpression binary -> {
                compileExpr(binary.left());
                compileExpr(binary.right());
                Opcode opcode = switch (binary.operator()) {
                    case ADD -> semanticModel.expressionTypes().get(binary) == PrimitiveType.STRING
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
                    // TODO: AND/OR need short-circuit compilation (conditional jumps over
                    // the right operand), not a single opcode after both sides are compiled.
                    case AND, OR -> throw new UnsupportedOperationException(
                            "short-circuit compilation for " + binary.operator() + " not yet implemented");
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
                Integer functionIndex = functionIndices.get(symbol);
                if (functionIndex == null) {
                    throw new IllegalStateException("No function index for: " + symbol.name());
                }
                instructions.add(new Instruction(Opcode.CALL, functionIndex));
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
                PrimitiveType type = semanticModel.expressionTypes().get(exprStmt.expression());
                if (type == null) {
                    throw new IllegalStateException("Untyped ExpressionStatement expression");
                }
                if (type != PrimitiveType.VOID) {
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
