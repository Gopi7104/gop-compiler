# Contributing to GopiLang

Thanks for your interest in GopiLang. This project's mission is deep, first-principles understanding of compiler and VM construction — it's built incrementally and reviewed class-by-class, not optimized for shipping speed. Contributions are welcome, but please read this document first so your change fits the project's style and goals.

## Build

Requirements: **Java 21+** and **Maven 3.6+**.

```bash
mvn compile
```

The CLI wrapper script (`./gopic`) runs against `target/classes`, so re-run `mvn compile` after any source change before using `./gopic`.

## Run Tests

```bash
mvn test
```

Run a single test class:

```bash
mvn test -Dtest=ParserTest
```

Run a single test method (including inside a `@Nested` class):

```bash
mvn test -Dtest=ParserTest#elseAttachesToNearestIf
mvn test -Dtest='ParserTest$DanglingElse#elseAttachesToNearestIf'
```

Every change should leave `mvn compile` and `mvn test` both green. If you add a compiler feature, add a regression test alongside it (see `src/test/java/com/gopilang/` for the existing suites, organized by pipeline stage — `lexer`, `parser`, `semantic`).

## Coding Style

This codebase has a deliberate, consistent style — please match it rather than introducing a different one:

- **No parser generators, no code generation, no reflection in production code.** Every stage is hand-written on purpose; the point of this project is understanding each part directly.
- **Package layout mirrors pipeline stage, not feature.** `lexer/`, `parser/`, `ast/`, `semantic/`, `bytecode/`, `vm/` are pipeline stages; `types/`, `util/`, `errors/`, `printer/` are cross-cutting concerns used by multiple stages; `cli/` is the entry point. When adding to a stage, put new classes in the matching package — don't invent a new one for a single class unless it's a genuinely new stage.
- **AST nodes are sealed, immutable records — no Visitor/`accept()` pattern.** Every AST-consuming operation is a plain `switch` over the sealed `Stmt`/`Expr` hierarchy in its own class (see `AstPrinter`, `SemanticAnalyzer.typeOf`, or `CodeGenerator.compileExpr` for the pattern to follow). Java 21's exhaustive pattern-matching switch gives the "every case must be handled" guarantee a Visitor would, without adding a method to every node class.
- **Two error-handling mechanisms, used consistently.** `GopiError` and its subclasses (`LexerException`, `ParseError`) are internal, phase-local control-flow signals only — thrown to unwind out of a broken token/statement, caught within the same phase, and converted to a `Diagnostic` immediately. They must never escape the phase that threw them. `Diagnostic` is the only thing that crosses phase boundaries. Semantic analysis has no `GopiError` subclass at all: it never throws, because by the time it runs, the tree's structure is already valid, so a problem is recorded by simply omitting an entry from `SemanticModel`'s maps ("absence = poison") rather than throwing.
- **Builder-then-freeze, not incremental mutation of a shared object.** `SemanticAnalyzer`/`SemanticModel` and `CodeGenerator`/`BytecodeModule` both follow the same shape: mutable accumulator fields filled in while walking the AST, assembled into one immutable record at the very end. Follow this shape for any new multi-pass or multi-stage builder.
- **Identity-sensitive maps stay identity-sensitive.** `SemanticModel`'s four node-keyed maps use `IdentityHashMap` deliberately — two structurally-equal-but-distinct AST nodes (e.g. two separate occurrences of a variable named `x`) must never collide. If you add a new node-keyed map anywhere, default to `IdentityHashMap` unless you have a specific reason ordinary equality is correct (as it is for `SemanticModel.functionTable()`, which is name-keyed).
- **Comments explain WHY, not WHAT.** Default to no comment. Only add one when there's a genuinely non-obvious invariant, a subtle constraint, or a workaround for a specific bug — not to restate what a well-named method or class already says. Look at the existing inline comments in `SemanticAnalyzer.java`, `SemanticModel.java`, and `CodeGenerator.java` for the target tone.
- **No premature abstraction.** Don't add a new class, interface, or configuration knob for something the current milestone doesn't need. Three similar lines are better than a shared helper built for a hypothetical future case.
- **One step at a time.** This project was built and reviewed incrementally, one class or one opcode at a time, with the real compiler exercised (not just reasoned about) after each step. If you're adding a nontrivial feature, prefer a similarly incremental PR sequence over one large change.

## Project Structure

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full pipeline and package-by-package breakdown, and [LANGUAGE.md](LANGUAGE.md) for what the language currently supports. In short:

```
src/main/java/com/gopilang/
├── lexer/      source text → tokens
├── parser/     tokens → AST
├── ast/        sealed AST node records
├── semantic/   scope/type/reachability/definite-assignment checking
├── bytecode/   AST → BytecodeModule (Opcode, Instruction, CodeGenerator, ...)
├── vm/         executes a BytecodeModule (Frame, VirtualMachine)
├── printer/    AstPrinter, BytecodeDisassembler
├── errors/     Diagnostic, DiagnosticReporter, ErrorPhase, GopiError
├── types/      PrimitiveType
├── util/       SourceLocation, SourceRange
└── cli/        GopiC (command-line entry point)
```

Example `.gopi` programs live in `examples/`, with `examples/semantic/` specifically containing one small program per semantic diagnostic category — useful both as regression material and as a quick way to see what a given check catches.

## Known, Tracked Gaps

Not bugs to silently work around — see the [README's roadmap](README.md#future-roadmap) before assuming something is broken:

- `&&`/`||` are fully parsed and type-checked but not yet compiled to bytecode (short-circuit evaluation needs conditional jumps interleaved with the right operand, unlike every other binary operator).
- Bytecode serialization (`BytecodeWriter`/`BytecodeReader`) doesn't exist yet — a `BytecodeModule` only ever exists in memory within one process run.
