/**
 * The lexer stage: turns raw GopiLang source text into a flat stream of
 * {@link com.gopilang.lexer.Token}s. Hand-written, not generated. Recovers
 * from a bad character or literal via panic-mode so one lexical error
 * produces one diagnostic instead of aborting the whole file.
 */
package com.gopilang.lexer;
