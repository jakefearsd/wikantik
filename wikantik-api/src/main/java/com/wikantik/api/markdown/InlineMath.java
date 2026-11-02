/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.api.markdown;

/**
 * Pandoc's {@code tex_math_dollars} rule for inline {@code $…$} math, which Obsidian follows: the one Java copy, used by
 * the renderer's inline-math parser and linter ({@code InlineMathRule} in wikantik-main) and by {@link ObsidianComments}
 * (the editor's copy is {@code inlineMath.js}).
 * <ol>
 *   <li>{@code $$} never opens inline math.</li>
 *   <li>The char after the opening {@code $} must exist and not be whitespace.</li>
 *   <li>A backslash consumes itself and the next char, so {@code \$} inside math never closes it.</li>
 *   <li>Whitespace inside is allowed unless it spans a blank line or directly precedes the closing {@code $}.</li>
 *   <li>The first unescaped {@code $} closes; if an ASCII digit follows it there is no match (currency).</li>
 * </ol>
 */
public final class InlineMath {

    private InlineMath() {
    }

    /**
     * Applies the rule to the {@code $} at {@code dollarIndex}.
     *
     * @return the exclusive end index of the span (just past the closing {@code $}), or -1 for no match
     */
    public static int matchEnd( final CharSequence s, final int dollarIndex ) {
        final int n = s.length();
        final int first = dollarIndex + 1;
        if ( first >= n || s.charAt( first ) == '$' || Character.isWhitespace( s.charAt( first ) ) ) {
            return -1;
        }
        int newlines = 0;
        boolean prevWhitespace = false;
        int j = first;
        while ( j < n ) {
            final char c = s.charAt( j );
            if ( c == '$' ) {
                return closeAt( s, j, prevWhitespace );
            }
            final int step = c == '\\' ? 2 : 1;
            if ( j + step > n ) {
                return -1;
            }
            final boolean ws = step == 1 && Character.isWhitespace( c );
            newlines = ws ? newlines + ( c == '\n' ? 1 : 0 ) : 0;
            if ( newlines >= 2 ) {
                return -1;
            }
            prevWhitespace = ws;
            j += step;
        }
        return -1;
    }

    /** End index for a closing {@code $} at {@code j}, or -1 when whitespace precedes it or a digit follows it. */
    private static int closeAt( final CharSequence s, final int j, final boolean prevWhitespace ) {
        final int end = j + 1;
        final boolean digitFollows = end < s.length() && s.charAt( end ) >= '0' && s.charAt( end ) <= '9';
        return prevWhitespace || digitFollows ? -1 : end;
    }
}
