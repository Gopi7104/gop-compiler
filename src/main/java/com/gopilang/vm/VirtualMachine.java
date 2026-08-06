package com.gopilang.vm;

import com.gopilang.bytecode.BytecodeFunction;
import com.gopilang.bytecode.BytecodeModule;
import com.gopilang.bytecode.Instruction;
import com.gopilang.bytecode.Opcode;

import java.util.ArrayDeque;
import java.util.Deque;

public final class VirtualMachine {

    private final BytecodeModule module;
    private final Deque<Frame> callStack = new ArrayDeque<>();
    private int pc;

    public VirtualMachine(BytecodeModule module) {
        this.module = module;
        this.pc = 0;
    }

    public void run() {
        Frame frame = callStack.peek();
        while (true) {
            Instruction instruction = module.instructions().get(pc);
            pc++;
            switch (instruction.opcode()) {
                case PUSH_CONST -> frame.operandStack().push(module.constantPool().get(instruction.operand()));
                case POP -> frame.operandStack().pop();
                case DUP -> frame.operandStack().push(frame.operandStack().peek());
                case LOAD -> frame.operandStack().push(frame.locals()[instruction.operand()]);
                case STORE -> frame.locals()[instruction.operand()] = frame.operandStack().pop();
                case ADD, SUB, MUL, DIV -> {
                    Object right = frame.operandStack().pop();
                    Object left = frame.operandStack().pop();
                    frame.operandStack().push(arithmetic(instruction.opcode(), left, right));
                }
                case MOD -> {
                    int right = (Integer) frame.operandStack().pop();
                    int left = (Integer) frame.operandStack().pop();
                    frame.operandStack().push(left % right);
                }
                case NEG -> {
                    Object value = frame.operandStack().pop();
                    if (value instanceof Integer i) {
                        frame.operandStack().push(-i);
                    } else {
                        frame.operandStack().push(-(Double) value);
                    }
                }
                case NOT -> frame.operandStack().push(!(Boolean) frame.operandStack().pop());
                case CMP_EQ -> {
                    Object right = frame.operandStack().pop();
                    Object left = frame.operandStack().pop();
                    frame.operandStack().push(valuesEqual(left, right));
                }
                case CMP_NE -> {
                    Object right = frame.operandStack().pop();
                    Object left = frame.operandStack().pop();
                    frame.operandStack().push(!valuesEqual(left, right));
                }
                case CMP_LT, CMP_GT, CMP_LE, CMP_GE -> {
                    Object right = frame.operandStack().pop();
                    Object left = frame.operandStack().pop();
                    frame.operandStack().push(compareNumeric(instruction.opcode(), left, right));
                }
                case CONCAT -> {
                    String right = (String) frame.operandStack().pop();
                    String left = (String) frame.operandStack().pop();
                    frame.operandStack().push(left + right);
                }
                case PRINT -> System.out.println(frame.operandStack().pop());
                case JMP -> pc = instruction.operand();
                case JMP_IF_FALSE -> {
                    boolean condition = (Boolean) frame.operandStack().pop();
                    if (!condition) {
                        pc = instruction.operand();
                    }
                }
                case CALL -> {
                    BytecodeFunction target = module.functions().get(instruction.operand());
                    Frame callee = new Frame(target, pc);
                    for (int i = target.parameterCount() - 1; i >= 0; i--) {
                        callee.locals()[i] = frame.operandStack().pop();
                    }
                    callStack.push(callee);
                    frame = callee;
                    pc = target.codeStart();
                }
                case RETURN -> {
                    Frame returning = callStack.pop();
                    boolean hasReturnValue = !returning.operandStack().isEmpty();
                    Object returnValue = hasReturnValue ? returning.operandStack().pop() : null;
                    if (callStack.isEmpty()) {
                        return;
                    }
                    frame = callStack.peek();
                    if (hasReturnValue) {
                        frame.operandStack().push(returnValue);
                    }
                    pc = returning.returnAddress();
                }
                case HALT -> {
                    return;
                }
                default -> throw new UnsupportedOperationException(
                        "Opcode not implemented: " + instruction.opcode());
            }
        }
    }

    private static Object arithmetic(Opcode opcode, Object left, Object right) {
        if (left instanceof Integer li && right instanceof Integer ri) {
            return switch (opcode) {
                case ADD -> li + ri;
                case SUB -> li - ri;
                case MUL -> li * ri;
                case DIV -> li / ri;
                default -> throw new IllegalStateException("Not an arithmetic opcode: " + opcode);
            };
        }
        double ld = toDouble(left);
        double rd = toDouble(right);
        return switch (opcode) {
            case ADD -> ld + rd;
            case SUB -> ld - rd;
            case MUL -> ld * rd;
            case DIV -> ld / rd;
            default -> throw new IllegalStateException("Not an arithmetic opcode: " + opcode);
        };
    }

    private static double toDouble(Object value) {
        return value instanceof Integer i ? i : (Double) value;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right)) {
            return toDouble(left) == toDouble(right);
        }
        return left.equals(right);
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Integer || value instanceof Double;
    }

    private static boolean compareNumeric(Opcode opcode, Object left, Object right) {
        if (left instanceof Integer li && right instanceof Integer ri) {
            return switch (opcode) {
                case CMP_LT -> li < ri;
                case CMP_GT -> li > ri;
                case CMP_LE -> li <= ri;
                case CMP_GE -> li >= ri;
                default -> throw new IllegalStateException("Not a comparison opcode: " + opcode);
            };
        }
        double ld = toDouble(left);
        double rd = toDouble(right);
        return switch (opcode) {
            case CMP_LT -> ld < rd;
            case CMP_GT -> ld > rd;
            case CMP_LE -> ld <= rd;
            case CMP_GE -> ld >= rd;
            default -> throw new IllegalStateException("Not a comparison opcode: " + opcode);
        };
    }
}
