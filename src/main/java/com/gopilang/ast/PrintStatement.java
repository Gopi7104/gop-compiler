package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public record PrintStatement(Expr value, SourceRange range) implements Stmt {
}
