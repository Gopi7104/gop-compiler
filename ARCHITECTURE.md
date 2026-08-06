# Architecture

This document explains how GopiLang's compiler and VM are put together: the pipeline, the responsibility of every package, and the major classes within each.

## The Pipeline

```
Gopi Source (.gopi)
        │
        ▼
      Lexer                source text → List<Token>
        │
        ▼
      Parser                tokens → AST (Program)
        │
        ▼
Semantic Analyzer            AST → SemanticModel (scope/type/reachability/definite-assignment checks)
        │
        ▼
 Bytecode Generation          AST + SemanticModel → BytecodeModule
        │
        ▼
  Virtual Machine             executes a BytecodeModule
```

Each arrow is a real, separate stage with its own class and (where relevant) its own diagnostic-reporting mechanism. No stage reaches backward into an earlier one, and — critically — no stage re-validates something an earlier stage already guaranteed. By the time bytecode generation runs, the AST is known type-correct and every identifier is known resolved; by the time the VM runs, the bytecode is assumed valid. This is a deliberate design principle throughout the codebase, not an accident of how it was built incrementally.

Package layout mirrors this pipeline directly — one package per stage, plus a few cross-cutting concerns used by multiple stages.

---

## `lexer` — Source Text → Tokens

**Responsibility**: turn raw `.gopi` source text into a flat sequence of tokens.

- **`Lexer`** — hand-written character-by-character scanner. Recognizes keywords, identifiers, literals (`num`, `dec`, `text`, `flag`), operators, and punctuation. Skips whitespace and `//` line comments. Recovers from a bad character or malformed literal via panic-mode: it catches its own `LexerException`, converts it to a `Diagnostic`, and resumes scanning from the next character — so one bad token doesn't abort the whole file.
- **`Token`** — one lexical unit: its `TokenType`, lexeme text, optional literal value, and source location.
- **`TokenType`** — the closed enum of every token kind the lexer can produce.
- **`LexerException`** — an internal, phase-local control-flow signal (extends `GopiError`) thrown to unwind out of a broken token and caught immediately within `Lexer` itself. It never escapes the lexer.

---

## `parser` — Tokens → AST

**Responsibility**: turn a token stream into a `Program` (the root AST node), with no parser-generator involved — every production is a hand-written recursive-descent method.

