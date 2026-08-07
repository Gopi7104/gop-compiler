# GopiLang — What It Is, Why It's Different, and What Problem It Actually Solves

This document answers, in plain English: *what is unique about this compiler, what problem does it solve that others don't, what's wrong with other compilers, and how does this one avoid those mistakes.* It is written so a beginner can follow it, with diagrams where they help.

---

## 1. First, be honest about what "problem" GopiLang solves

GopiLang is **not** trying to beat Python, Java, Go, or Rust at running programs fast, having more libraries, or being more popular. It has no library ecosystem, no package manager, and a deliberately tiny feature set. If the question is "should I use GopiLang to build a real product?" — the answer is no, and it isn't trying to be that.

The actual problem GopiLang solves is:

> **"Most people who use a compiler never see inside one — and most people who try to build one get lost in complexity before they understand it."**

Real-world compilers (GCC, the JVM, V8, Roslyn) are millions of lines of code, built over decades, with performance tricks, generated code, and legacy-compatibility layers stacked on top of each other. Reading their source teaches you almost nothing about the *core ideas* of how a compiler works, because the core ideas are buried under 30 years of extra machinery.

GopiLang's actual "product" is: **a compiler + VM small enough to read start to finish in an afternoon, but complete enough that it isn't a toy** — it really does lex, parse, type-check, generate bytecode, and execute it, for a language with real functions, recursion, arrays, and structs. That is the unique thing. It solves the "teach me how a compiler actually works" problem better than either (a) reading production compiler source, or (b) reading a textbook without ever running real code.

---

## 2. The pipeline — the shape every real compiler has, made visible

```
 Gopi Source (.gopi)
        │
        ▼
  ┌───────────┐   tokenizes text, catches things like unterminated strings
  │   Lexer   │
  └───────────┘
        │  tokens
        ▼
  ┌───────────┐   hand-written recursive descent, no generator tool
  │  Parser   │   builds the AST, catches syntax errors
  └───────────┘
        │  AST (tree of the program's structure)
        ▼
  ┌───────────────────┐  checks that every variable/function exists,
  │ Semantic Analyzer  │  every type matches, every path returns a value,
  └───────────────────┘  every variable is assigned before use
        │  SemanticModel (facts about the AST, not stored on the AST)
        ▼
  ┌────────────────┐   turns the AST into a flat list of simple instructions
  │ Code Generator  │
  └────────────────┘
        │  BytecodeModule (constants + functions + structs + instructions)
        ▼
  ┌───────────────────┐  a simple stack machine that runs the instructions
  │  Virtual Machine   │
  └───────────────────┘
        │
        ▼
      Output
```

Every real compiler — GCC, javac, the Python bytecode compiler — has this same shape underneath. GopiLang's uniqueness isn't that it invented a new shape; it's that **every one of these boxes is small enough to read entirely, and each box does exactly what it says and nothing else.**

---

## 3. What's actually wrong with a lot of other compilers/interpreters (including ones students build)

These are real, common failure modes in compiler projects — some show up in toy student compilers, some are genuine historical pain points in production languages:

| Problem | What goes wrong | Where you see it |
|---|---|---|
| **Cascading errors** | One typo produces 40 error messages instead of 1, because the error-recovery logic doesn't actually stop the bad state from spreading. | Classic issue in early C/C++ compilers and most "my first compiler" student projects. |
| **The AST gets mutated as you go** | Different passes (type-checking, optimization) write directly onto the tree nodes. Later, nobody can tell which pass set which field, and bugs appear when passes run in the wrong order or twice. | Many hand-rolled interpreters, and historically some parts of production compilers before they moved to immutable IR. |
| **Aliasing bugs from shared mutable state** | Two different occurrences of the same variable name accidentally get treated as "the same object" because of how hash maps or equality were implemented, corrupting analysis. | Common in scope-resolution code that uses ordinary `equals()`-based maps instead of identity. |
| **Errors and control flow are the same mechanism** | Exceptions are used both to unwind out of a broken statement *and* to represent a user-facing compile error, so an internal signal accidentally "escapes" and crashes the whole tool instead of printing a clean message. | Very common in first-attempt compilers. |
| **No clear separation between "the language's rules" and "how we implement them"** | Type-compatibility rules get scattered across many files, so changing one rule means hunting through the whole codebase and often breaking something else. | Common as codebases grow organically. |
| **The VM and the compiler know too much about each other** | The bytecode format changes and the VM breaks in subtle ways because nothing enforced a clean boundary between "what code generation promises" and "what the VM assumes." | Common cause of "works on my compiler, crashes at runtime" bugs. |
| **Big, over-engineered from day one** | New language projects often build a plugin system, multiple backends, or a generic IR before the basic four stages even work, and never finish. | Extremely common reason side-project languages die. |

