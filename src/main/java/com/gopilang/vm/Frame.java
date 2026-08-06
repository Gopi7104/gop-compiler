package com.gopilang.vm;

import com.gopilang.bytecode.BytecodeFunction;

import java.util.ArrayDeque;

public final class Frame {

    private final BytecodeFunction function;
    private final Object[] locals;
    private final ArrayDeque<Object> operandStack = new ArrayDeque<>();
    private final int returnAddress;

    public Frame(BytecodeFunction function, int returnAddress) {
        this.function = function;
        this.locals = new Object[function.slotCount()];
        this.returnAddress = returnAddress;
    }

    public BytecodeFunction function() {
        return function;
    }

    public Object[] locals() {
        return locals;
    }

    public ArrayDeque<Object> operandStack() {
        return operandStack;
    }

    public int returnAddress() {
        return returnAddress;
    }
}
