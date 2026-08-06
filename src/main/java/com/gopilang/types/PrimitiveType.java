package com.gopilang.types;

/** GopiLang's built-in types. {@code VOID} is only ever a function return type — never a value's type. */
public enum PrimitiveType {
    INT,
    FLOAT,
    BOOL,
    STRING,
    VOID;

    // The GopiLang keyword spelling, for user-facing diagnostics only —
    // toString() (used by AstPrinter/BytecodeDisassembler's internal-view
    // dumps) deliberately keeps returning the enum's own name.
    public String displayName() {
        return switch (this) {
            case INT -> "num";
            case FLOAT -> "dec";
            case BOOL -> "flag";
            case STRING -> "text";
            case VOID -> "none";
        };
    }
}
