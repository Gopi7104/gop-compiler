/**
 * The parser stage: a hand-written recursive-descent parser that consumes
 * the lexer's token stream and produces a {@link com.gopilang.ast.Program}.
 * Recovers from a broken statement via panic-mode synchronization so one
 * syntax error yields one diagnostic rather than a cascade.
 */
package com.gopilang.parser;