---

## 4. How GopiLang's design specifically avoids each of these

### 4.1 One root cause → one error message ("poison" instead of cascading)

Most compilers, when they hit a broken expression, either crash or try to "guess and continue," which causes one mistake to produce a wall of fake follow-on errors.

GopiLang's semantic analyzer instead uses what the codebase calls **"absence = poison."** If an expression can't be resolved (e.g. it uses an undefined variable), the analyzer doesn't invent a fallback value — it simply *doesn't record an entry* for that expression. Every later check that depends on that expression sees "no information here" and silently skips its own check, instead of reporting a second, third, and fourth error about the same root problem.

```
   x = undefinedVar + 1;
        │
        ▼
  "undefinedVar" fails to resolve → 1 diagnostic reported
        │
        ▼
  the whole expression "undefinedVar + 1" gets NO type recorded
        │
        ▼
  every later check (e.g. "is this assignable to x?") sees nothing
  recorded → skips silently → NO second/third fake error
```

This is a deliberate design decision, not an accident — it's why one typo produces one clear message instead of a cascade.

### 4.2 Two clearly separate error mechanisms, never mixed

GopiLang keeps two *completely separate* systems that other projects often blur together:

- **`GopiError`** (and its subclasses) — used only *inside* a single phase, to unwind out of a broken token or statement. It is caught immediately, in the same phase, and converted into a diagnostic. It is never allowed to leak out.
- **`Diagnostic`** — a plain, calm data record (phase, location, message, suggestion) that is the *only* thing allowed to cross from one phase to another.

By the time semantic analysis runs, the tree's shape is already guaranteed valid — so semantic analysis doesn't even *have* an exception type. Every semantic problem is locally recoverable using the poison-entry trick above. This split means a bug in "how we recover from a broken statement" can never accidentally masquerade as a user-facing compile error, and vice versa.

### 4.3 The AST is never mutated — every pass produces its own separate result

Instead of writing type information, resolved-variable information, etc. directly onto the AST nodes (which is what causes "which pass touched this field, and in what order" bugs), GopiLang's semantic analyzer produces one separate, **immutable** result object — `SemanticModel` — that maps AST nodes to facts about them. The AST itself is never touched. Code generation reads from `SemanticModel`, never re-derives or re-checks anything the semantic analyzer already decided.

```
        AST (never changes, ever)
         │                  │
         │ read-only        │ read-only
         ▼                  ▼
  SemanticAnalyzer  →  SemanticModel  →  CodeGenerator  →  BytecodeModule
   (builds facts)      (frozen facts)     (reads facts,       (frozen result)
                                            builds bytecode)
```

Each stage is "builder, then freeze" — a plain, name-obvious pattern (mutable accumulator while walking the tree, then one immutable object at the end) used consistently everywhere, so every stage is predictable and safe to reason about in isolation.

### 4.4 Identity bugs are prevented on purpose, not by luck

If you resolve variables using a normal hash map keyed by "does this look the same," two *different* occurrences of a variable named `x` in different places can accidentally collide and corrupt the analysis. GopiLang's node-keyed maps deliberately use **identity-based** maps (`IdentityHashMap`) everywhere a specific *occurrence* of a node matters — this is called out explicitly as a rule new code must follow, precisely because this is such an easy category of bug to introduce by accident.

