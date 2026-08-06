package com.gopilang.lexer;

/** Every kind of token the lexer can produce, grouped by category. */
public enum TokenType {

    // Literals
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    BOOLEAN_LITERAL,

    // Identifiers
    IDENTIFIER,

    // Keywords: types
    KW_INT,
    KW_FLOAT,
    KW_BOOL,
    KW_STRING,
    KW_VOID,

    // Keywords: control flow / builtins
    KW_IF,
    KW_ELSE,
    KW_WHILE,
    KW_FOR,
    KW_RETURN,
    KW_PRINT,
    KW_NEW,

    // Operators: arithmetic
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,

    // Operators: assignment
    ASSIGN,

    // Operators: comparison
    EQUAL_EQUAL,
    BANG_EQUAL,
    LESS,
    GREATER,
    LESS_EQUAL,
    GREATER_EQUAL,

    // Operators: logical
    AND_AND,
    OR_OR,
    BANG,

    // Separators
    SEMICOLON,
    COMMA,
    DOT,

    // Grouping
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,

    // Sentinel
    EOF
}
