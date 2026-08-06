# GopiLang

GopiLang is a small, statically-typed programming language with a complete, from-scratch compiler and virtual machine — lexer, parser, semantic analyzer, bytecode generator, and VM — implemented entirely in hand-written Java 21. There are no parser generators, no third-party compiler libraries, and no VM frameworks: every stage of the pipeline is written and reviewed by hand.

This project exists for deep, first-principles understanding of how a real compiler and virtual machine work end to end, built incrementally, one class at a time. It is not optimized for shipping speed — it's optimized for understanding every decision along the way.

```
num factorial(num n) {
    if (n <= 1) {
        give 1;
    } else {
        give n * factorial(n - 1);
    }
}

none main() {
    show(factorial(5));
}
```
```
$ ./gopic examples/factorial.gopi
120
```

## Features

- **Hand-written recursive-descent lexer and parser** — no generated code anywhere in the pipeline.
- **Static semantic analysis**: scope-aware identifier resolution, type checking, reachability analysis, and definite-assignment checking, all before a single instruction is generated.
- **A real bytecode compiler** targeting a small, uniform stack-machine instruction set (see [`Opcode`](src/main/java/com/gopilang/bytecode/Opcode.java) — 38 opcodes, fixed `(opcode, operand)` instruction shape, no variable-length encoding).
- **A stack-based virtual machine** with call frames, a shared instruction stream, and full function-call/recursion support.
- **Rich diagnostics** with source-line rendering and caret underlines for lexical, syntax, and semantic errors.
- **A disassembler** for inspecting exactly what the compiler generated, without needing to run it.
- **Language features**: static types (`num`, `dec`, `flag`, `text`, `none`), arrays of any non-`none` type, variables, arithmetic and comparison operators, `if`/`else`, `loop`, `run` (a C-style for-loop, desugared to `loop` at parse time), user-defined functions, recursion, and `show`.

