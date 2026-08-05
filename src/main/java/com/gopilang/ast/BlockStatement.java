package com.gopilang.ast;

import com.gopilang.util.SourceRange;

import java.util.List;

public record BlockStatement(List<Stmt> statements, SourceRange range) implements Stmt {
}