- **`Parser`** — one method per grammar production (see [LANGUAGE.md](LANGUAGE.md#grammar-overview) for the full grammar). Expression parsing is precedence-climbing: `parseAssignment → parseLogicalOr → parseLogicalAnd → parseEquality → parseComparison → parseTerm → parseFactor → parseUnary → parseCall → parsePrimary`, each level handling one precedence tier and delegating to the next for its operands. Recovers from a syntax error via panic-mode synchronization; `Parser.synchronize(insideBlock)` distinguishes recovering inside a block (where a stray `}` must not be consumed, since the enclosing block-parsing loop needs to see it) from recovering at the top level (where it must be consumed or the parser would hang).
- **`ParseError`** — the parser's internal control-flow signal, exactly analogous to `LexerException`: thrown to unwind out of a broken statement/expression, caught within `Parser`, converted to a `Diagnostic`, never escapes the phase.

---

## `ast` — The AST Node Types

**Responsibility**: define the tree shape every later stage consumes. Frozen for v1: sealed, immutable records, no `accept()`/Visitor pattern.

- **`ASTNode`** — sealed root: `Program`, `Parameter`, `FunctionDeclaration`, `Stmt`, `Expr`.
- **`Stmt`** (sealed, 7 concretes) — `BlockStatement`, `VariableDeclaration`, `IfStatement`, `WhileStatement`, `ReturnStatement`, `PrintStatement`, `ExpressionStatement`.
- **`Expr`** (sealed, 7 concretes) — `LiteralExpression`, `VariableExpression`, `GroupingExpression`, `UnaryExpression`, `BinaryExpression`, `AssignmentExpression`, `FunctionCallExpression`.
- **`Program`** — holds only `List<FunctionDeclaration>`; there are no top-level variables or statements. `FunctionDeclaration` is deliberately *not* part of `Stmt` (functions can't be nested).
- **`BinaryOperator`**, **`UnaryOperator`** — closed enums for each operator family.

Every operation over the AST (semantic analysis, `AstPrinter`, `CodeGenerator`) is a plain `switch` over these sealed hierarchies in its own class, relying on Java 21's exhaustive pattern-matching switch for the same "every case handled" guarantee a Visitor would give — without adding an `accept()` method to every node.

---

## `semantic` — Semantic Analysis

**Responsibility**: validate a parsed `Program` (scope, types, reachability, definite assignment) and produce a `SemanticModel` the rest of the pipeline can trust without re-checking.

- **`SemanticAnalyzer`** — two-pass by necessity. Pass 1 (`registerFunctions`) registers every function's signature into a flat table *before* any body is analyzed, so forward references and mutual recursion resolve correctly. Pass 2 walks each function body doing scope management, identifier resolution, statement-level type checking, and reachability analysis all in the same traversal (expression typing, `typeOf`, is the one standalone recursive method, since it's called from many statement contexts). Also runs `validateMainFunction()` — checking for a valid `none main()` — as a final whole-program check.
- **`SemanticModel`** — the analyzer's immutable output: a function table plus four node-keyed resolution/type maps. The function table is `String`-keyed (ordinary equality is correct there); the other four *must* use `IdentityHashMap` semantics, since two structurally-identical-but-distinct AST nodes (e.g. two separate `x` references) must never collide — the compact constructor rebuilds them as genuine `IdentityHashMap`s rather than using `Map.copyOf` (which would silently discard identity semantics, or throw on a real collision).
- **`Scope`** — a mutable, parent-linked chain, one per block. Deliberately mutable and short-lived (unlike the AST's immutability) since it only exists for the single resolution pass. Shadowing a variable from an enclosing scope is a reported error.
- **`TypeRules`** — pure static type-compatibility rules (assignability, binary/unary operator result types, printability), with zero dependency on `Scope`/`SemanticModel`/the AST — kept separate specifically so it's independently testable.
- **`FunctionSymbol`**, **`VariableSymbol`** — resolved-declaration records (name, type, declaration site) that `SemanticModel`'s maps point at.

Semantic analysis never throws — there is no `GopiError` subclass for this phase. An unresolved or invalid expression simply has no entry in `SemanticModel`'s maps ("absence = poison"), and every downstream check that reads an absent entry silently skips its own check rather than cascading a second diagnostic from the same root cause.

---

## `bytecode` — Code Generation & the Bytecode Data Model

**Responsibility**: lower a validated `Program` + `SemanticModel` into a `BytecodeModule` the VM can execute.

- **`Opcode`** — the closed instruction set: 34 opcodes across constants, locals, arithmetic, comparison, control flow, functions, I/O, and stack operations. Deliberately small compared to JVM bytecode — every type-specific decision (which numeric type, which operator variant) was already resolved by semantic analysis, so the instruction set itself doesn't need per-type opcode variants.
- **`Instruction`** — a fixed-size `(Opcode, int operand)` record. Every instruction is the same shape regardless of opcode (opcodes that take no operand simply carry an unused `0`), so jump/call targets are always plain instruction indices, directly addressable with no decoding pass — unlike JVM-style variable-length bytecode.
- **`BytecodeFunction`** — metadata only (name, parameter count, slot count, `codeStart`), never its own instruction list — every function's code lives in one shared, flat, program-wide instruction stream, addressed by index.
- **`BytecodeModule`** — the complete compiled program: a deduplicated constant pool, one `BytecodeFunction` per declared function, and the single shared instruction list. Defensively copies all three in its compact constructor.
- **`CodeGenerator`** — builder-style, mirroring `SemanticAnalyzer`/`SemanticModel`'s shape: mutable accumulators (constant pool with dedup via `constantIndex`, per-function local-slot map, function-index map) filled in while walking the AST, assembled into one immutable `BytecodeModule` at the end (`generate()`). `generate()` assigns every function a stable index in declaration order, emits the program's two-instruction entry stub (`CALL <index of main>; HALT` — always instructions 0 and 1, so the VM always knows where to start), then compiles each function in turn. Within a function, `compileFunction` assigns parameter and local-variable slots up front (before any instruction is emitted) and always emits a trailing unconditional `RETURN`, even when every path already returns, so a function never falls off the end of its own code. Forward jumps (`if`/`else`, `while`) use backpatching: a placeholder operand (`-1`) is emitted and remembered by instruction index, then overwritten in place (`instructions.set(index, new Instruction(...))`) once the true target is known — `Instruction`'s immutability means this replaces the list entry wholesale rather than mutating a field.

Never re-validates anything `SemanticModel` already resolved — a missing resolution at this stage means semantic analysis itself failed to catch something, and is treated as an internal invariant violation (`IllegalStateException`), not a user-facing diagnostic.

---

## `vm` — The Virtual Machine

**Responsibility**: execute a `BytecodeModule`.

- **`Frame`** — one call activation: the `BytecodeFunction` being run, its local-variable slots (sized by `slotCount()`), its own operand stack, and the return address to resume the caller at. Every active call gets its own `Frame`, which is what makes recursion work with no special-casing — two simultaneously-active calls to the same function simply have two distinct `Frame`s on the call stack.
- **`VirtualMachine`** — a `BytecodeModule`, a `Deque<Frame>` call stack, and a program counter (`pc`) indexing into the module's shared instruction list. `run()` is a fetch-decode-execute loop: fetch the instruction at `pc`, increment `pc`, dispatch on its opcode. `CALL` pops the callee's arguments off the caller's operand stack (last-pushed argument lands in the highest local slot, matching how `CodeGenerator` compiles arguments left-to-right), creates a new `Frame`, pushes it, and jumps to the callee's `codeStart`. `RETURN` pops the current frame, optionally carries one value back onto the (now-current) caller's operand stack, restores `pc` from the popped frame's return address, and terminates the program if no caller frame remains. Jump opcodes (`JMP`, `JMP_IF_FALSE`) simply overwrite `pc` with the instruction's operand — since `pc` is already incremented before the opcode dispatch runs, this assignment lands exactly on the target index with no extra bookkeeping.

---

## `printer` — Debugging Views Over Compiler Output

**Responsibility**: render intermediate compiler artifacts as human-readable text, without affecting compilation itself.

- **`AstPrinter`** — renders a `Program` as a Unicode tree. Deliberately external to the `ast` package, dispatching with a plain `switch` over the sealed `Stmt`/`Expr` hierarchies rather than adding an `accept()` method to the AST nodes.
- **`BytecodeDisassembler`** — renders a `BytecodeModule`'s constant pool, function table, and instruction list as plain text (used by `gopic --disassemble`). Reuses the `BytecodeModule` a real `CodeGenerator` run already produced; it never regenerates or re-derives anything.

---

## `errors` — Diagnostics

**Responsibility**: the one thing that crosses phase boundaries.

- **`Diagnostic`** — an immutable record (phase, source range, message, suggestion) with a `render()` method that draws a source-line-and-caret view.
- **`DiagnosticReporter`** — a simple collector each phase owns independently (`Lexer`, `Parser`, `SemanticAnalyzer` each have their own — merging multiple phases' diagnostics into one combined view is left to the CLI, not each phase's job).
- **`ErrorPhase`** — which phase a diagnostic came from (`LEXICAL`, `SYNTAX`, `SEMANTIC`, `TYPE`, `RUNTIME`).
- **`GopiError`** — the abstract base for phase-local control-flow exceptions (`LexerException`, `ParseError`). These are internal signals only: thrown to unwind out of a broken token/statement, caught immediately within the same phase, and converted to a `Diagnostic` at the catch site. They never escape the phase that threw them, and semantic analysis has no subclass at all (see the `semantic` section above).

---

## `types` and `util` — Shared, Dependency-Free Support

- **`types.PrimitiveType`** — the five primitive types (`INT`, `FLOAT`, `BOOL`, `STRING`, `VOID`), used by the AST, semantic analysis, and error messages alike.
- **`util.SourceLocation`** — a single (line, column) position.
- **`util.SourceRange`** — a (start, end) span, used by every AST node and every `Diagnostic` to point at exactly the source text involved.

Both packages have zero dependency on any pipeline stage — they're pure, reusable value types.

---

## `cli` — The Command-Line Entry Point

**Responsibility**: orchestrate the pipeline for a single `.gopi` file; add no compiler behavior of its own.

- **`GopiC`** — `main()` dispatches on the command-line arguments to one of four modes: run a program end-to-end (default), or inspect one intermediate stage (`--tokens`, `--ast`, `--disassemble`) without executing the VM. All four modes share `compileToBytecode()`, which runs lexer → parser → semantic analyzer → code generator in sequence, stopping and printing diagnostics (with a non-zero exit code) the moment any phase reports an error, and never letting a later phase see output from a failed earlier one.
