package com.gopilang.semantic;

import com.gopilang.ast.ArrayAccessExpression;
import com.gopilang.ast.ArrayLengthExpression;
import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BlockStatement;
import com.gopilang.ast.Expr;
import com.gopilang.ast.ExpressionStatement;
import com.gopilang.ast.FieldAccessExpression;
import com.gopilang.ast.FieldAssignmentExpression;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.FunctionDeclaration;
import com.gopilang.ast.GroupingExpression;
import com.gopilang.ast.IfStatement;
import com.gopilang.ast.IndexAssignmentExpression;
import com.gopilang.ast.LiteralExpression;
import com.gopilang.ast.NewArrayExpression;
import com.gopilang.ast.NewStructExpression;
import com.gopilang.ast.Parameter;
import com.gopilang.ast.PrintStatement;
import com.gopilang.ast.Program;
import com.gopilang.ast.ReturnStatement;
import com.gopilang.ast.Stmt;
import com.gopilang.ast.StructDeclaration;
import com.gopilang.ast.UnaryExpression;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.DiagnosticReporter;
import com.gopilang.errors.ErrorPhase;
import com.gopilang.types.PrimitiveType;
import com.gopilang.types.TypeRef;
import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Two-pass semantic analyzer producing an immutable {@link SemanticModel}
 * from a parsed {@link Program}. Pass 1 ({@code registerFunctions()})
 * registers every function's signature before any body is analyzed, which
 * is what makes forward references and mutual recursion resolve correctly.
 * Pass 2 ({@code analyzeFunction}/{@code analyzeStatement}/{@code
 * analyzeExpr}) walks each body doing scope management, identifier
 * resolution, type checking, reachability analysis, and definite
 * assignment — all woven into the same statement-level switch rather than
 * separate traversals. Never throws and never mutates the AST: an
 * unresolved or invalid expression simply gets no entry in the resulting
 * maps, and every downstream check that reads an absent entry silently
 * skips its own check rather than reporting a cascade.
 */
public final class SemanticAnalyzer {

    private final Program program;
    private final DiagnosticReporter reporter = new DiagnosticReporter();

    // Builder-side accumulators for the eventual SemanticModel — mutable here,
    // frozen (and, for the node-keyed maps, copied into genuine
    // IdentityHashMaps) by SemanticModel's own compact constructor at the end
    // of analyze().
    private final Map<String, StructSymbol> structTable = new HashMap<>();
    private final Map<String, FunctionSymbol> functionTable = new HashMap<>();
    private final Map<VariableExpression, VariableSymbol> variableResolutions = new IdentityHashMap<>();
    private final Map<AssignmentExpression, VariableSymbol> assignmentTargetResolutions = new IdentityHashMap<>();
    private final Map<FunctionCallExpression, FunctionSymbol> callResolutions = new IdentityHashMap<>();
    private final Map<Expr, TypeRef> expressionTypes = new IdentityHashMap<>();

    // Reassigned while walking: currentScope tracks the active scope chain
    // (entering/leaving a block reassigns this to a child/parent Scope);
    // currentFunction tracks which function's body is being walked, needed
    // to check return statements and reachability against its declared
    // return type (not yet implemented).
    private Scope currentScope;
    private FunctionDeclaration currentFunction;
    // Definite assignment: which VariableSymbols are known, on the current
    // path, to have been given a value. Identity-keyed (not name-keyed) for
    // the same reason SemanticModel's node maps are — shadowed variables with
    // the same name must never be conflated. Reassigned wholesale (not just
    // mutated) at if/while boundaries so branches can be analyzed from
    // independent snapshots and merged afterward; see analyzeStatement.
    private Set<VariableSymbol> currentAssigned;

    public SemanticAnalyzer(Program program) {
        this.program = program;
    }

    /** Diagnostics collected during {@link #analyze()} — empty if analysis found no problems. */
    public DiagnosticReporter reporter() {
        return reporter;
    }

    /** Runs both passes over the program and returns the resulting {@link SemanticModel}. */
    public SemanticModel analyze() {
        registerBuiltins();
        registerStructs();
        registerFunctions();
        for (FunctionDeclaration function : program.functions()) {
            analyzeFunction(function);
        }
        validateMainFunction();
        return new SemanticModel(structTable, functionTable, variableResolutions, assignmentTargetResolutions,
                callResolutions, expressionTypes);
    }

    // Whole-program check, run once after every function has been analyzed —
    // uses only functionTable (already complete after Pass 1), so it could
    // run earlier, but grouping "per-function work" before "whole-program
    // checks" reads more clearly. Not part of the original Milestone 4 scope
    // list, but Phase 4's requested "main() validation" test category has
    // nothing real to test without it — see message.
    private void validateMainFunction() {
        FunctionSymbol main = functionTable.get("main");
        if (main == null) {
            reporter.report(new Diagnostic(
                    ErrorPhase.SEMANTIC,
                    program.range(),
                    "program has no 'main' function",
                    "add a 'none main()' function as the program's entry point"));
            return;
        }
        if (!isVoid(main.returnType()) || !main.parameterTypes().isEmpty()) {
            reporter.report(new Diagnostic(
                    ErrorPhase.SEMANTIC,
                    main.declaredAt(),
                    "'main' must have the signature 'none main()'",
                    "found return type '" + main.returnType().displayName() + "' with "
                            + main.parameterTypes().size() + " parameter(s)"));
        }
    }

