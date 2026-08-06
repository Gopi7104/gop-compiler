package com.gopilang.lexer;

import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.DiagnosticReporter;
import com.gopilang.errors.ErrorPhase;
import com.gopilang.util.SourceLocation;
import com.gopilang.util.SourceRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written scanner that turns GopiLang source text into a flat list of
 * {@link Token}s. Recovers from a bad character/literal via panic-mode
 * (catches its own {@link LexerException}, reports a {@link Diagnostic},
 * and resumes scanning from the next character) so one bad token doesn't
 * abort the whole file.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("num", TokenType.KW_INT),
            Map.entry("dec", TokenType.KW_FLOAT),
            Map.entry("flag", TokenType.KW_BOOL),
            Map.entry("text", TokenType.KW_STRING),
            Map.entry("none", TokenType.KW_VOID),
            Map.entry("if", TokenType.KW_IF),
            Map.entry("else", TokenType.KW_ELSE),
            Map.entry("loop", TokenType.KW_WHILE),
            Map.entry("run", TokenType.KW_FOR),
            Map.entry("give", TokenType.KW_RETURN),
            Map.entry("show", TokenType.KW_PRINT),
            Map.entry("new", TokenType.KW_NEW),
            Map.entry("struct", TokenType.KW_STRUCT),
            Map.entry("yes", TokenType.BOOLEAN_LITERAL),
            Map.entry("no", TokenType.BOOLEAN_LITERAL)
    );

    private final String source;
    private final DiagnosticReporter reporter = new DiagnosticReporter();
    private final List<Token> tokens = new ArrayList<>();

    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int lineStart = 0;

    private int tokenLine;
    private int tokenColumn;

    public Lexer(String source) {
        this.source = source;
    }

    /** Diagnostics collected during {@link #scanTokens()} — empty if lexing was clean. */
    public DiagnosticReporter reporter() {
        return reporter;
    }

    /** Scans the whole source and returns its tokens, always ending in a trailing {@code EOF} token. */
    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            tokenLine = line;
            tokenColumn = current - lineStart + 1;
            try {
                scanToken();
            } catch (LexerException e) {
                reporter.report(Diagnostic.from(e));
            }
        }

        tokens.add(new Token(TokenType.EOF, "", null, new SourceLocation(line, current - lineStart + 1)));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case ' ', '\t', '\r' -> { }
            case '\n' -> { line++; lineStart = current; }

            case '(' -> addToken(TokenType.LEFT_PAREN);
            case ')' -> addToken(TokenType.RIGHT_PAREN);
            case '{' -> addToken(TokenType.LEFT_BRACE);
            case '}' -> addToken(TokenType.RIGHT_BRACE);
            case '[' -> addToken(TokenType.LEFT_BRACKET);
            case ']' -> addToken(TokenType.RIGHT_BRACKET);
            case ';' -> addToken(TokenType.SEMICOLON);
            case ',' -> addToken(TokenType.COMMA);
            case '.' -> addToken(TokenType.DOT);

            case '+' -> addToken(TokenType.PLUS);
            case '-' -> addToken(TokenType.MINUS);
            case '*' -> addToken(TokenType.STAR);
            case '%' -> addToken(TokenType.PERCENT);
            case '/' -> {
                if (match('/')) {
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else {
                    addToken(TokenType.SLASH);
                }
            }

            case '=' -> addToken(match('=') ? TokenType.EQUAL_EQUAL : TokenType.ASSIGN);
            case '!' -> addToken(match('=') ? TokenType.BANG_EQUAL : TokenType.BANG);
            case '<' -> addToken(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS);
            case '>' -> addToken(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER);

            case '&' -> {
                if (match('&')) {
                    addToken(TokenType.AND_AND);
                } else {
                    throw new LexerException("unexpected character '&'", tokenStartRange(),
                            "GopiLang has no bitwise '&' — did you mean '&&'?");
                }
            }
            case '|' -> {
                if (match('|')) {
                    addToken(TokenType.OR_OR);
                } else {
                    throw new LexerException("unexpected character '|'", tokenStartRange(),
                            "GopiLang has no bitwise '|' — did you mean '||'?");
                }
            }

            case '"' -> scanString();

            default -> {
                if (isDigit(c)) {
                    scanNumber();
                } else if (isAlpha(c)) {
                    scanIdentifierOrKeyword();
                } else {
                    throw new LexerException("unexpected character '" + c + "'", tokenStartRange());
                }
            }
        }
    }

    private void scanNumber() {
        while (isDigit(peek())) advance();

        boolean isFloat = false;
        if (peek() == '.' && isDigit(peekNext())) {
            isFloat = true;
            advance();
            while (isDigit(peek())) advance();
        }

        String lexeme = source.substring(start, current);
        if (isFloat) {
            addToken(TokenType.FLOAT_LITERAL, Double.parseDouble(lexeme));
            return;
        }

        try {
            addToken(TokenType.INT_LITERAL, Integer.parseInt(lexeme));
        } catch (NumberFormatException ex) {
            throw new LexerException("integer literal '" + lexeme + "' is too large", tokenStartRange(),
                    "GopiLang int literals must fit in a 32-bit signed integer");
        }
    }

    private void scanString() {
        StringBuilder value = new StringBuilder();

        while (peek() != '"' && peek() != '\n' && !isAtEnd()) {
            if (peek() == '\\') {
                int escapeLine = line;
                int escapeColumn = current - lineStart + 1;
                advance(); // consume the backslash

                if (isAtEnd() || peek() == '\n') break;

                char next = advance();
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    // recoverable in place: report and keep scanning for the real closing
                    // quote instead of throwing, so one bad escape can't masquerade the
                    // rest of the literal as a second, unrelated "unterminated string".
                    default -> reporter.report(new Diagnostic(
                            ErrorPhase.LEXICAL,
                            SourceRange.point(new SourceLocation(escapeLine, escapeColumn)),
                            "unknown escape sequence '\\" + next + "'",
                            "supported escapes are \\n, \\t, \\\", and \\\\"));
                }
            } else {
                value.append(advance());
            }
        }

        if (peek() == '\n' || isAtEnd()) {
            throw new LexerException("unterminated string literal", tokenStartRange(),
                    "strings cannot span multiple lines — add a closing '\"', or use \\n for a newline");
        }

        advance(); // consume closing quote
        addToken(TokenType.STRING_LITERAL, value.toString());
    }

    private void scanIdentifierOrKeyword() {
        while (isAlphaNumeric(peek())) advance();

        String lexeme = source.substring(start, current);
        TokenType type = KEYWORDS.get(lexeme);

        if (type == TokenType.BOOLEAN_LITERAL) {
            addToken(type, lexeme.equals("yes"));
        } else if (type != null) {
            addToken(type);
        } else {
            addToken(TokenType.IDENTIFIER);
        }
    }

    private SourceRange tokenStartRange() {
        return SourceRange.point(new SourceLocation(tokenLine, tokenColumn));
    }

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String lexeme = source.substring(start, current);
        tokens.add(new Token(type, lexeme, literal, new SourceLocation(tokenLine, tokenColumn)));
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return (current + 1 >= source.length()) ? '\0' : source.charAt(current + 1);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
}
