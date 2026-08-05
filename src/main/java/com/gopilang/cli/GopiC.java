package com.gopilang.cli;

import com.gopilang.ast.Program;
import com.gopilang.errors.Diagnostic;
import com.gopilang.errors.DiagnosticReporter;
import com.gopilang.lexer.Lexer;
import com.gopilang.lexer.Token;
import com.gopilang.parser.Parser;
import com.gopilang.printer.AstPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GopiC {

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--tokens")) {
            printTokens(Path.of(args[1]));
        } else if (args.length == 2 && args[0].equals("--ast")) {
            printAst(Path.of(args[1]));
        } else {
            printUsage();
        }
    }

    private static void printTokens(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile);
        String[] sourceLines = source.split("\n", -1);

        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scanTokens();

        for (Token token : tokens) {
            System.out.printf("%-16s lexeme=%-14s literal=%-12s %s%n",
                    token.type(),
                    "'" + token.lexeme() + "'",
                    token.literal() == null ? "-" : token.literal(),
                    token.location());
        }

        printDiagnostics("lexical", lexer.reporter(), sourceLines);
    }

    private static void printAst(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile);
        String[] sourceLines = source.split("\n", -1);

        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens);
        Program program = parser.parseProgram();

        // Diagnostics first, then the AST — recovery means the tree may be
        // partial even when errors were found; showing both is more useful
        // for debugging than hiding the tree behind "no errors".
        printDiagnostics("lexical", lexer.reporter(), sourceLines);
        printDiagnostics("syntax", parser.reporter(), sourceLines);
        System.out.print(AstPrinter.print(program));
    }

    private static void printDiagnostics(String phaseLabel, DiagnosticReporter reporter, String[] sourceLines) {
        if (!reporter.hasErrors()) {
            return;
        }
        System.out.println();
        System.out.println(reporter.diagnostics().size() + " " + phaseLabel + " error(s):");
        System.out.println();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            int lineIndex = diagnostic.range().start().line() - 1;
            String sourceLine = (lineIndex >= 0 && lineIndex < sourceLines.length) ? sourceLines[lineIndex] : "";
            System.out.println(diagnostic.render(sourceLine));
        }
    }

    private static void printUsage() {
        System.out.println("Usage: gopic --tokens <file.gopi>  |  gopic --ast <file.gopi>");
    }
}
