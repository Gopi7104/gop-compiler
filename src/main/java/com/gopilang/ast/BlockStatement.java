package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

/** A {@code { ... }} block: an ordered sequence of statements sharing one lexical scope. */
public record BlockStatement(List<Stmt> statements, SourceRange range) implements Stmt {
}