### 4.5 The type-compatibility rules live in exactly one place

All the "is type A allowed where type B is expected" logic lives in a single class (`TypeRules`) with no dependency on the rest of the compiler. Every other stage calls into it rather than re-implementing its own version of "are these types compatible." This is the opposite of the common failure mode where type rules get copy-pasted and drift out of sync across a codebase.

### 4.6 A clean, frozen boundary between compiler and VM

The compiler's output is one plain, immutable object: constants + functions + structs + a flat instruction list, using a fixed, small instruction shape (opcode + one operand, no variable-length encoding tricks). The VM only ever reads this object — it never re-derives anything the compiler already decided, and it treats any inconsistency as an *internal bug* (a thrown `IllegalStateException`), not a user-facing error, because if this happens, an earlier phase failed to catch something it was supposed to catch. This means the compiler and the VM can each be understood completely on their own, without needing to hold the other one's internals in your head at the same time.

### 4.7 Deliberately small scope, grown one piece at a time

The project rule is explicit: *no premature abstraction, no feature the current milestone doesn't need, one class or one opcode at a time, with the real thing actually run after every step* — not just reasoned about on paper. This is precisely the discipline that most hobby-language projects skip, which is why most hobby-language projects never reach a working end-to-end pipeline. GopiLang's current instruction set is famously small — 30 opcodes total — because every type-specific decision has already been resolved earlier, by semantic analysis, so the bytecode layer doesn't need per-type variants of anything.

---

## 5. A concrete example: how a struct works, end to end

Structs are a good example of the whole philosophy, because they touch every stage without needing a single new runtime concept:

```
struct Point { num x; num y; }

Point p = new Point(1, 2);
show(p.x);
```

- **Parser**: sees `new Point(...)` and `p.x` as ordinary syntax, no special-cased "struct" grammar beyond recognizing a name.
- **Semantic analyzer**: checks `Point` exists, checks the constructor arguments match the field types, checks `.x` is a real field of `Point`, and records the *type* of `p.x` — exactly the same machinery used for every other expression.
- **Code generator**: a struct is compiled as nothing more than a plain array (`Object[]`) with one slot per field, in declaration order. `p.x` compiles to "push field index; array-get" — the exact same two instructions arrays already use for `p[0]`.
- **VM**: needs **zero new instructions and zero code changes** to support structs, because from the VM's point of view, a struct and an array are indistinguishable — both are just `Object[]`.

This is the payoff of the whole design: because each earlier stage did its job completely and recorded the facts cleanly, adding a whole new language feature (structs) required no changes at all to the lowest, most fragile layer (the VM).

---

## 6. Known, honest limitations (tracked on purpose, not hidden)

- No way to build an array of structs yet (`new Point[5]` is still a parse error).
- No struct methods, no reflection, no dynamic field names.
- No way to save compiled bytecode to a file and reload it later — a compiled program only exists in memory for one run.
- No self-hosting yet (the long-term goal of rewriting the compiler in GopiLang itself hasn't started).
- No real debugger (breakpoints, stepping) yet.

These aren't oversights that "should be fixed" — they're deliberate, scoped-out boundaries so each milestone stays small enough to fully understand before moving to the next one.

---

## 7. One-paragraph summary

GopiLang doesn't compete with production languages on features or speed — its real contribution is proving that a compiler pipeline (lexer → parser → semantic analysis → bytecode → VM) can be small enough for one person to read completely, while still being *real*: real recursion, real type-checking, real structs, real bytecode. It avoids the classic mistakes of hobby/student compilers — cascading errors, AST mutation bugs, identity bugs, blurred error-handling, scattered type rules, and premature over-engineering — through a small number of consistently-applied disciplines: poison instead of cascade, immutable "builder-then-freeze" results at every stage, identity-safe maps, one place for type rules, and a hard, clean boundary between the compiler and the VM.
