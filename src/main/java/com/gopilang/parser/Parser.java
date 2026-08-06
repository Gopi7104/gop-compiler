package com.gopilang.parser;

import com.gopilang.ast.AssignmentExpression;
import com.gopilang.ast.BinaryExpression;
import com.gopilang.ast.BinaryOperator;
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
import com.gopilang.ast.UnaryOperator;
import com.gopilang.ast.VariableDeclaration;
import com.gopilang.ast.VariableExpression;
import com.gopilang.ast.WhileStatement;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.DiagnosticReporter;
import com.gopilang.lexer.Token;
import com.gopilang.lexer.TokenType;
import com.gopilang.types.PrimitiveType;
import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written recursive-descent parser producing a {@link Program} AST from
 * a token stream. Recovers from a broken statement via panic-mode
 * ({@link #synchronize(boolean)}, catching its own {@link ParseError}) so one
 * syntax error yields one diagnostic rather than a cascade.
 */
public final class Parser {

    private final List<Token> tokens;
    private final DiagnosticReporter reporter = new DiagnosticReporter();
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Diagnostics collected during {@link #parseProgram()} — empty if parsing was clean. */
    public DiagnosticReporter reporter() {
        return reporter;
    }

    /** Parses the whole token stream into a {@link Program} (a list of function declarations). */
    public Program parseProgram() {
        SourceLocation start = peek().location();
        List<FunctionDeclaration> functions = new ArrayList<>();
        while (!isAtEnd()) {
            try {
                functions.add(parseFunction());
            } catch (ParseError e) {
                reporter.report(Diagnostic.from(e));
                synchronize(false);
            }
        }
        // previous() is unsafe when functions is empty (current is still 0, so
        // there is no "previous" token) — an empty program's range collapses
        // to a point at its own start (EOF's location) instead.
        SourceLocation end = functions.isEmpty() ? start : previous().location();
        return new Program(functions, new SourceRange(start, end));
    }

    private FunctionDeclaration parseFunction() {
        SourceLocation start = peek().location();
        PrimitiveType returnType = parseType();
        Token name = consume(TokenType.IDENTIFIER, "expected a function name");
        consume(TokenType.LEFT_PAREN, "expected '(' after function name");
        List<Parameter> parameters = parseParameters();
        consume(TokenType.RIGHT_PAREN, "expected ')' after parameters");
        BlockStatement body = parseBlock();
        SourceLocation end = previous().location();
        return new FunctionDeclaration(returnType, name.lexeme(), parameters, body, new SourceRange(start, end));
    }

    private List<Parameter> parseParameters() {
        List<Parameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                SourceLocation paramStart = peek().location();
                PrimitiveType type = parseType();
                Token name = consume(TokenType.IDENTIFIER, "expected a parameter name");
                parameters.add(new Parameter(type, name.lexeme(), new SourceRange(paramStart, name.location())));
            } while (match(TokenType.COMMA));
        }
        return parameters;
    }

    private PrimitiveType parseType() {
        if (match(TokenType.KW_INT)) return PrimitiveType.INT;
        if (match(TokenType.KW_FLOAT)) return PrimitiveType.FLOAT;
        if (match(TokenType.KW_BOOL)) return PrimitiveType.BOOL;
        if (match(TokenType.KW_STRING)) return PrimitiveType.STRING;
        if (match(TokenType.KW_VOID)) return PrimitiveType.VOID;
        throw new ParseError("expected a type", SourceRange.point(peek().location()));
    }

    // blockItem ::= varDecl | statement — no nested functions.
    private BlockStatement parseBlock() {
        SourceLocation start = peek().location();
        consume(TokenType.LEFT_BRACE, "expected '{' to start a block");
        List<Stmt> statements = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            try {
                statements.add(parseStatement());
            } catch (ParseError e) {
                reporter.report(Diagnostic.from(e));
                synchronize(true);
            }
        }
        Token closing = consume(TokenType.RIGHT_BRACE, "expected '}' to end a block");
        return new BlockStatement(statements, new SourceRange(start, closing.location()));
    }

    private Stmt parseStatement() {
        if (check(TokenType.KW_IF)) return parseIfStatement();
        if (check(TokenType.KW_WHILE)) return parseWhileStatement();
        if (check(TokenType.KW_RETURN)) return parseReturnStatement();
        if (check(TokenType.KW_PRINT)) return parsePrintStatement();
        if (check(TokenType.LEFT_BRACE)) return parseBlock();
        if (check(TokenType.KW_INT) || check(TokenType.KW_FLOAT) || check(TokenType.KW_BOOL)
                || check(TokenType.KW_STRING) || check(TokenType.KW_VOID)) {
            return parseVariableDeclaration();
        }
        return parseExpressionStatement();
    }

    private VariableDeclaration parseVariableDeclaration() {
        SourceLocation start = peek().location();
        PrimitiveType type = parseType();
        Token name = consume(TokenType.IDENTIFIER, "expected a variable name");
        Optional<Expr> initializer = Optional.empty();
        if (match(TokenType.ASSIGN)) {
            initializer = Optional.of(parseExpression());
        }
        Token semicolon = consume(TokenType.SEMICOLON, "expected ';' after variable declaration");
        return new VariableDeclaration(type, name.lexeme(), initializer, new SourceRange(start, semicolon.location()));
    }

    private ReturnStatement parseReturnStatement() {
        SourceLocation start = peek().location();
        consume(TokenType.KW_RETURN, "expected 'give'");
        Optional<Expr> value = Optional.empty();
        if (!check(TokenType.SEMICOLON)) {
            value = Optional.of(parseExpression());
        }
        Token semicolon = consume(TokenType.SEMICOLON, "expected ';' after return statement");
        return new ReturnStatement(value, new SourceRange(start, semicolon.location()));
    }

    private PrintStatement parsePrintStatement() {
        SourceLocation start = peek().location();
        consume(TokenType.KW_PRINT, "expected 'show'");
        consume(TokenType.LEFT_PAREN, "expected '(' after 'show'");
        Expr value = parseExpression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after expression");
        Token semicolon = consume(TokenType.SEMICOLON, "expected ';' after show statement");
        return new PrintStatement(value, new SourceRange(start, semicolon.location()));
    }

    private IfStatement parseIfStatement() {
        SourceLocation start = peek().location();
        consume(TokenType.KW_IF, "expected 'if'");
        consume(TokenType.LEFT_PAREN, "expected '(' after 'if'");
        Expr condition = parseExpression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after condition");
        Stmt thenBranch = parseStatement();
        Optional<Stmt> elseBranch = Optional.empty();
        if (match(TokenType.KW_ELSE)) {
            elseBranch = Optional.of(parseStatement());
        }
        SourceLocation end = previous().location();
        return new IfStatement(condition, thenBranch, elseBranch, new SourceRange(start, end));
    }

    private WhileStatement parseWhileStatement() {
        SourceLocation start = peek().location();
        consume(TokenType.KW_WHILE, "expected 'loop'");
        consume(TokenType.LEFT_PAREN, "expected '(' after 'loop'");
        Expr condition = parseExpression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after condition");
        Stmt body = parseStatement();
        SourceLocation end = previous().location();
        return new WhileStatement(condition, body, new SourceRange(start, end));
    }

    private ExpressionStatement parseExpressionStatement() {
        SourceLocation start = peek().location();
        Expr expression = parseExpression();
        Token semicolon = consume(TokenType.SEMICOLON, "expected ';' after expression");
        return new ExpressionStatement(expression, new SourceRange(start, semicolon.location()));
    }

    private Expr parseExpression() {
        return parseAssignment();
    }

    private Expr parseAssignment() {
        if (check(TokenType.IDENTIFIER) && peekNext().type() == TokenType.ASSIGN) {
            Token name = advance();  // the identifier
            advance();               // the '='
            Expr value = parseAssignment();
            return new AssignmentExpression(name.lexeme(), value, new SourceRange(name.location(), value.range().end()));
        }
        return parseLogicalOr();
    }

    private Expr parseLogicalOr() {
        Expr expr = parseLogicalAnd();
        while (match(TokenType.OR_OR)) {
            Expr right = parseLogicalAnd();
            expr = new BinaryExpression(expr, BinaryOperator.OR, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseLogicalAnd() {
        Expr expr = parseEquality();
        while (match(TokenType.AND_AND)) {
            Expr right = parseEquality();
            expr = new BinaryExpression(expr, BinaryOperator.AND, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseEquality() {
        Expr expr = parseComparison();
        while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
            Token operatorToken = previous();
            BinaryOperator operator = operatorToken.type() == TokenType.EQUAL_EQUAL
                    ? BinaryOperator.EQUAL : BinaryOperator.NOT_EQUAL;
            Expr right = parseComparison();
            expr = new BinaryExpression(expr, operator, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseComparison() {
        Expr expr = parseTerm();
        while (match(TokenType.LESS, TokenType.GREATER, TokenType.LESS_EQUAL, TokenType.GREATER_EQUAL)) {
            Token operatorToken = previous();
            BinaryOperator operator = switch (operatorToken.type()) {
                case LESS -> BinaryOperator.LESS;
                case GREATER -> BinaryOperator.GREATER;
                case LESS_EQUAL -> BinaryOperator.LESS_EQUAL;
                case GREATER_EQUAL -> BinaryOperator.GREATER_EQUAL;
                default -> throw new IllegalStateException("unreachable: " + operatorToken.type());
            };
            Expr right = parseTerm();
            expr = new BinaryExpression(expr, operator, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseTerm() {
        Expr expr = parseFactor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token operatorToken = previous();
            BinaryOperator operator = operatorToken.type() == TokenType.PLUS
                    ? BinaryOperator.ADD : BinaryOperator.SUBTRACT;
            Expr right = parseFactor();
            expr = new BinaryExpression(expr, operator, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseFactor() {
        Expr expr = parseUnary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token operatorToken = previous();
            BinaryOperator operator = switch (operatorToken.type()) {
                case STAR -> BinaryOperator.MULTIPLY;
                case SLASH -> BinaryOperator.DIVIDE;
                case PERCENT -> BinaryOperator.MODULO;
                default -> throw new IllegalStateException("unreachable: " + operatorToken.type());
            };
            Expr right = parseUnary();
            expr = new BinaryExpression(expr, operator, right,
                    new SourceRange(expr.range().start(), right.range().end()));
        }
        return expr;
    }

    private Expr parseUnary() {
        if (match(TokenType.BANG, TokenType.MINUS)) {
            Token operatorToken = previous();
            UnaryOperator operator = operatorToken.type() == TokenType.BANG
                    ? UnaryOperator.NOT : UnaryOperator.NEGATE;
            Expr operand = parseUnary();
            return new UnaryExpression(operator, operand,
                    new SourceRange(operatorToken.location(), operand.range().end()));
        }
        return parseCall();
    }

    // call ::= primary [ "(" [ argList ] ")" ] — not in the originally listed
    // ten methods, but required for FunctionCallExpression (one of the seven
    // frozen Expr nodes) to ever be constructible; restored from Milestone 3's
    // original grammar between parseUnary() and parsePrimary().
    private Expr parseCall() {
        Expr expr = parsePrimary();
        if (match(TokenType.LEFT_PAREN)) {
            if (!(expr instanceof VariableExpression callee)) {
                throw new ParseError("only a plain function name can be called", expr.range());
            }
            List<Expr> arguments = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    arguments.add(parseExpression());
                } while (match(TokenType.COMMA));
            }
            Token closing = consume(TokenType.RIGHT_PAREN, "expected ')' after arguments");
            return new FunctionCallExpression(callee.name(), arguments,
                    new SourceRange(callee.range().start(), closing.location()));
        }
        return expr;
    }

    private Expr parsePrimary() {
        if (match(TokenType.INT_LITERAL, TokenType.FLOAT_LITERAL, TokenType.STRING_LITERAL,
                TokenType.BOOLEAN_LITERAL)) {
            Token literal = previous();
            PrimitiveType type = switch (literal.type()) {
                case INT_LITERAL -> PrimitiveType.INT;
                case FLOAT_LITERAL -> PrimitiveType.FLOAT;
                case STRING_LITERAL -> PrimitiveType.STRING;
                case BOOLEAN_LITERAL -> PrimitiveType.BOOL;
                default -> throw new IllegalStateException("unreachable: " + literal.type());
            };
            return new LiteralExpression(literal.literal(), type, SourceRange.point(literal.location()));
        }
        if (match(TokenType.IDENTIFIER)) {
            Token name = previous();
            return new VariableExpression(name.lexeme(), SourceRange.point(name.location()));
        }
        if (match(TokenType.LEFT_PAREN)) {
            SourceLocation start = previous().location();
            Expr inner = parseExpression();
            Token closing = consume(TokenType.RIGHT_PAREN, "expected ')' after expression");
            return new GroupingExpression(inner, new SourceRange(start, closing.location()));
        }
        throw new ParseError("expected an expression", SourceRange.point(peek().location()));
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    // Two-token lookahead, needed only by parseAssignment()'s IDENTIFIER "="
    // check. Safe at end-of-stream: EOF is always the last token, so once
    // current sits on it there is nothing meaningful "next" — returning EOF
    // again is the correct, bounds-safe answer.
    private Token peekNext() {
        return (current + 1 < tokens.size()) ? tokens.get(current + 1) : tokens.get(tokens.size() - 1);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && peek().type() == type;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw new ParseError(describeMismatch(message), SourceRange.point(peek().location()));
    }

    // Names what was actually found, not just what was expected — "expected
    // ')' after expression" alone leaves the reader guessing; naming the
    // offending token turns it into "expected ')' after expression, found
    // ';'". EOF gets its own phrasing since its lexeme is empty.
    private String describeMismatch(String message) {
        if (isAtEnd()) {
            return message + " (reached end of file)";
        }
        return message + ", found '" + peek().lexeme() + "'";
    }

    // Discards tokens until reaching a position it's safe to resume parsing
    // from a fresh statement/declaration.
    //
    // insideBlock distinguishes two genuinely different recovery contexts:
    //   - true  (called from parseBlock()'s catch): if current is ALREADY a
    //     '}', it must NOT be consumed — parseBlock()'s own check(RIGHT_BRACE)
    //     loop condition needs to see it, or the block's real closing brace
    //     gets swallowed and parsing runs on into whatever follows the block.
    //   - false (called from parseProgram()'s catch): there is no enclosing
    //     block waiting for a '}' here, so treating one as "stop, don't
    //     consume" would mean it's NEVER consumed — an infinite loop, since
    //     parseFunction() would keep failing on the same token forever. At
    //     this level a stray '}' is just another token to skip past.
    private void synchronize(boolean insideBlock) {
        if (insideBlock && check(TokenType.RIGHT_BRACE)) {
            return;
        }
        advance();
        while (!isAtEnd()) {
            if (previous().type() == TokenType.SEMICOLON) {
                return;
            }
            if (insideBlock && check(TokenType.RIGHT_BRACE)) {
                return;
            }
            if (isStatementStartKeyword()) {
                return;
            }
            advance();
        }
    }

    private boolean isStatementStartKeyword() {
        return switch (peek().type()) {
            case KW_IF, KW_WHILE, KW_RETURN, KW_PRINT, KW_INT, KW_FLOAT, KW_BOOL, KW_STRING, KW_VOID -> true;
            default -> false;
        };
    }
}
