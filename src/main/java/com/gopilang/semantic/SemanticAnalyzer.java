package com.gopilang.semantic;

import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BlockStatement;
import com.gopilang.ast.Expr;
import com.gopilang.ast.ExpressionStatement;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.FunctionDeclaration;
import com.gopilang.ast.GroupingExpression;
import com.gopilang.ast.IfStatement;
import com.gopilang.ast.LiteralExpression;
import com.gopilang.ast.Parameter;
import com.gopilang.ast.PrintStatement;
import com.gopilang.ast.Program;
import com.gopilang.ast.ReturnStatement;
import com.gopilang.ast.Stmt;
import com.gopilang.ast.UnaryExpression;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.DiagnosticReporter;
import com.gopilang.errors.ErrorPhase;
import com.gopilang.types.PrimitiveType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private final Map<String, FunctionSymbol> functionTable = new HashMap<>();
    private final Map<VariableExpression, VariableSymbol> variableResolutions = new IdentityHashMap<>();
    private final Map<AssignmentExpression, VariableSymbol> assignmentTargetResolutions = new IdentityHashMap<>();
    private final Map<FunctionCallExpression, FunctionSymbol> callResolutions = new IdentityHashMap<>();
    private final Map<Expr, PrimitiveType> expressionTypes = new IdentityHashMap<>();

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
        registerFunctions();
        for (FunctionDeclaration function : program.functions()) {
            analyzeFunction(function);
        }
        validateMainFunction();
        return new SemanticModel(
                functionTable, variableResolutions, assignmentTargetResolutions, callResolutions, expressionTypes);
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
        if (main.returnType() != PrimitiveType.VOID || !main.parameterTypes().isEmpty()) {
            reporter.report(new Diagnostic(
                    ErrorPhase.SEMANTIC,
                    main.declaredAt(),
                    "'main' must have the signature 'none main()'",
                    "found return type '" + main.returnType() + "' with "
                            + main.parameterTypes().size() + " parameter(s)"));
        }
    }

    // Pass 1: register every function's signature before analyzing any body,
    // so forward references and mutual recursion resolve correctly. Duplicate
    // names are reported, not thrown — the first declaration stays
    // authoritative (matching Scope.define()'s exact putIfAbsent semantics),
    // so later, unrelated references aren't affected by the duplicate.
    private void registerFunctions() {
        for (FunctionDeclaration function : program.functions()) {
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
        if (function.returnType() != PrimitiveType.VOID && !returnsOnAllPaths(function.body())) {
            reporter.report(new Diagnostic(
                    ErrorPhase.TYPE,
                    function.range(),
                    "function '" + function.name() + "' does not return a value on all paths",
                    "declared to return '" + function.returnType() + "'"));
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
                // Checked before insertion, deliberately — a variable's own
                // initializer must not be able to see itself (int y = y + 1;
                // sees an outer y, or is undefined; never "the y being
                // declared right now").
                decl.initializer().ifPresent(init -> checkExpr(init).ifPresent(type -> {
                    if (!TypeRules.isAssignable(type, decl.type())) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                init.range(),
                                "cannot assign '" + type + "' to variable of type '" + decl.type() + "'",
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
                    if (type != PrimitiveType.BOOL) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                ifStmt.condition().range(),
                                "if condition must be 'BOOL', found '" + type + "'",
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
                    if (type != PrimitiveType.BOOL) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                whileStmt.condition().range(),
                                "while condition must be 'BOOL', found '" + type + "'",
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
                PrimitiveType declaredReturnType = currentFunction.returnType();
                if (returnStmt.value().isEmpty()) {
                    if (declaredReturnType != PrimitiveType.VOID) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                returnStmt.range(),
                                "missing return value in function declared to return '" + declaredReturnType + "'",
                                null));
                    }
                } else {
                    Expr value = returnStmt.value().get();
                    Optional<PrimitiveType> valueType = checkExpr(value);
                    if (declaredReturnType == PrimitiveType.VOID) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                value.range(),
                                "cannot return a value from a function declared 'VOID'",
                                null));
                    } else {
                        valueType.ifPresent(type -> {
                            if (!TypeRules.isReturnCompatible(type, declaredReturnType)) {
                                reporter.report(new Diagnostic(
                                        ErrorPhase.TYPE,
                                        value.range(),
                                        "cannot return '" + type + "' from a function declared to return '"
                                                + declaredReturnType + "'",
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
                            "cannot print a value of type '" + type + "'",
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
    private Optional<PrimitiveType> checkExpr(Expr expr) {
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
    public Optional<PrimitiveType> typeOf(Expr expr) {
        return switch (expr) {
            case LiteralExpression literal -> recordType(literal, literal.type());

            case VariableExpression variable -> {
                VariableSymbol symbol = variableResolutions.get(variable);
                yield symbol == null ? Optional.empty() : recordType(variable, symbol.type());
            }

            case GroupingExpression grouping -> {
                Optional<PrimitiveType> innerType = typeOf(grouping.inner());
                yield innerType.isEmpty() ? Optional.empty() : recordType(grouping, innerType.get());
            }

            case UnaryExpression unary -> {
                Optional<PrimitiveType> operandType = typeOf(unary.operand());
                if (operandType.isEmpty()) {
                    yield Optional.empty();
                }
                Optional<PrimitiveType> result = TypeRules.resultOfUnary(unary.operator(), operandType.get());
                if (result.isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            unary.range(),
                            "operator '" + unary.operator() + "' cannot be applied to '" + operandType.get() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(unary, result.get());
            }

            case BinaryExpression binary -> {
                Optional<PrimitiveType> leftType = typeOf(binary.left());
                Optional<PrimitiveType> rightType = typeOf(binary.right());
                if (leftType.isEmpty() || rightType.isEmpty()) {
                    yield Optional.empty();
                }
                Optional<PrimitiveType> result =
                        TypeRules.resultOfBinary(leftType.get(), binary.operator(), rightType.get());
                if (result.isEmpty()) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            binary.range(),
                            "operator '" + binary.operator() + "' cannot be applied to '" + leftType.get()
                                    + "' and '" + rightType.get() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(binary, result.get());
            }

            case AssignmentExpression assignment -> {
                Optional<PrimitiveType> valueType = typeOf(assignment.value());
                VariableSymbol target = assignmentTargetResolutions.get(assignment);
                if (target == null || valueType.isEmpty()) {
                    yield Optional.empty();
                }
                if (!TypeRules.isAssignable(valueType.get(), target.type())) {
                    reporter.report(new Diagnostic(
                            ErrorPhase.TYPE,
                            assignment.range(),
                            "cannot assign '" + valueType.get() + "' to variable of type '" + target.type() + "'",
                            null));
                    yield Optional.empty();
                }
                yield recordType(assignment, target.type());
            }

            case FunctionCallExpression call -> {
                List<Optional<PrimitiveType>> argumentTypes = new ArrayList<>();
                for (Expr argument : call.arguments()) {
                    argumentTypes.add(typeOf(argument));
                }

                FunctionSymbol callee = callResolutions.get(call);
                if (callee == null) {
                    yield Optional.empty();
                }

                List<PrimitiveType> parameterTypes = callee.parameterTypes();
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
                    Optional<PrimitiveType> argumentType = argumentTypes.get(i);
                    if (argumentType.isEmpty()) {
                        continue; // already poisoned/reported upstream
                    }
                    PrimitiveType parameterType = parameterTypes.get(i);
                    if (!TypeRules.isArgumentCompatible(argumentType.get(), parameterType)) {
                        reporter.report(new Diagnostic(
                                ErrorPhase.TYPE,
                                call.range(),
                                "argument " + (i + 1) + " of '" + call.calleeName() + "': expected '"
                                        + parameterType + "', found '" + argumentType.get() + "'",
                                null));
                    }
                }

                yield recordType(call, callee.returnType());
            }
        };
    }

    private Optional<PrimitiveType> recordType(Expr expr, PrimitiveType type) {
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
