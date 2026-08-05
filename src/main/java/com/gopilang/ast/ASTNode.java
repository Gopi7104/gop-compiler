package com.gopilang.ast;

import com.gopilang.util.SourceRange;

public sealed interface ASTNode permits Program, Parameter, FunctionDeclaration, Stmt, Expr {
    SourceRange range();
}
