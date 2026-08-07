package com.gopilang.bytecode;

/**
 * Metadata for one compiled struct: how many fields it has, needed by {@code
 * NEW_STRUCT} to allocate and fill its runtime {@code Object[]}
 * representation. Mirrors {@code BytecodeFunction}'s role for {@code CALL} —
 * {@code NEW_STRUCT} addresses this list by index rather than encoding a
 * field count directly in the instruction, so a struct's fuller runtime
 * metadata (e.g. field names/types, for a later field-access milestone) has
 * one place to grow into without changing the instruction encoding.
 */
public record BytecodeStruct(String name, int fieldCount) {
}