    // Milestone B1 (self-hosting bootstrap): six VM-intrinsic functions,
    // pre-registered into functionTable before any user function is — which
    // is what makes a user's own top-level declaration of the same name
    // collide via registerFunctions()'s existing functionTable.putIfAbsent()
    // duplicate-detection, with the existing "function 'X' is already
    // declared" diagnostic, unchanged. Deliberately not a separate
    // "intrinsic table": these become ordinary FunctionSymbols, so every
    // downstream check (analyzeExpr's callResolutions, typeOf's argument-count
    // and argument-type checking, CodeGenerator's return type reads) works on
    // them with zero special-casing — only CodeGenerator needs to know these
    // particular names are builtins, to emit their dedicated opcode instead
    // of CALL.
    private static final SourceRange BUILTIN_DECLARED_AT = SourceRange.point(new SourceLocation(0, 0));

    private void registerBuiltins() {
        registerBuiltin("charCodeAt", new TypeRef(PrimitiveType.INT, false),
                new TypeRef(PrimitiveType.STRING, false), new TypeRef(PrimitiveType.INT, false));
        registerBuiltin("textLength", new TypeRef(PrimitiveType.INT, false),
                new TypeRef(PrimitiveType.STRING, false));
        registerBuiltin("textFromCharCode", new TypeRef(PrimitiveType.STRING, false),
                new TypeRef(PrimitiveType.INT, false));
        registerBuiltin("readFile", new TypeRef(PrimitiveType.STRING, false),
                new TypeRef(PrimitiveType.STRING, false));
        registerBuiltin("argCount", new TypeRef(PrimitiveType.INT, false));
        registerBuiltin("argAt", new TypeRef(PrimitiveType.STRING, false),
                new TypeRef(PrimitiveType.INT, false));
    }

    private void registerBuiltin(String name, TypeRef returnType, TypeRef... parameterTypes) {
        functionTable.put(name, new FunctionSymbol(name, returnType, List.of(parameterTypes), BUILTIN_DECLARED_AT));
    }

    // Pass 1, structs: register every struct's field list before functions are
    // registered, so a function signature can already refer to a struct
    // declared later in the file (forward reference). Duplicate struct names,
    // and duplicate field names within one struct, are reported, not thrown —
    // mirroring registerFunctions()'s and analyzeFunction()'s own
    // parameter-duplicate handling exactly: the first declaration stays
    // authoritative. As of Milestone S3, once every struct is registered, a
    // second pass resolves each field's own type (a field may itself be
    // struct-typed or array-of-struct-typed — see parseDeclaredType()), and a
    // third pass detects cyclic struct containment; both need the complete
    // structTable, so neither can run interleaved with the registration loop
    // above.
    private void registerStructs() {
        for (StructDeclaration structDecl : program.structs()) {
            // A throwaway Scope, used only for its existing putIfAbsent-based
            // duplicate-name detection — a struct's fields are a flat
            // namespace, exactly like a function's parameters are.
            Scope fieldScope = new Scope(null);
            for (Parameter field : structDecl.fields()) {
                VariableSymbol fieldSymbol = new VariableSymbol(field.name(), field.type(), field.range());
                fieldScope.define(fieldSymbol).ifPresent(existing -> reporter.report(new Diagnostic(
                        ErrorPhase.SEMANTIC,
                        field.range(),
                        "field '" + field.name() + "' is already declared",
                        "previous declaration was at " + existing.declaredAt())));
            }

            StructSymbol symbol = new StructSymbol(structDecl.name(), structDecl.fields(), structDecl.range());
            StructSymbol existing = structTable.putIfAbsent(symbol.name(), symbol);
            if (existing != null) {
                reporter.report(new Diagnostic(
                        ErrorPhase.SEMANTIC,
                        symbol.declaredAt(),
                        "struct '" + symbol.name() + "' is already declared",
                        "previous declaration was at " + existing.declaredAt()));
            }
        }

        for (StructDeclaration structDecl : program.structs()) {
            for (Parameter field : structDecl.fields()) {
                resolveDeclaredType(field.type(), field.range());
            }
        }

        detectStructCycles();
    }

    // A struct-typed field creates a "must be fully contained" edge in the
    // struct dependency graph — but only when it is NOT an array: an array
    // field is a runtime reference (a plain Object[], per CodeGenerator/the
    // VM), not inline storage, so it can never make a struct's size
    // unbounded the way direct containment would. Standard DFS with the two
    // sets a cycle check needs: onPath (the current recursion stack — a back
    // edge into this set is the cycle) and seen (permanently processed, so a
    // struct reachable from more than one root, e.g. a diamond dependency, is
    // never re-explored and never misreported as a cycle). One diagnostic per
    // back edge found, reported at the field that creates it.
    private void detectStructCycles() {
        Set<String> seen = new HashSet<>();
        for (String name : structTable.keySet()) {
            if (!seen.contains(name)) {
                visitForCycle(name, seen, new HashSet<>());
            }
        }
    }

