# The GopiLang Language

GopiLang is a small, statically-typed, C-family language. This document describes the language as currently implemented — every example here compiles and runs on the current compiler.

## Comments

Line comments only, starting with `//` and running to end of line:

```
// this is a comment
int x = 5; // and so is this
```

There is no block-comment syntax.

## Types

Five primitive types:

| Type     | Description                          | Example literal |
|----------|---------------------------------------|------------------|
| `int`    | 32-bit signed integer                 | `42`             |
| `float`  | double-precision floating point       | `3.14`           |
| `bool`   | boolean                               | `true`, `false`  |
| `string` | text                                  | `"hello"`        |
| `void`   | "no value" — only valid as a function return type | — |

There is no implicit narrowing: an `int` value can be used where a `float` is expected (it's widened automatically), but not the reverse. `void` cannot be used as a variable's type or as a value.

String literals support the escape sequences `\n`, `\t`, `\"`, and `\\`. Strings cannot span multiple lines.

## Variables

Declared with an explicit type, optionally with an initializer:

```
int x = 5;
int y;        // declared, not yet assigned
y = 10;       // now assigned
```

Reading a variable before it has definitely been assigned on every path leading to that read is a compile-time error (definite-assignment checking) — this is checked independently of whether the variable had an initializer.

A variable declared in an inner scope may not shadow a variable of the same name from an enclosing scope — this is a compile-time error, not a warning (Java-style, not C-style shadowing rules).

## Operators

**Arithmetic**: `+` `-` `*` `/` `%`
`+` is overloaded: `int`/`float` operands add numerically; two `string` operands concatenate. Mixing `int` and `float` operands in any arithmetic operator produces a `float` result. `%` requires both operands to be `int`.

**Comparison**: `==` `!=` `<` `>` `<=` `>=`
`==`/`!=` work on any two operands of compatible type (numeric-vs-numeric, `bool`-vs-`bool`, `string`-vs-`string`, by value for strings). `<` `>` `<=` `>=` are numeric-only.

**Logical**: `&&` `||` `!`
`!` (logical NOT) is fully implemented. `&&`/`||` parse and type-check correctly (both operands must be `bool`) but are not yet compiled to executable bytecode — see the [README's roadmap](README.md#future-roadmap).

**Assignment**: `=`
Assignment is itself an expression (not just a statement), and is right-associative, so chained assignment works:

```
int a;
int b;
a = b = 5;   // both a and b become 5
print(a);    // 5
```

**Grouping**: `(` `expr` `)` — parentheses override default precedence, exactly as you'd expect.

### Precedence (highest to lowest)

1. Primary (literals, identifiers, function calls, parenthesized expressions)
2. Unary `!` `-` (right-associative)
3. `*` `/` `%`
4. `+` `-`
5. `<` `>` `<=` `>=`
6. `==` `!=`
7. `&&`
8. `||`
9. `=` (right-associative, lowest precedence)

## `if` / `else`

```
if (x > 0) {
    print(1);
} else {
    print(-1);
}
```

`else` is optional. A dangling `else` always attaches to the nearest preceding `if`. Braces are required around each branch's body in the current grammar (a branch is always a `{ ... }` block in practice, even though the AST technically allows any single statement).

## `while`

```
int i = 0;
while (i < 5) {
    print(i);
    i = i + 1;
}
```

There is no `for`, `do`/`while`, `break`, or `continue` — `while` is the only loop construct.

## Functions

Every function has an explicit return type, a name, a parenthesized parameter list (each parameter with an explicit type), and a `{ ... }` body:

```
int add(int a, int b) {
    return a + b;
}
```

`return` may appear with or without a value, and every non-`void` function must return a value on every reachable path (checked by reachability analysis at compile time). Functions cannot be nested — a function may only be declared at the top level of a program, never inside another function's body.

Every GopiLang program must declare exactly one function named `main`, with return type `void` and no parameters — this is the program's entry point, and its absence (or a wrong signature) is a semantic error.

## Recursion

Functions may call themselves, directly or indirectly, with no special syntax:

```
int factorial(int n) {
    if (n <= 1) {
        return 1;
    } else {
        return n * factorial(n - 1);
    }
}
```

Each active call gets its own independent set of local variables at runtime — recursion works because of how the VM's call stack is structured, not because of anything special in the language grammar.

## `print`

```
print(expression);
```

A built-in statement (not a function) that evaluates its expression and writes its value to standard output followed by a newline. Any printable type (`int`, `float`, `bool`, `string`) may be printed; `void` cannot.

## Grammar Overview

Informal EBNF-style summary of the current grammar (see [`Parser.java`](src/main/java/com/gopilang/parser/Parser.java) for the authoritative hand-written recursive-descent implementation):

```
program        ::= functionDecl* EOF

functionDecl   ::= type IDENTIFIER "(" parameters? ")" block
parameters     ::= parameter ("," parameter)*
parameter      ::= type IDENTIFIER
type           ::= "int" | "float" | "bool" | "string" | "void"

block          ::= "{" statement* "}"
statement      ::= variableDecl | ifStmt | whileStmt | returnStmt
                  | printStmt | exprStmt | block

variableDecl   ::= type IDENTIFIER ("=" expression)? ";"
ifStmt         ::= "if" "(" expression ")" statement ("else" statement)?
whileStmt      ::= "while" "(" expression ")" statement
returnStmt     ::= "return" expression? ";"
printStmt      ::= "print" "(" expression ")" ";"
exprStmt       ::= expression ";"

expression     ::= assignment
assignment     ::= (IDENTIFIER "=" assignment) | logicalOr
logicalOr      ::= logicalAnd ("||" logicalAnd)*
logicalAnd     ::= equality ("&&" equality)*
equality       ::= comparison (("==" | "!=") comparison)*
comparison     ::= term (("<" | ">" | "<=" | ">=") term)*
term           ::= factor (("+" | "-") factor)*
factor         ::= unary (("*" | "/" | "%") unary)*
unary          ::= ("!" | "-") unary | call
call           ::= primary ("(" arguments? ")")?
arguments      ::= expression ("," expression)*
primary        ::= INT_LITERAL | FLOAT_LITERAL | STRING_LITERAL | BOOLEAN_LITERAL
                  | IDENTIFIER | "(" expression ")"
```
