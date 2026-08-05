# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GopiLang: a from-scratch programming language ecosystem, implemented entirely in hand-written Java 21 (no parser generators — lexer and parser are hand-written recursive descent). The mission is deep, first-principles understanding of compiler construction, built incrementally and reviewed class-by-class; it is not a "ship it fast" project. The long-term (not-yet-started) goal is to rewrite the compiler in GopiLang itself for self-hosting.

Pipeline: `Gopi Source (.gopi) → Lexer → Parser/AST → Semantic Analyzer → Bytecode Generator (not built) → Gopi VM (not built) → Output`

## Commands

- Build: `mvn compile`
- Run all tests: `mvn test`
- Run one test class: `mvn test -Dtest=ParserTest`
- Run one test method: `mvn test -Dtest=ParserTest#elseAttachesToNearestIf` (nested `@Nested` classes: `mvn test -Dtest='ParserTest$DanglingElse#elseAttachesToNearestIf'`)
- CLI tool (after `mvn compile`): `./gopic --tokens <file.gopi>` prints the lexer's token stream; `./gopic --ast <file.gopi>` prints the parsed AST as a Unicode tree, both followed by rendered diagnostics if any phase produced errors
- Sample programs live in `examples/*.gopi`

There is no build step wiring semantic analysis into the CLI yet — `gopic` currently only exposes `--tokens` and `--ast`.

## Architecture

### Package layout mirrors pipeline stage, not feature

`lexer/`, `parser/`, `ast/`, `semantic/`, `types/`, `util/`, `errors/`, `printer/`, `cli/` — each package is one stage of the pipeline (or a cross-cutting concern used by multiple stages: `types`, `util`, `errors`). When adding to a stage, match the existing package.

### Error/diagnostic architecture — two parallel hierarchies, don't confuse them

- **`GopiError` (abstract, extends `RuntimeException`)** and its subclasses `LexerException`, `ParseError` are **internal, phase-local control-flow signals only** — thrown to unwind out of a broken token/statement, caught immediately within the same phase, and converted to a `Diagnostic` at the catch site. They never escape the phase that threw them. Semantic analysis has no `GopiError` subclass at all — it never throws, because by the time it runs the tree's structure is already known-valid, so every semantic problem is locally recoverable (see poison-type note below).
- **`Diagnostic`** (a plain immutable record: phase, range, message, suggestion) is the only thing that crosses phase boundaries. Each phase (`Lexer`, `Parser`, `SemanticAnalyzer`) owns its own `DiagnosticReporter` (self-contained, not injected — merging multiple phases' diagnostics into one view is a future compiler-driver concern, not each phase's job).
- Recovery strategy differs by phase and is deliberate, not incidental: lexer/parser use panic-mode (`Parser.synchronize(insideBlock: boolean)` — the boolean distinguishes recovering inside a block, where a stray `}` must NOT be consumed since the enclosing loop needs to see it, from recovering at the top level, where it must be consumed or the parser hangs). Semantic analysis uses no synchronization at all — an unresolved/invalid expression simply gets no entry in `SemanticModel`'s maps (absence = poison), and every downstream check that reads an absent entry silently skips its own check rather than reporting a cascade. This is why one root cause never produces more than one diagnostic, and it's why `ErrorPhase.TYPE` diagnostics (type mismatches) are distinct from `ErrorPhase.SEMANTIC` ones (resolution failures, duplicate/shadowed declarations).

### AST — frozen for v1, sealed, no Visitor pattern

`ASTNode` (sealed: `Program, Parameter, FunctionDeclaration, Stmt, Expr`), `Stmt` (sealed, 7 concretes), `Expr` (sealed, 7 concretes) are all immutable records. Every AST-consuming operation (semantic analysis, `AstPrinter`, eventually bytecode generation) is a plain `switch` over the sealed hierarchy in its own class — deliberately not a Visitor/`accept()` pattern, since Java 21's exhaustive pattern-matching switch gives the same "every case must be handled" guarantee for free. When adding a new operation over the AST, follow this pattern (see `AstPrinter` or `SemanticAnalyzer.typeOf`/`analyzeStatement` for examples), not a new `accept()` method on the nodes.