    private void visitForCycle(String name, Set<String> seen, Set<String> onPath) {
        seen.add(name);
        onPath.add(name);
        for (Parameter field : structTable.get(name).fields()) {
            TypeRef fieldType = field.type();
            if (fieldType.isArray() || fieldType.structName().isEmpty()) {
                continue; // array field: no containment edge. primitive field: no edge at all.
            }
            String referenced = fieldType.structName().get();
            if (!structTable.containsKey(referenced)) {
                continue; // undefined struct, already reported by resolveDeclaredType()
            }
            if (onPath.contains(referenced)) {
                reporter.report(new Diagnostic(
                        ErrorPhase.SEMANTIC,
                        field.range(),
                        "struct '" + name + "' cannot contain itself (directly or indirectly) via field '"
                                + field.name() + "'",
                        "'" + field.name() + "' has type '" + referenced
                                + "', which (directly or indirectly) contains '" + name + "' again"));
            } else if (!seen.contains(referenced)) {
                visitForCycle(referenced, seen, onPath);
            }
        }
        onPath.remove(name);
    }

    // Pass 1: register every function's signature before analyzing any body,
    // so forward references and mutual recursion resolve correctly. Duplicate
    // names are reported, not thrown — the first declaration stays
    // authoritative (matching Scope.define()'s exact putIfAbsent semantics),
    // so later, unrelated references aren't affected by the duplicate.
    private void registerFunctions() {
        for (FunctionDeclaration function : program.functions()) {
            resolveDeclaredType(function.returnType(), function.range());
            for (Parameter parameter : function.parameters()) {
                resolveDeclaredType(parameter.type(), parameter.range());
            }

            FunctionSymbol symbol = new FunctionSymbol(
                    function.name(),
                    function.returnType(),
                    function.parameters().stream().map(Parameter::type).toList(),
                    function.range());

            FunctionSymbol existing = functionTable.putIfAbsent(symbol.name(), symbol);
            if (existing != null) {
                reporter.report(new Diagnostic(
                        ErrorPhase.SEMANTIC,
                        symbol.declaredAt(),
                        "function '" + symbol.name() + "' is already declared",
                        "previous declaration was at " + existing.declaredAt()));
            }
        }
    }

    // Resolution for a declared type's struct name, if it has one — a field,
    // parameter, return type, or variable type may all name a struct that was
    // never declared. Reported once, at the exact position the type was
    // written, mirroring "undefined function"/"undefined variable" exactly.
    // A struct that DOES resolve needs no further action here: its TypeRef
    // already carries the real name, so TypeRules' nominal comparisons work
    // correctly from this point on with no separate "resolved" marker needed.
    private void resolveDeclaredType(TypeRef type, SourceRange range) {
        type.structName().ifPresent(name -> {
            if (!structTable.containsKey(name)) {
                reporter.report(new Diagnostic(
                        ErrorPhase.SEMANTIC,
                        range,
                        "undefined struct '" + name + "'",
                        "declare 'struct " + name + " { ... }' before using it as a type"));
            }
        });
    }

    // Pass 2: scope management and identifier resolution only — no type
    // checking. Every function's body is analyzed regardless of whether Pass
    // 1 accepted or rejected it as a duplicate: a rejected duplicate's body is
    // still real source the user wrote, and its own internal errors are still
    // worth surfacing in the same pass rather than after a second recompile.
    private void analyzeFunction(FunctionDeclaration function) {
        currentFunction = function;
        // The parameter scope and the body's top-level scope are the SAME
        // Scope — no extra nesting for the outermost block (see message).
        currentScope = new Scope(null);
        currentAssigned = newIdentitySet();

        for (Parameter parameter : function.parameters()) {
            VariableSymbol symbol = new VariableSymbol(parameter.name(), parameter.type(), parameter.range());
            currentScope.define(symbol).ifPresent(existing -> reporter.report(new Diagnostic(
                    ErrorPhase.SEMANTIC,
                    parameter.range(),
                    "parameter '" + parameter.name() + "' is already declared",
                    "previous declaration was at " + existing.declaredAt())));
            currentAssigned.add(symbol); // parameters begin assigned
        }

        for (Stmt statement : function.body().statements()) {
            analyzeStatement(statement);
        }

        // Reachability: run after the body's own type checking, since it
        // needs no information the type-checking walk didn't already use —
        // it's a structural question over the same statement tree, checked
        // once per function, not interleaved with per-statement typing.
        if (!isVoid(function.returnType()) && !returnsOnAllPaths(function.body())) {
            reporter.report(new Diagnostic(
                    ErrorPhase.TYPE,
                    function.range(),
                    "function '" + function.name() + "' does not return a value on all paths",
                    "declared to return '" + function.returnType().displayName() + "'"));
        }

        currentScope = null;
        currentFunction = null;
        currentAssigned = null;
    }

