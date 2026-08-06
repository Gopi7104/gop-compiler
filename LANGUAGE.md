# The GopiLang Language

GopiLang is a small, statically-typed, C-family language. This document describes the language as currently implemented — every example here compiles and runs on the current compiler.

## Keywords

Every reserved word in GopiLang, at a glance (see the linked section for full details on each):

| Keyword | Meaning |
|---------|---------|
| `num`   | 32-bit signed integer type ([Types](#types)) |
| `dec`   | double-precision floating point type ([Types](#types)) |
| `flag`  | boolean type ([Types](#types)) |
| `text`  | string type ([Types](#types)) |
| `none`  | "no value" — function return type only ([Types](#types)) |
| `yes` / `no` | boolean literals ([Types](#types)) |
| `if` / `else` | conditional branching ([`if` / `else`](#if--else)) |
| `loop`  | while-loop ([`loop`](#loop)) |
| `run`   | C-style for-loop, desugared to `loop` ([`run`](#run)) |
| `new`   | array creation (`new elementType[size]`) ([Arrays](#arrays)) |
| `struct` | struct declaration ([Structs](#structs)) |
| `give`  | return statement ([Functions](#functions)) |
| `show`  | print statement ([`show`](#show)) |

## Comments

Line comments only, starting with `//` and running to end of line:

```
// this is a comment
num x = 5; // and so is this
```

There is no block-comment syntax.

## Types

Five primitive types:

| Type     | Description                          | Example literal |
|----------|---------------------------------------|------------------|
| `num`    | 32-bit signed integer                 | `42`             |
| `dec`    | double-precision floating point       | `3.14`           |
| `flag`   | boolean                               | `yes`, `no`      |
| `text`   | text                                  | `"hello"`        |
| `none`   | "no value" — only valid as a function return type | — |

There is no implicit narrowing: a `num` value can be used where a `dec` is expected (it's widened automatically), but not the reverse. `none` cannot be used as a variable's type or as a value.

String literals support the escape sequences `\n`, `\t`, `\"`, and `\\`. Strings cannot span multiple lines.

## Arrays

Any type except `none` can be made into an array type by appending `[]`:

```
num[] numbers;
text[] names;
flag[] values;
```

An array is created with `new elementType[size]`, where `size` is any `num`-typed expression (not necessarily a literal):

```
num[] numbers = new num[5];
num n = 5;
text[] names = new text[n];
```

Elements are read and written with `[index]`, where `index` must be `num`:

```
numbers[0] = 10;
num first = numbers[0];
```

Indexed assignment is itself an expression (like plain variable assignment), so it can be chained or nested:

```
numbers[0] = numbers[1] = 5;   // both become 5
```

An array's length is read with `.len()`:

```
show(numbers.len());
```

Arrays can be passed to and returned from functions like any other type:

```
num sum(num[] values) {
    num total = 0;
    num i = 0;
    loop (i < values.len()) {
        total = total + values[i];
        i = i + 1;
    }
    give total;
}
```

Assigning one array-typed value to another requires an **exact** type match — there is no `num[]`-to-`dec[]` widening the way there is for plain `num`-to-`dec`:

```
dec[] a = new num[3];   // compile-time error: cannot assign 'num[]' to variable of type 'dec[]'
```

Arithmetic and comparison operators do not accept array operands (`a + b` where `a`/`b` are arrays is a compile-time error), and an array cannot be passed directly to `show(...)` — only its elements or its `.len()`.

There is no array-literal syntax (e.g. `[1, 2, 3]`) — every array is created with `new`, then filled in by assignment.

## Structs

**Status: a real, nominal type — declaration, resolution, and full type-checking are implemented; construction and field access are not.** A struct name may be used anywhere a type is legal — a variable's type, a parameter's type, a function's return type, or another struct's field type — and (as of Milestone S3) is properly integrated into the type system: assignability, argument/return compatibility, and printability all understand structs, nominally (by name, never by field-shape).

```
struct Point {
    num x;
    num y;
}

none identity(Point p) {
    give p;
}

none main() {
    Point p;          // legal: a real, resolved struct type
}
```

Each field is a type and a name, exactly like a function parameter (`type IDENTIFIER;`), and — unlike a plain variable/parameter/return type — a field may itself be struct-typed or array-of-struct-typed (subject to the cycle rule below). A struct may have zero or more fields, and multiple structs may be declared in one file, in any order (forward references resolve).

Currently supported:
- Declaring a struct with any number of fields, each either a primitive type or a struct type (including the struct's own name — see the recursion rule below)
- Duplicate struct names, and duplicate field names within one struct, are compile-time errors
- A struct name may be reused as a function name with no conflict — structs and functions are resolved in separate contexts
- A struct name is a legal variable type, parameter type, return type, or field type, and (with `[]`) so is an array of that struct — `Point p;`, `Point[] points;`, `none foo(Point p);`, `Point[] make();` are all valid
- **Nominal typing**: two structs with identical fields are still incompatible types — `Point`-typed and `Line`-typed values are never interchangeable just because their fields happen to match
- Assignment, argument passing, and return values all require an exact struct-name match (or an exact array-of-that-struct match — arrays never widen, matching primitive arrays); referencing an undeclared struct name as a type is a compile-time error ("undefined struct 'X'")
- **Cycle detection**: a struct cannot contain itself, directly or indirectly, through a non-array field (`struct Node { Node next; }` and `struct A { B b; } struct B { A a; }` are both compile-time errors), but *can* reference itself through an array field (`struct Node { Node[] children; }` is legal) — an array is a runtime reference, not inline storage, so it can't create an unbounded-size struct
- A struct value cannot be printed (`show(...)`) and cannot be used as an operand to any arithmetic, comparison, or logical operator

**Explicitly not yet supported** (planned for later milestones, not oversights):
- There is no struct construction syntax (no `new Point(...)` yet) — the only way to obtain a definitely-assigned value of a struct type in a program today is a function parameter
- There is no field access (`point.x`) or field assignment (`point.x = 5`) yet

## Variables

Declared with an explicit type, optionally with an initializer:

```
num x = 5;
num y;        // declared, not yet assigned
y = 10;       // now assigned
```

Reading a variable before it has definitely been assigned on every path leading to that read is a compile-time error (definite-assignment checking) — this is checked independently of whether the variable had an initializer.

A variable declared in an inner scope may not shadow a variable of the same name from an enclosing scope — this is a compile-time error, not a warning (Java-style, not C-style shadowing rules).

## Operators

**Arithmetic**: `+` `-` `*` `/` `%`
`+` is overloaded: `num`/`dec` operands add numerically; two `text` operands concatenate. Mixing `num` and `dec` operands in any arithmetic operator produces a `dec` result. `%` requires both operands to be `num`.

**Comparison**: `==` `!=` `<` `>` `<=` `>=`
`==`/`!=` work on any two operands of compatible type (numeric-vs-numeric, `flag`-vs-`flag`, `text`-vs-`text`, by value for strings). `<` `>` `<=` `>=` are numeric-only.

**Logical**: `&&` `||` `!`
Both operands of `&&`/`||` must be `flag`. All three are fully implemented, including short-circuit evaluation for `&&`/`||`, identical to Java/C: `a && b` never evaluates `b` if `a` is already `no`, and `a || b` never evaluates `b` if `a` is already `yes`.

```
flag isValid(num x) {
    give x > 0 && x < 100;
}
```

See [`short_circuit.gopi`](examples/short_circuit.gopi) for a worked example showing exactly which calls do and don't happen.

**Assignment**: `=`
Assignment is itself an expression (not just a statement), and is right-associative, so chained assignment works:

```
num a;
num b;
a = b = 5;   // both a and b become 5
show(a);     // 5
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
    show(1);
} else {
    show(-1);
}
```

`else` is optional. A dangling `else` always attaches to the nearest preceding `if`. Braces are required around each branch's body in the current grammar (a branch is always a `{ ... }` block in practice, even though the AST technically allows any single statement).

## `loop`

```
num i = 0;
loop (i < 5) {
    show(i);
    i = i + 1;
}
```

There is no `do`/`while`, `break`, or `continue`.

## `run`

A C-style for-loop, with all three clauses mandatory:

```
run (num i = 0; i < 5; i = i + 1) {
    show(i);
}
```

`run` is pure syntactic sugar — the parser desugars it immediately into a `loop`, with no separate AST node, bytecode, or VM behavior of its own:

```
run (init; condition; increment) body
   ≡
{
    init;
    loop (condition) {
        body
        increment;
    }
}
```

The outer block means the init variable is scoped to the loop only (Java/C99-style), so two consecutive `run` loops may reuse the same variable name without conflict. The init clause may either declare a fresh variable (`num i = 0`) or reuse an existing one (`i = 0`), since both are just ordinary statements to the desugaring:

```
num i;
run (i = 0; i < 5; i = i + 1) {
    show(i);
}
```

Because `run` becomes an ordinary `loop` before semantic analysis ever runs, its condition is checked exactly like `loop`'s (a diagnostic for a non-`flag` condition will describe it as a "while condition", since the analyzer genuinely does not know the source spelled it `run`) and `while (true)`'s reachability special-case applies identically to `run (init; yes; increment) { give ...; }`.

## Functions

Every function has an explicit return type, a name, a parenthesized parameter list (each parameter with an explicit type), and a `{ ... }` body:

```
num add(num a, num b) {
    give a + b;
}
```

`give` may appear with or without a value, and every non-`none` function must return a value on every reachable path (checked by reachability analysis at compile time). Functions cannot be nested — a function may only be declared at the top level of a program, never inside another function's body.

Every GopiLang program must declare exactly one function named `main`, with return type `none` and no parameters — this is the program's entry point, and its absence (or a wrong signature) is a semantic error.

## Recursion

Functions may call themselves, directly or indirectly, with no special syntax:

```
num factorial(num n) {
    if (n <= 1) {
        give 1;
    } else {
        give n * factorial(n - 1);
    }
}
```

Each active call gets its own independent set of local variables at runtime — recursion works because of how the VM's call stack is structured, not because of anything special in the language grammar.

## `show`

```
show(expression);
```

A built-in statement (not a function) that evaluates its expression and writes its value to standard output followed by a newline. Any printable type (`num`, `dec`, `flag`, `text`) may be printed; `none` cannot.

## Grammar Overview

Informal EBNF-style summary of the current grammar (see [`Parser.java`](src/main/java/com/gopilang/parser/Parser.java) for the authoritative hand-written recursive-descent implementation):

```
program        ::= (functionDecl | structDecl)* EOF

structDecl     ::= "struct" IDENTIFIER "{" field* "}"
field          ::= declaredType IDENTIFIER ";"         // identical shape to `parameter` below

functionDecl   ::= declaredType IDENTIFIER "(" parameters? ")" block
parameters     ::= parameter ("," parameter)*
parameter      ::= declaredType IDENTIFIER
declaredType   ::= type | IDENTIFIER ( "[" "]" )?
                  // IDENTIFIER = a struct name used as a type, e.g. "Point" or "Point[]" —
                  // the ONE type production used for every type position (field,
                  // parameter, return type, variable); real, nominal struct types since
                  // Milestone S3
type           ::= elementType ( "[" "]" )?
elementType    ::= "num" | "dec" | "flag" | "text" | "none"

block          ::= "{" statement* "}"
statement      ::= variableDecl | ifStmt | whileStmt | forStmt | returnStmt
                  | printStmt | exprStmt | block
                  // A statement starting with IDENTIFIER is ambiguous: "Point p;" (a
                  // struct-typed variableDecl) vs "p = 5;"/"foo();" (an exprStmt) vs, as
                  // of Milestone S3, "Point[] arr;" (also a variableDecl) vs "arr[i] = 5;"
                  // (also an exprStmt) — both pairs share the same first one or two
                  // tokens. Parser.isStructTypedDeclarationStart() resolves both with a
                  // small, fixed lookahead (at most 3 tokens, no backtracking): two
                  // IDENTIFIERs in a row, or IDENTIFIER "[" "]" (an index expression can
                  // never be empty, so that shape is unambiguously a type suffix)

variableDecl   ::= declaredType IDENTIFIER ("=" expression)? ";"
ifStmt         ::= "if" "(" expression ")" statement ("else" statement)?
whileStmt      ::= "loop" "(" expression ")" statement
// forStmt is not its own AST node - the parser desugars it directly into
// { forInit "loop" "(" expression ")" "{" statement expression ";" "}" },
// i.e. an ordinary block/loop/block, before semantic analysis ever runs.
forStmt        ::= "run" "(" forInit expression ";" expression ")" statement
forInit        ::= variableDecl | expression ";"
returnStmt     ::= "give" expression? ";"
printStmt      ::= "show" "(" expression ")" ";"
exprStmt       ::= expression ";"

expression     ::= assignment
assignment     ::= (assignmentTarget "=" assignment) | logicalOr
assignmentTarget ::= IDENTIFIER | IDENTIFIER "[" expression "]"
logicalOr      ::= logicalAnd ("||" logicalAnd)*
logicalAnd     ::= equality ("&&" equality)*
equality       ::= comparison (("==" | "!=") comparison)*
comparison     ::= term (("<" | ">" | "<=" | ">=") term)*
term           ::= factor (("+" | "-") factor)*
factor         ::= unary (("*" | "/" | "%") unary)*
unary          ::= ("!" | "-") unary | call
call           ::= primary ( "(" arguments? ")" | "[" expression "]" | "." "len" "(" ")" )?
arguments      ::= expression ("," expression)*
primary        ::= INT_LITERAL | FLOAT_LITERAL | STRING_LITERAL | BOOLEAN_LITERAL
                  | IDENTIFIER | "(" expression ")" | "new" elementType "[" expression "]"
```

`assignmentTarget` isn't parsed as its own production in practice — `parseAssignment()` parses the left side as an ordinary expression first, then checks whether it turned out to be a plain identifier or an array index before treating the following `=` as an assignment; anything else followed by `=` is a parse error ("invalid assignment target").
