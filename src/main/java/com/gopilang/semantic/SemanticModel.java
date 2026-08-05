package com.gopilang.semantic;

import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.Expr;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.VariableExpression;
import com.gopilang.types.PrimitiveType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public record SemanticModel(
        Map<String, FunctionSymbol> functionTable,
        Map<VariableExpression, VariableSymbol> variableResolutions,
        Map<AssignmentExpression, VariableSymbol> assignmentTargetResolutions,
        Map<FunctionCallExpression, FunctionSymbol> callResolutions,
        Map<Expr, PrimitiveType> expressionTypes
) {
    // functionTable is name-keyed (ordinary equality is correct) — Map.copyOf
    // is the right tool there. The other four are node-keyed and MUST stay
    // identity-based: Map.copyOf builds its result using equals()/hashCode(),
    // so copying an IdentityHashMap through it doesn't just silently lose
    // identity semantics — it throws IllegalArgumentException("duplicate
    // key") the moment two structurally-equal-but-distinct AST nodes exist,
    // confirmed by testing this directly before shipping it. Rebuilding a
    // genuine IdentityHashMap (a true copy, immune to later mutation of the
    // source) and wrapping it unmodifiable is the correct fix.
    public SemanticModel {
        functionTable = Map.copyOf(functionTable);
        variableResolutions = Collections.unmodifiableMap(new IdentityHashMap<>(variableResolutions));
        assignmentTargetResolutions =
                Collections.unmodifiableMap(new IdentityHashMap<>(assignmentTargetResolutions));
        callResolutions = Collections.unmodifiableMap(new IdentityHashMap<>(callResolutions));
        expressionTypes = Collections.unmodifiableMap(new IdentityHashMap<>(expressionTypes));
    }
}
