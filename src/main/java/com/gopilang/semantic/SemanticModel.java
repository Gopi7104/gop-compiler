package com.gopilang.semantic;

import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.Expr;
import com.gopilang.ast.FunctionCallExpression;
import com.gopilang.ast.VariableExpression;
import com.gopilang.types.PrimitiveType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The complete, immutable output of {@code SemanticAnalyzer}: the function
 * table plus every identifier/call resolution and expression type computed
 * during analysis. Never written onto the AST nodes themselves — kept as a
 * separate model so the AST stays a pure, immutable syntax tree.
 */
public record SemanticModel(
        Map<String, FunctionSymbol> functionTable,
        Map<VariableExpression, VariableSymbol> variableResolutions,
        Map<AssignmentExpression, VariableSymbol> assignmentTargetResolutions,
        Map<FunctionCallExpression, FunctionSymbol> callResolutions,
        Map<Expr, PrimitiveType> expressionTypes
) {
    /**
     * Defensively copies all five maps. {@code functionTable} is name-keyed
     * (ordinary equality is correct) so {@code Map.copyOf} is the right tool
     * there. The other four are node-keyed and MUST stay identity-based:
     * {@code Map.copyOf} builds its result using {@code equals()}/{@code
     * hashCode()}, so copying an {@code IdentityHashMap} through it doesn't
     * just silently lose identity semantics — it throws {@code
     * IllegalArgumentException("duplicate key")} the moment two
     * structurally-equal-but-distinct AST nodes exist. Rebuilding a genuine
     * {@code IdentityHashMap} (a true copy, immune to later mutation of the
     * source) and wrapping it unmodifiable is the correct fix.
     */
    public SemanticModel {
        functionTable = Map.copyOf(functionTable);
        variableResolutions = Collections.unmodifiableMap(new IdentityHashMap<>(variableResolutions));
        assignmentTargetResolutions =
                Collections.unmodifiableMap(new IdentityHashMap<>(assignmentTargetResolutions));
        callResolutions = Collections.unmodifiableMap(new IdentityHashMap<>(callResolutions));
        expressionTypes = Collections.unmodifiableMap(new IdentityHashMap<>(expressionTypes));
    }
}