    // Structural reachability only — no CFG, no unreachable-code detection,
    // no loop-bound reasoning beyond a literal `true` condition. See message
    // for why each case is what it is.
    private boolean returnsOnAllPaths(Stmt stmt) {
        return switch (stmt) {
            case BlockStatement block -> block.statements().stream().anyMatch(this::returnsOnAllPaths);
            case IfStatement ifStmt -> ifStmt.elseBranch().isPresent()
                    && returnsOnAllPaths(ifStmt.thenBranch())
                    && returnsOnAllPaths(ifStmt.elseBranch().get());
            case WhileStatement whileStmt -> isLiteralTrue(whileStmt.condition())
                    && returnsOnAllPaths(whileStmt.body());
            case ReturnStatement returnStmt -> true;
            case VariableDeclaration decl -> false;
            case PrintStatement printStmt -> false;
            case ExpressionStatement exprStmt -> false;
        };
    }

    // Deliberately syntactic, not semantic: while (1 == 1) or while (!false)
    // are not recognized, since that would require constant-folding
    // arbitrary expressions — explicitly out of scope for this simple pass.
    private boolean isLiteralTrue(Expr expr) {
        return expr instanceof LiteralExpression literal
                && literal.type() == PrimitiveType.BOOL
                && Boolean.TRUE.equals(literal.value());
    }

    // "Exactly 'none', not 'none[]'" — VOID can never legally be an array
    // (typeOf's NewArrayExpression case never produces one), so this is the
    // one check needed everywhere a declared or expected type must be plain
    // 'none' (main's return type, a non-array function's implicit void return).
    private static boolean isVoid(TypeRef type) {
        return !type.isArray() && type.elementType() == PrimitiveType.VOID;
    }

    private void analyzeStatement(Stmt stmt) {
        switch (stmt) {
            case BlockStatement block -> {
                Scope enclosing = currentScope;
                currentScope = new Scope(enclosing);
                for (Stmt inner : block.statements()) {
                    analyzeStatement(inner);
                }
                currentScope = enclosing; // leaving the scope: just stop using the child
            }
            case VariableDeclaration decl -> {
                resolveDeclaredType(decl.type(), decl.range());

                // Checked before insertion, deliberately — a variable's own
                // initializer must not be able to see itself (num y = y + 1;
                // sees an outer y, or is undefined; never "the y being
                // declared right now").
                decl.initializer().ifPresent(init -> checkExpr(init).ifPresent(type -> {
                    if (!TypeRules.isAssignable(type, decl.type())) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                init.range(),
                                "cannot assign '" + type.displayName() + "' to variable of type '"
                                        + decl.type().displayName() + "'",
                                null));
                    }
                }));

                Optional<VariableSymbol> shadowed = currentScope.resolve(decl.name());
                VariableSymbol symbol = new VariableSymbol(decl.name(), decl.type(), decl.range());
                Optional<VariableSymbol> sameScope = currentScope.define(symbol);

                if (decl.initializer().isPresent()) {
                    // Assigned regardless of whether the initializer's type
                    // was actually compatible — definite assignment tracks
                    // whether a value was given at all, a different question
                    // from whether that value type-checked.
                    currentAssigned.add(symbol);
                }