Known gap (tracked, not hidden): `&&`/`||` parse and type-check correctly but are not yet compiled to bytecode — see [Future Roadmap](#future-roadmap).

## Compiler Architecture

```
Gopi Source (.gopi)
        │
        ▼
     Lexer            tokenizes source text, reports lexical errors
        │
        ▼
     Parser           hand-written recursive descent → AST, reports syntax errors
        │
        ▼
Semantic Analyzer      scope/identifier resolution, type checking, reachability,
        │              definite assignment — reports semantic errors
        ▼
 Code Generator        lowers the AST into a flat bytecode instruction stream
        │
        ▼
   Bytecode Module      constant pool + function table + instruction list
        │
        ▼
  Virtual Machine       fetch-decode-execute loop over the instruction stream
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for a full description of every package and class.

## VM Architecture

The VM is a stack machine: one shared, flat instruction stream spans every function in the program, and each active function call gets its own [`Frame`](src/main/java/com/gopilang/vm/Frame.java) (local-variable slots + an operand stack). `CALL` pushes a new frame and jumps to the callee's `codeStart`; `RETURN` pops the current frame, optionally carries a return value back onto the caller's stack, and restores the caller's `pc`. Recursion needs no special handling — it falls out naturally from the call stack being a plain `Deque<Frame>`.

## Folder Structure

```
gop-compiler/
├── gopic                      # dev-loop wrapper: java -cp target/classes com.gopilang.cli.GopiC "$@"
├── install.sh                 # builds + installs gopic to ~/gopilang, symlinked onto PATH
├── uninstall.sh               # removes the ~/gopilang installation and its PATH symlink
├── .gitignore                 # ignores target/ and IDE/OS noise — never commit build output
├── pom.xml                    # Maven build (Java 21, JUnit 5) — also builds the standalone distribution
├── examples/                  # sample .gopi programs
│   └── semantic/              # programs demonstrating specific semantic diagnostics
├── src/main/java/com/gopilang/
│   ├── lexer/                 # source text → tokens
│   ├── parser/                # tokens → AST (hand-written recursive descent)
│   ├── ast/                   # sealed AST node types (immutable records)
│   ├── semantic/              # scope, type checking, SemanticAnalyzer, SemanticModel
│   ├── bytecode/               # Opcode, Instruction, BytecodeFunction/Module, CodeGenerator
│   ├── vm/                     # Frame, VirtualMachine
│   ├── printer/                # AstPrinter, BytecodeDisassembler
│   ├── errors/                 # Diagnostic, DiagnosticReporter, ErrorPhase, GopiError
│   ├── types/                  # PrimitiveType
│   ├── util/                   # SourceLocation, SourceRange
│   └── cli/                    # GopiC — command-line entry point
├── src/main/scripts/gopic     # launcher script template, copied into the built distribution
├── src/test/java/com/gopilang/ # JUnit test suites (lexer, parser, semantic)
└── target/gopilang/            # generated by `mvn package` — the standalone, relocatable install
    ├── bin/gopic                #   launcher (resolves its jar relative to itself)
    ├── lib/gopilang.jar          #   executable jar (Main-Class: com.gopilang.cli.GopiC)
    ├── examples/                 #   copy of examples/, for use from the installed location
    └── README.md
```

## Installation

Requirements:
- **Java 21** or later (JDK, not just a JRE)
- **Maven 3.6+**

```bash
git clone <this-repo-url>
cd gop-compiler
```

There are two ways to use GopiLang from here, depending on what you're doing:

- **Working on the compiler itself** — use `mvn compile` and the repo's own `./gopic` script (see [Building](#building) / [Running Programs](#running-programs)). Fastest inner loop; always reflects your latest source changes.
- **Using `gopic` as an installed command, from any directory** — run `./install.sh` (see [Installing `gopic`](#installing-gopic)). This does **not** point at `target/`, so it survives `mvn clean`.

## Building

```bash
mvn compile
```

Run the test suite:

```bash
mvn test
```

Build the standalone distribution (`target/gopilang/`, with an executable `lib/gopilang.jar` and a `bin/gopic` launcher):

```bash
mvn package
```

## Running Programs

From within the repo, using the dev-loop script:

```bash
./gopic examples/hello.gopi
```
```
Hello, GopiLang!
```

`./gopic` is a thin shell wrapper around `java -cp target/classes com.gopilang.cli.GopiC`, so `mvn compile` must be run first (or after any source change).

Once installed system-wide (below), the exact same commands work as plain `gopic`, from any directory:

```bash
cd /tmp
gopic /path/to/examples/hello.gopi
```
```
Hello, GopiLang!
```

## Installing `gopic`

This makes `gopic` behave like `javac` or `python` — runnable from anywhere, with no need to `cd` into this repo first, and (unlike pointing at `target/` directly) unaffected by `mvn clean`.

```bash
./install.sh
```

This builds the project (`mvn package`), copies the resulting distribution to `~/gopilang` (override with `GOPILANG_INSTALL_DIR=/opt/gopilang ./install.sh`), and symlinks `~/.local/bin/gopic` (override with `GOPILANG_LINK_DIR`) to it. It also verifies Java 21+ is on `PATH` before building. Re-running `./install.sh` after pulling new changes safely replaces the previous installation.

```
$ ./install.sh
...
== GopiLang installed successfully ==

Try it:
  gopic /Users/you/gopilang/examples/hello.gopi
```

Verify it from anywhere:

```bash
cd /tmp
gopic ~/gopilang/examples/hello.gopi
```
```
Hello, GopiLang!
```

`mvn clean` in the repository only removes `target/` — the installed copy under `~/gopilang` is untouched, so `gopic` keeps working.

To uninstall:

```bash
./uninstall.sh
```

```
$ ./uninstall.sh
== GopiLang uninstaller ==
...
== GopiLang uninstalled successfully ==
```

This removes `~/gopilang` and its `PATH` symlink — `uninstall.sh` only ever removes the symlink if it still points at `~/gopilang/bin/gopic`, so it won't touch an unrelated `gopic` you may have installed some other way.

## CLI Usage

```
gopic <file.gopi>                Compile and run a program
gopic --tokens <file.gopi>       Print the lexer's token stream
gopic --ast <file.gopi>          Print the parsed AST as a tree
gopic --disassemble <file.gopi>  Compile and print the generated bytecode (does not run it)
```

Errors at any phase (lexical, syntax, or semantic) are printed with source context and a caret pointing at the offending column; the VM never runs when errors are present, and the process exits non-zero.

```
$ ./gopic examples/semantic/undefined_variable.gopi

1 semantic error(s):

Semantic Error: undefined variable 'y'
 --> line 9, column 10
  |
9 |     show(y);
  |          ^

$ echo $?
1
```

## Disassembler Usage

```bash
./gopic --disassemble examples/factorial.gopi
```
```
Constant Pool
-------------
0: 1
1: 5

Functions
---------
0 factorial
  params:1
  slots:1
  codeStart:2

1 main
  params:0
  slots:0
  codeStart:17

Instructions
------------
0000 CALL 1
0001 HALT
0002 LOAD 0
0003 PUSH_CONST 0
0004 CMP_LE
0005 JMP_IF_FALSE 9
0006 PUSH_CONST 0
0007 RETURN
0008 JMP 16
0009 LOAD 0
0010 LOAD 0
0011 PUSH_CONST 0
0012 SUB
0013 CALL 0
0014 MUL
0015 RETURN
0016 RETURN
0017 PUSH_CONST 1
0018 CALL 0
0019 PRINT
0020 RETURN
```

Every program begins with the same two-instruction entry stub (`CALL <index of main>`, `HALT`) — the VM always starts execution at instruction 0, regardless of how many functions the program declares or in what order.

## Example Programs

All of these live in [`examples/`](examples/) and can be run directly.

**Hello World** ([`hello.gopi`](examples/hello.gopi))
```
none main() {
    show("Hello, GopiLang!");
}
```
```
$ ./gopic examples/hello.gopi
Hello, GopiLang!
```

**Functions** ([`function_calls.gopi`](examples/function_calls.gopi))
```
num add(num a, num b) {
    give a + b;
}

none main() {
    show(add(1, 2));
}
```
```
$ ./gopic examples/function_calls.gopi
3
```

**Recursion** ([`factorial.gopi`](examples/factorial.gopi))
```
num factorial(num n) {
    if (n <= 1) {
        give 1;
    } else {
        give n * factorial(n - 1);
    }
}

none main() {
    show(factorial(5));
}
```
```
$ ./gopic examples/factorial.gopi
120
```

**Operator precedence** ([`arithmetic.gopi`](examples/arithmetic.gopi))
```
none main() {
    show(1 + 2 * 3);
    show((1 + 2) * 3);
}
```
```
$ ./gopic examples/arithmetic.gopi
7
9
```

**Chained assignment** ([`assignment_chains.gopi`](examples/assignment_chains.gopi))
```
none main() {
    num a;
    num b;
    a = b = 5;
    show(a);
}
```
```
$ ./gopic examples/assignment_chains.gopi
5
```

**Nested control flow** ([`nested_control_flow.gopi`](examples/nested_control_flow.gopi))
```
none main() {
    num x = 10;
    loop (x > 0) {
        if (x == 5) {
            show(x);
            x = x - 1;
        } else {
            x = x - 1;
        }
    }
}
```
```
$ ./gopic examples/nested_control_flow.gopi
5
```

**Arrays** ([`arrays.gopi`](examples/arrays.gopi))
```
none main() {
    num[] numbers = new num[3];
    numbers[0] = 10;
    numbers[1] = 20;
    numbers[2] = 30;
    show(numbers.len());
}
```
```
$ ./gopic examples/arrays.gopi
3
```

**`run` (for-loop)** ([`for_loop.gopi`](examples/for_loop.gopi))
```
none main() {
    run (num i = 0; i < 5; i = i + 1) {
        show(i);
    }
}
```
```
$ ./gopic examples/for_loop.gopi
0
1
2
3
4
```
`run` is pure syntactic sugar, desugared to `loop` at parse time — see [LANGUAGE.md](LANGUAGE.md#run) for the desugaring rule.

See [`examples/semantic/`](examples/semantic) for programs that each demonstrate one specific semantic diagnostic (undefined variables, shadowing, duplicate declarations, use-before-assignment, unreachable-code checking, and more).

## Future Roadmap

Tracked, deliberate gaps — not oversights:

- **`&&` / `||` short-circuit compilation.** Both operators parse and type-check correctly today; `CodeGenerator` throws `UnsupportedOperationException` for them because short-circuit evaluation needs conditional jumps interleaved with the right operand's code, a different shape than every other binary operator (which unconditionally compiles both sides first).
- **Chained assignment's `DUP` opcode** was anticipated from the original bytecode design and is implemented; a `.gopi`-level regression example is in [`assignment_chains.gopi`](examples/assignment_chains.gopi).
- **Bytecode serialization** (`BytecodeWriter`/`BytecodeReader`) — writing a `BytecodeModule` to a `.gbc` file and reading it back, so compilation and execution can be separate steps.
- **Self-hosting** — the long-term goal of rewriting this compiler in GopiLang itself, once the language is expressive enough to do so.
- **A real debugger** — breakpoints and single-stepping against the VM's `Frame`/call-stack model.

## License

See the repository for license details.
