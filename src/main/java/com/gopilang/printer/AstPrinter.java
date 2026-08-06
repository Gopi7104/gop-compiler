package com.gopilang.printer;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a parsed {@link Program} as a Unicode tree, for the {@code --ast}
 * CLI mode. Deliberately NOT a Visitor on the AST classes themselves — this
 * class is external to the {@code ast} package and dispatches with a plain
 * switch over the sealed {@code Stmt}/{@code Expr} hierarchies, exactly the
 * "sealed + switch instead of Visitor" decision made when the AST was
 * designed. Two responsibilities are kept separate on purpose:
 * {@code describe()} knows about GopiLang's AST; {@code render()} knows
 * only how to draw a generic labeled tree and could print anything.
 */
public final class AstPrinter {

    private AstPrinter() {
    }

    /** Renders {@code program}'s full AST as an indented Unicode tree, ending in a trailing newline. */
    public static String print(Program program) {
        TreeNode root = describe(program);
        StringBuilder out = new StringBuilder();
        out.append(root.label()).append('\n');
        renderChildren(root.children(), "", out);
        return out.toString();
    }

    private record TreeNode(String label, List<TreeNode> children) {
        private static TreeNode leaf(String label) {
            return new TreeNode(label, List.of());
        }
    }

    private static void renderChildren(List<TreeNode> children, String prefix, StringBuilder out) {
        for (int i = 0; i < children.size(); i++) {
            boolean isLast = i == children.size() - 1;
            TreeNode child = children.get(i);
            out.append(prefix).append(isLast ? "└── " : "├── ").append(child.label()).append('\n');
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            renderChildren(child.children(), childPrefix, out);
        }
    }

    private static TreeNode describe(Program program) {
        List<TreeNode> children = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            children.add(describe(function));
        }
        return new TreeNode("Program", children);
    }

    private static TreeNode describe(FunctionDeclaration function) {
        List<TreeNode> children = new ArrayList<>();
        for (Parameter parameter : function.parameters()) {
            children.add(describe(parameter));
        }
        children.add(describe(function.body()));
        String label = "FunctionDeclaration " + function.name() + "() -> " + function.returnType();
        return new TreeNode(label, children);
    }

    private static TreeNode describe(Parameter parameter) {
        return TreeNode.leaf("Parameter " + parameter.type() + " " + parameter.name());
    }

    private static TreeNode describe(Stmt stmt) {
        return switch (stmt) {
            case BlockStatement block -> {
                List<TreeNode> children = new ArrayList<>();
                for (Stmt s : block.statements()) {
                    children.add(describe(s));
                }
                yield new TreeNode("BlockStatement", children);
            }
            case VariableDeclaration decl -> {
                List<TreeNode> children = new ArrayList<>();
                decl.initializer().ifPresent(init -> children.add(describe(init)));
                yield new TreeNode("VariableDeclaration " + decl.type() + " " + decl.name(), children);
            }
            case IfStatement ifStmt -> {
                List<TreeNode> children = new ArrayList<>();
                children.add(new TreeNode("condition", List.of(describe(ifStmt.condition()))));
                children.add(new TreeNode("then", List.of(describe(ifStmt.thenBranch()))));
                ifStmt.elseBranch().ifPresent(e -> children.add(new TreeNode("else", List.of(describe(e)))));
                yield new TreeNode("IfStatement", children);
            }
            case WhileStatement whileStmt -> new TreeNode("WhileStatement", List.of(
                    new TreeNode("condition", List.of(describe(whileStmt.condition()))),
                    new TreeNode("body", List.of(describe(whileStmt.body())))));
            case ReturnStatement returnStmt -> {
                List<TreeNode> children = new ArrayList<>();
                returnStmt.value().ifPresent(v -> children.add(describe(v)));
                yield new TreeNode("ReturnStatement", children);
            }
            case PrintStatement printStmt -> new TreeNode("PrintStatement", List.of(describe(printStmt.value())));
            case ExpressionStatement exprStmt ->
                    new TreeNode("ExpressionStatement", List.of(describe(exprStmt.expression())));
        };
    }

    private static TreeNode describe(Expr expr) {
        return switch (expr) {
            case LiteralExpression literal ->
                    TreeNode.leaf("LiteralExpression " + literal.value() + " (" + literal.type() + ")");
            case VariableExpression variable -> TreeNode.leaf("VariableExpression " + variable.name());
            case GroupingExpression grouping ->
                    new TreeNode("GroupingExpression", List.of(describe(grouping.inner())));
            case UnaryExpression unary ->
                    new TreeNode("UnaryExpression [" + unary.operator() + "]", List.of(describe(unary.operand())));
            case BinaryExpression binary -> new TreeNode("BinaryExpression [" + binary.operator() + "]",
                    List.of(describe(binary.left()), describe(binary.right())));
            case AssignmentExpression assignment -> new TreeNode(
                    "AssignmentExpression " + assignment.target() + " =",
                    List.of(describe(assignment.value())));
            case FunctionCallExpression call -> {
                List<TreeNode> children = new ArrayList<>();
                for (Expr argument : call.arguments()) {
                    children.add(describe(argument));
                }
                String label = "FunctionCallExpression " + call.calleeName()
                        + "(" + call.arguments().size() + " args)";
                yield new TreeNode(label, children);
            }
        };
    }
}