`FunctionDeclaration` is intentionally *not* part of the `Stmt` hierarchy (nested functions are disallowed — it only ever appears in `Program.functions()`). `Program` holds only `List<FunctionDeclaration>` — no top-level variables or statements; GopiLang requires a `void main()` entry point, validated by `SemanticAnalyzer.validateMainFunction()` (not the parser — parsing `void main(){}` and `void foo(){}` is structurally identical, so "does this program have a valid entry point" is a whole-program semantic question, not a syntactic one).

### Semantic analysis is multi-pass by necessity, and never mutates the AST

`SemanticAnalyzer` runs two passes over `Program`: Pass 1 (`registerFunctions`) registers every function's signature into a flat, name-keyed table *before* any body is analyzed, which is what makes forward references and mutual recursion between functions resolve correctly. Pass 2 (`analyzeFunction`/`analyzeStatement`/`analyzeExpr`) walks each body doing scope management and identifier resolution, and is *also* where statement-level type checking and reachability analysis are woven in (extending the same switch, not a separate traversal) — only expression typing (`typeOf`) is its own standalone recursive method, since it's invoked from many different statement contexts.

Its output, `SemanticModel`, is a separate immutable record (function table, variable/assignment-target/call resolutions, expression types) — never written onto the AST nodes. This is why the maps use two different `Map` implementations and it matters which: `functionTable` is `String`-keyed (ordinary equality is correct — two occurrences of the name "add" should collide) and built with `Map.copyOf`; the other four maps are keyed by AST node instances and **must** use `IdentityHashMap` semantics (two structurally-equal-but-distinct nodes, e.g. two `VariableExpression("x", sameRange)` at different source occurrences, must not collide) — note that `Map.copyOf()` on an `IdentityHashMap` silently discards identity semantics (or throws `IllegalArgumentException: duplicate key` if a real collision exists), so `SemanticModel`'s compact constructor rebuilds those four via `new IdentityHashMap<>(source)` + `Collections.unmodifiableMap`, not `Map.copyOf`.

`TypeRules` (pure static methods, zero dependency on `Scope`/`SemanticModel`/the AST node hierarchy — only `PrimitiveType`/`BinaryOperator`/`UnaryOperator`) holds the actual type-compatibility table and is deliberately separate from `SemanticAnalyzer` for independent testability.

`Scope` is a mutable, parent-linked chain (one `Scope` per block), intentionally mutable/non-persistent — narrowly-scoped, single-traversal mutation, not shared or long-lived, unlike the AST. Shadowing a variable from an enclosing scope is a reported error, not silently allowed (Java-style, not C-style).

### Definite assignment tracks state on `SemanticAnalyzer`, never on `VariableSymbol`

`SemanticAnalyzer.currentAssigned` (a `Set<VariableSymbol>`, identity-based via `Collections.newSetFromMap(new IdentityHashMap<>())` — required, not just safer, for the same shadowing reason as `SemanticModel`'s maps) tracks which symbols are known-assigned on the current path. It is mutable field state exactly like `currentScope`, not a field added to `VariableSymbol` itself — mutating a value shared across multiple frozen maps would reintroduce the aliasing risk immutability elsewhere in this codebase exists to prevent. Branch merging at `if`/`else` intersects the then/else branches' independent snapshots (assigned only if *both* branches assigned it, or there's no `else` at all — mirroring reachability's "no else, never guaranteed" rule); `while` conservatively discards anything the body assigns, uniformly, with no `while(true)` special case (unlike reachability, which does special-case it for returns).

### Current implementation state

Lexer, Parser+AST, and Semantic Analysis (function registration, scope/identifier resolution, type checking, reachability analysis, definite assignment, `main()` signature validation) are complete, with a comprehensive regression suite (`SemanticAnalyzerTest`, organized by category) and worked examples in `examples/semantic/`. **Not yet implemented:** bytecode generation and the VM. A known, tracked gap for whenever bytecode generation starts: the bytecode instruction set (designed but not yet implemented in code) needs a `DUP` opcode for chained assignment (`a = b = 5`), which isn't in the original design.
