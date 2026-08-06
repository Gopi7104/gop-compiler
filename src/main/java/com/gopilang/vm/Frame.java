package com.gopilang.vm;

import com.gopilang.bytecode.BytecodeFunction;

import java.util.ArrayDeque;

/**
 * One function-call activation record: its own local-variable slots and
 * operand stack, independent of every other simultaneously-active call.
 * {@link VirtualMachine} keeps a stack of these — one push per {@code CALL},
 * one pop per {@code RETURN} — which is what makes recursion work with no
 * special-case logic: a recursive call is just another frame on the stack.
 */
public final class Frame {

    private final BytecodeFunction function;
    private final Object[] locals;
    private final ArrayDeque<Object> operandStack = new ArrayDeque<>();
    private final int returnAddress;

    /** Creates a frame for {@code function}, allocating {@code function.slotCount()} local slots. */
    public Frame(BytecodeFunction function, int returnAddress) {
        this.function = function;
        this.locals = new Object[function.slotCount()];
        this.returnAddress = returnAddress;
    }

    /** The function this frame is executing. */
    public BytecodeFunction function() {
        return function;
    }

    /** This call's local-variable slots (parameters first, then declared locals). */
    public Object[] locals() {
        return locals;
    }

    /** This call's operand stack, used by expression-evaluating opcodes. */
    public ArrayDeque<Object> operandStack() {
        return operandStack;
    }

    /** The instruction index to resume at, in the caller's frame, once this call returns. */
    public int returnAddress() {
        return returnAddress;
    }
}