                if (sameScope.isPresent()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.SEMANTIC,
                            decl.range(),
                            "variable '" + decl.name() + "' is already declared in this scope",
                            "previous declaration was at " + sameScope.get().declaredAt()));
                } else if (shadowed.isPresent()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.SEMANTIC,
                            decl.range(),
                            "variable '" + decl.name() + "' shadows a variable from an enclosing scope",
                            "enclosing declaration was at " + shadowed.get().declaredAt()));
                }
            }
            case IfStatement ifStmt -> {
                checkExpr(ifStmt.condition()).ifPresent(type -> {
                    if (type.isArray() || type.elementType() != PrimitiveType.BOOL) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                ifStmt.condition().range(),
                                "if condition must be 'flag', found '" + type.displayName() + "'",
                                null));
                    }
                });

                // Branch merging: each branch is analyzed from an independent
                // snapshot of what's assigned going in (the condition itself
                // already ran unconditionally, so anything IT assigned, e.g.
                // "if ((x = f()) > 0)", is correctly visible to both). A
                // variable is assigned after the whole if-statement only if
                // BOTH branches assigned it — or there's no else at all, in
                // which case the false path contributes nothing new, exactly
                // mirroring returnsOnAllPaths's "no else -> never guaranteed".
                Set<VariableSymbol> beforeBranches = copyOfAssigned(currentAssigned);
                analyzeStatement(ifStmt.thenBranch());
                Set<VariableSymbol> afterThen = currentAssigned;

                Set<VariableSymbol> afterElse;
                if (ifStmt.elseBranch().isPresent()) {
                    currentAssigned = copyOfAssigned(beforeBranches);
                    analyzeStatement(ifStmt.elseBranch().get());
                    afterElse = currentAssigned;
                } else {
                    afterElse = beforeBranches;
                }

                currentAssigned = intersectAssigned(afterThen, afterElse);
            }
            case WhileStatement whileStmt -> {
                checkExpr(whileStmt.condition()).ifPresent(type -> {
                    if (type.isArray() || type.elementType() != PrimitiveType.BOOL) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                whileStmt.condition().range(),
                                "while condition must be 'flag', found '" + type.displayName() + "'",
                                null));
                    }
                });

                // Conservative, uniformly: the body might execute zero times
                // (unlike returnsOnAllPaths, no while(true) special case here
                // — even a provably-infinite loop's assignments only matter
                // for reads INSIDE it, which the body's own sequential walk
                // already checks correctly; anything after the loop can't
                // rely on the body having run at all).
                Set<VariableSymbol> beforeLoop = copyOfAssigned(currentAssigned);
                analyzeStatement(whileStmt.body());
                currentAssigned = beforeLoop;
            }
            case ReturnStatement returnStmt -> {
                TypeRef declaredReturnType = currentFunction.returnType();
                if (returnStmt.value().isEmpty()) {
                    if (!isVoid(declaredReturnType)) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                returnStmt.range(),
                                "missing return value in function declared to return '"
                                        + declaredReturnType.displayName() + "'",
                                null));
                    }
                } else {
                    Expr value = returnStmt.value().get();
                    Optional<TypeRef> valueType = checkExpr(value);
                    if (isVoid(declaredReturnType)) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                value.range(),
                                "cannot return a value from a function declared 'none'",
                                null));
                    } else {
                        valueType.ifPresent(type -> {
                            if (!TypeRules.isReturnCompatible(type, declaredReturnType)) {
                                reporter.report(new Diagnostic(
                                        ErrorPhase.TYPE,
                                        value.range(),
                                        "cannot return '" + type.displayName()
                                                + "' from a function declared to return '"
                                                + declaredReturnType.displayName() + "'",
                                        null));
                            }
                        });
                    }
                }
            }
            case PrintStatement printStmt -> checkExpr(printStmt.value()).ifPresent(type -> {
                if (!TypeRules.isPrintable(type)) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            printStmt.value().range(),
                            "cannot print a value of type '" + type.displayName() + "'",
                            null));
                }
            });
            case ExpressionStatement exprStmt -> checkExpr(exprStmt.expression());
        }
    }

    // Resolution (analyzeExpr, populates variableResolutions/
    // assignmentTargetResolutions/callResolutions) must run before typing
    // (typeOf, only READS those maps) — this is the one, explicit place that
    // ordering is enforced, so every statement-level touchpoint gets both by
    // construction instead of having to remember to pair them manually.
    private Optional<TypeRef> checkExpr(Expr expr) {
        analyzeExpr(expr);
        return typeOf(expr);
    }

    private void analyzeExpr(Expr expr) {
        switch (expr) {
            case LiteralExpression literal -> { }
            case VariableExpression variable -> {
                Optional<VariableSymbol> symbol = currentScope.resolve(variable.name());
                if (symbol.isPresent()) {
                    variableResolutions.put(variable, symbol.get());
                    // Only checked once resolution succeeded — an undefined
                    // variable already gets its own diagnostic above; it must
                    // not also be reported as "unassigned", which would be
                    // exactly the cascade the poison-propagation design exists
                    // to prevent.
                    if (!currentAssigned.contains(symbol.get())) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.SEMANTIC,
                                variable.range(),
                                "variable '" + variable.name() + "' might not have been assigned a value",
                                null));
                    }
                } else {
                    reporter.report(new Diagnostic(
                            ErrorPhase.SEMANTIC,
                            variable.range(),
                            "undefined variable '" + variable.name() + "'",
                            null));
                }
            }
            case GroupingExpression grouping -> analyzeExpr(grouping.inner());
            case UnaryExpression unary -> analyzeExpr(unary.operand());
            case BinaryExpression binary -> {
                analyzeExpr(binary.left());
                analyzeExpr(binary.right());
            }
            case AssignmentExpression assignment -> {
                analyzeExpr(assignment.value());
                Optional<VariableSymbol> symbol = currentScope.resolve(assignment.target());
                if (symbol.isPresent()) {
                    assignmentTargetResolutions.put(assignment, symbol.get());
                    // Marked assigned regardless of the value's own type
                    // compatibility — the same reasoning as VariableDeclaration:
                    // definite assignment tracks whether a value was given,
                    // not whether that value type-checked.
                    currentAssigned.add(symbol.get());
                } else {
                    reporter.report(new Diagnostic(
                            ErrorPhase.SEMANTIC,
                            assignment.range(),
                            "undefined variable '" + assignment.target() + "'",
                            null));
                }
            }
            case FunctionCallExpression call -> {
                for (Expr argument : call.arguments()) {
                    analyzeExpr(argument);
                }
                FunctionSymbol callee = functionTable.get(call.calleeName());
                if (callee != null) {
                    callResolutions.put(call, callee);
                } else {
                    reporter.report(new Diagnostic(
                            ErrorPhase.SEMANTIC,
                            call.range(),
                            "undefined function '" + call.calleeName() + "'",
                            null));
                }
            }
            case NewArrayExpression newArray -> analyzeExpr(newArray.size());
            case ArrayAccessExpression access -> {
                analyzeExpr(access.array());
                analyzeExpr(access.index());
            }
            case ArrayLengthExpression length -> analyzeExpr(length.array());
            case IndexAssignmentExpression indexAssignment -> {
                // The array reference itself is read here (which array is
                // being mutated), not reassigned — its own definite-assignment
                // check happens naturally via analyzeExpr(array)'s ordinary
                // VariableExpression case, exactly like any other read.
                analyzeExpr(indexAssignment.array());
                analyzeExpr(indexAssignment.index());
                analyzeExpr(indexAssignment.value());
            }
            case NewStructExpression construction -> {
                for (Expr argument : construction.arguments()) {
                    analyzeExpr(argument);
                }
                // Reuses resolveDeclaredType() verbatim (the same "undefined
                // struct" check/diagnostic already used for a variable,
                // parameter, return, or field type) rather than a parallel
                // check — a struct name is a struct name regardless of
                // whether it appears in a type position or a construction.
                resolveDeclaredType(
                        new TypeRef(PrimitiveType.BOOL, false, Optional.of(construction.structName())),
                        construction.range());
            }
            // Placeholder only — real field resolution/type checking is
            // Milestone S5 Phase 2's scope; this exists so the switch stays
            // exhaustive for Phase 1 (parser/AST).
            case FieldAccessExpression access -> analyzeExpr(access.target());
            case FieldAssignmentExpression assignment -> {
                analyzeExpr(assignment.target());
                analyzeExpr(assignment.value());
            }
        }
    }

    /**
     * Recursive, bottom-up expression typing, invoked from many different
     * statement contexts via {@link #checkExpr(Expr)}. Reads {@code
     * variableResolutions}, {@code assignmentTargetResolutions}, and {@code
     * callResolutions} (all already populated by {@code analyzeExpr}'s own
     * resolution walk) but never writes to them — this method's only side
     * effect on {@link SemanticModel} is populating {@code expressionTypes}.
     * Returns empty for an ill-typed or already-poisoned sub-expression,
     * which is what lets a type error stop propagating rather than
     * cascading into further diagnostics.
     */
    public Optional<TypeRef> typeOf(Expr expr) {
        return switch (expr) {
            case LiteralExpression literal -> recordType(literal, new TypeRef(literal.type(), false));

            case VariableExpression variable -> {
                VariableSymbol symbol = variableResolutions.get(variable);
                yield symbol == null ? Optional.empty() : recordType(variable, symbol.type());
            }

            case GroupingExpression grouping -> {
                Optional<TypeRef> innerType = typeOf(grouping.inner());
                yield innerType.isEmpty() ? Optional.empty() : recordType(grouping, innerType.get());
            }

            case UnaryExpression unary -> {
                Optional<TypeRef> operandType = typeOf(unary.operand());
                if (operandType.isEmpty()) {
                    yield Optional.empty();
                }
                if (operandType.get().isArray() || operandType.get().structName().isPresent()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            unary.range(),
                            "operator '" + unary.operator().symbol() + "' cannot be applied to '"
                                    + operandType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                Optional<PrimitiveType> result =
                        TypeRules.resultOfUnary(unary.operator(), operandType.get().elementType());
                if (result.isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            unary.range(),
                            "operator '" + unary.operator().symbol() + "' cannot be applied to '"
                                    + operandType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(unary, new TypeRef(result.get(), false));
            }

            case BinaryExpression binary -> {
                Optional<TypeRef> leftType = typeOf(binary.left());
                Optional<TypeRef> rightType = typeOf(binary.right());
                if (leftType.isEmpty() || rightType.isEmpty()) {
                    yield Optional.empty();
                }
                if (leftType.get().isArray() || rightType.get().isArray()
                        || leftType.get().structName().isPresent() || rightType.get().structName().isPresent()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            binary.range(),
                            "operator '" + binary.operator().symbol() + "' cannot be applied to '"
                                    + leftType.get().displayName() + "' and '" + rightType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                Optional<PrimitiveType> result = TypeRules.resultOfBinary(
                        leftType.get().elementType(), binary.operator(), rightType.get().elementType());
                if (result.isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            binary.range(),
                            "operator '" + binary.operator().symbol() + "' cannot be applied to '"
                                    + leftType.get().displayName() + "' and '" + rightType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(binary, new TypeRef(result.get(), false));
            }

            case AssignmentExpression assignment -> {
                Optional<TypeRef> valueType = typeOf(assignment.value());
                VariableSymbol target = assignmentTargetResolutions.get(assignment);
                if (target == null || valueType.isEmpty()) {
                    yield Optional.empty();
                }
                if (!TypeRules.isAssignable(valueType.get(), target.type())) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            assignment.range(),
                            "cannot assign '" + valueType.get().displayName() + "' to variable of type '"
                                    + target.type().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(assignment, target.type());
            }

            case FunctionCallExpression call -> {
                List<Optional<TypeRef>> argumentTypes = new ArrayList<>();
                for (Expr argument : call.arguments()) {
                    argumentTypes.add(typeOf(argument));
                }

                FunctionSymbol callee = callResolutions.get(call);
                if (callee == null) {
                    yield Optional.empty();
                }

                List<TypeRef> parameterTypes = callee.parameterTypes();
                if (argumentTypes.size() != parameterTypes.size()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            call.range(),
                            "function '" + call.calleeName() + "' expects " + parameterTypes.size()
                                    + " argument(s), found " + argumentTypes.size(),
                            null));
                }

                int checked = Math.min(argumentTypes.size(), parameterTypes.size());
                for (int i = 0; i < checked; i++) {
                    Optional<TypeRef> argumentType = argumentTypes.get(i);
                    if (argumentType.isEmpty()) {
                        continue; // already poisoned/reported upstream
                    }
                    TypeRef parameterType = parameterTypes.get(i);
                    if (!TypeRules.isArgumentCompatible(argumentType.get(), parameterType)) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                call.range(),
                                "argument " + (i + 1) + " of '" + call.calleeName() + "': expected '"
                                        + parameterType.displayName() + "', found '"
                                        + argumentType.get().displayName() + "'",
                                null));
                    }
                }

                yield recordType(call, callee.returnType());
            }

            case NewArrayExpression newArray -> {
                Optional<TypeRef> sizeType = typeOf(newArray.size());
                boolean sizeOk = sizeType.isPresent()
                        && !sizeType.get().isArray()
                        && sizeType.get().elementType() == PrimitiveType.INT;
                if (sizeType.isPresent() && !sizeOk) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            newArray.size().range(),
                            "array size must be 'num', found '" + sizeType.get().displayName() + "'",
                            null));
                }
                if (newArray.elementType() == PrimitiveType.VOID) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            newArray.range(),
                            "array element type cannot be 'none'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(newArray, new TypeRef(newArray.elementType(), true));
            }

            case ArrayAccessExpression access -> {
                Optional<TypeRef> arrayType = typeOf(access.array());
                Optional<TypeRef> indexType = typeOf(access.index());
                if (arrayType.isPresent() && !arrayType.get().isArray()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            access.array().range(),
                            "cannot index into non-array type '" + arrayType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                if (indexType.isPresent() && (indexType.get().isArray() || indexType.get().elementType() != PrimitiveType.INT)) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            access.index().range(),
                            "array index must be 'num', found '" + indexType.get().displayName() + "'",
                            null));
                }
                if (arrayType.isEmpty()) {
                    yield Optional.empty();
                }
                yield recordType(access, new TypeRef(arrayType.get().elementType(), false));
            }

            case ArrayLengthExpression length -> {
                Optional<TypeRef> arrayType = typeOf(length.array());
                if (arrayType.isEmpty()) {
                    yield Optional.empty();
                }
                if (!arrayType.get().isArray()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            length.array().range(),
                            "'.len()' can only be called on an array, found '" + arrayType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(length, new TypeRef(PrimitiveType.INT, false));
            }

            case IndexAssignmentExpression indexAssignment -> {
                Optional<TypeRef> arrayType = typeOf(indexAssignment.array());
                Optional<TypeRef> indexType = typeOf(indexAssignment.index());
                Optional<TypeRef> valueType = typeOf(indexAssignment.value());

                if (arrayType.isPresent() && !arrayType.get().isArray()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            indexAssignment.array().range(),
                            "cannot index into non-array type '" + arrayType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                if (indexType.isPresent() && (indexType.get().isArray() || indexType.get().elementType() != PrimitiveType.INT)) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            indexAssignment.index().range(),
                            "array index must be 'num', found '" + indexType.get().displayName() + "'",
                            null));
                }
                if (arrayType.isEmpty() || valueType.isEmpty()) {
                    yield Optional.empty();
                }

                TypeRef elementType = new TypeRef(arrayType.get().elementType(), false);
                if (!TypeRules.isAssignable(valueType.get(), elementType)) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            indexAssignment.range(),
                            "cannot assign '" + valueType.get().displayName() + "' to array element of type '"
                                    + elementType.displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(indexAssignment, elementType);
            }

            // Milestone S4 Phase 2: mirrors the FunctionCallExpression case
            // immediately above exactly — argument count, then per-position
            // argument-type compatibility via the existing, unmodified
            // TypeRules.isArgumentCompatible() (no parallel type-checking
            // logic). structTable.get(...) here is a second, idempotent
            // lookup of the same fact resolveDeclaredType() already checked
            // in analyzeExpr — there is no per-construction resolution map
            // to read instead (SemanticModel gains no new map for this;
            // CodeGenerator can already get from this TypeRef's structName()
            // back to the same StructSymbol via structTable()).
            case NewStructExpression construction -> {
                List<Optional<TypeRef>> argumentTypes = new ArrayList<>();
                for (Expr argument : construction.arguments()) {
                    argumentTypes.add(typeOf(argument));
                }

                StructSymbol structSymbol = structTable.get(construction.structName());
                if (structSymbol == null) {
                    yield Optional.empty(); // already reported in analyzeExpr
                }

                List<TypeRef> fieldTypes = structSymbol.fields().stream().map(Parameter::type).toList();
                if (argumentTypes.size() != fieldTypes.size()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            construction.range(),
                            "struct '" + construction.structName() + "' expects " + fieldTypes.size()
                                    + " argument(s), found " + argumentTypes.size(),
                            null));
                }

                int checked = Math.min(argumentTypes.size(), fieldTypes.size());
                for (int i = 0; i < checked; i++) {
                    Optional<TypeRef> argumentType = argumentTypes.get(i);
                    if (argumentType.isEmpty()) {
                        continue; // already poisoned/reported upstream
                    }
                    TypeRef fieldType = fieldTypes.get(i);
                    if (!TypeRules.isArgumentCompatible(argumentType.get(), fieldType)) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                construction.range(),
                                "argument " + (i + 1) + " of struct '" + construction.structName()
                                        + "': expected '" + fieldType.displayName() + "', found '"
                                        + argumentType.get().displayName() + "'",
                                null));
                    }
                }

                yield recordType(construction,
                        new TypeRef(PrimitiveType.BOOL, false, Optional.of(construction.structName())));
            }

            case FieldAccessExpression access -> {
                Optional<TypeRef> targetType = typeOf(access.target());
                if (targetType.isEmpty()) {
                    yield Optional.empty();
                }
                if (targetType.get().isArray() || targetType.get().structName().isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            access.range(),
                            "cannot access field '" + access.fieldName() + "' on non-struct type '"
                                    + targetType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                StructSymbol structSymbol = structTable.get(targetType.get().structName().get());
                if (structSymbol == null) {
                    yield Optional.empty(); // undefined struct already reported at the target's declaration
                }
                Optional<Parameter> field = resolveField(structSymbol, access.fieldName(), access.range());
                if (field.isEmpty()) {
                    yield Optional.empty();
                }
                yield recordType(access, field.get().type());
            }

            case FieldAssignmentExpression assignment -> {
                Optional<TypeRef> targetType = typeOf(assignment.target());
                Optional<TypeRef> valueType = typeOf(assignment.value());

                if (targetType.isEmpty()) {
                    yield Optional.empty();
                }
                if (targetType.get().isArray() || targetType.get().structName().isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            assignment.range(),
                            "cannot access field '" + assignment.fieldName() + "' on non-struct type '"
                                    + targetType.get().displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                StructSymbol structSymbol = structTable.get(targetType.get().structName().get());
                if (structSymbol == null) {
                    yield Optional.empty(); // undefined struct already reported at the target's declaration
                }
                Optional<Parameter> field = resolveField(structSymbol, assignment.fieldName(), assignment.range());
                if (field.isEmpty() || valueType.isEmpty()) {
                    yield Optional.empty();
                }
                TypeRef fieldType = field.get().type();
                if (!TypeRules.isAssignable(valueType.get(), fieldType)) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            assignment.range(),
                            "cannot assign '" + valueType.get().displayName() + "' to field of type '"
                                    + fieldType.displayName() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(assignment, fieldType);
            }
        };
    }

    // Reused verbatim by both FieldAccessExpression and
    // FieldAssignmentExpression — field lookup always happens directly
    // against structTable.get(...).fields(), never through a cached map, so
    // there is exactly one place this scan and its diagnostic live.
    private Optional<Parameter> resolveField(StructSymbol struct, String fieldName, SourceRange range) {
        Optional<Parameter> field = struct.fields().stream()
                .filter(candidate -> candidate.name().equals(fieldName))
                .findFirst();
        if (field.isEmpty()) {
            reporter.report(new Diagnostic(
                    ErrorPhase.SEMANTIC,
                    range,
                    "struct '" + struct.name() + "' has no field '" + fieldName + "'",
                    null));
        }
        return field;
    }

    private Optional<TypeRef> recordType(Expr expr, TypeRef type) {
        expressionTypes.put(expr, type);
        return Optional.of(type);
    }

    // Java has no built-in identity Set; this is the standard idiom for one.
    // Required (not just "safer") for currentAssigned specifically: a plain
    // HashSet<VariableSymbol> would conflate two shadowed variables sharing a
    // name if their types/positions ever happened to make them structurally
    // equal — the same reasoning behind SemanticModel's IdentityHashMaps.
    private static Set<VariableSymbol> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<VariableSymbol> copyOfAssigned(Set<VariableSymbol> source) {
        Set<VariableSymbol> copy = newIdentitySet();
        copy.addAll(source);
        return copy;
    }

    private static Set<VariableSymbol> intersectAssigned(Set<VariableSymbol> a, Set<VariableSymbol> b) {
        Set<VariableSymbol> result = newIdentitySet();
        for (VariableSymbol symbol : a) {
            if (b.contains(symbol)) {
                result.add(symbol);
            }
        }
        return result;
    }
}
