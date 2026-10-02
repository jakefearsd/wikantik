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
package com.wikantik.importer;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.wikantik.api.parser.CodeMask;

/**
 * Applies a text transform to the non-code parts of a Markdown body. What counts as code is decided by
 * {@link CodeMask}, the same scan the renderer-adjacent link scanner and rename use, so the importer never
 * disagrees with the wiki about what is code. Line terminators are preserved exactly: with an identity
 * function the result equals the input.
 */
public final class CodeSegments {

    private CodeSegments() {
    }

    /** Applies {@code f} to everything except fenced code blocks (inline code spans are NOT protected). */
    public static String mapOutsideFences( final String md, final UnaryOperator< String > f ) {
        return map( md, CodeMask.fenced( md ), f );
    }

    /** Applies {@code f} to prose only: fenced blocks, indented code blocks and inline code spans are left untouched. */
    public static String mapProse( final String md, final UnaryOperator< String > f ) {
        return map( md, CodeMask.of( md ), f );
    }

    /** Calls {@code c} with each maximal run of prose text, in order. */
    public static void forEachProse( final String md, final Consumer< String > c ) {
        mapProse( md, s -> {
            c.accept( s );
            return s;
        } );
    }

    /** {@link CodeMask} leaves the line break between two code lines unmarked; it belongs to the code run. */
    private static void bridgeLineBreaks( final String md, final boolean[] code ) {
        for ( int i = 1; i < md.length() - 1; i++ ) {
            if ( md.charAt( i ) == '\n' && code[ i - 1 ] && code[ i + 1 ] ) {
                code[ i ] = true;
            }
        }
    }

    private static String map( final String md, final boolean[] code, final UnaryOperator< String > f ) {
        bridgeLineBreaks( md, code );
        final StringBuilder out = new StringBuilder( md.length() );
        int start = 0;
        while ( start < md.length() ) {
            final boolean isCode = code[ start ];
            int end = start + 1;
            while ( end < md.length() && code[ end ] == isCode ) {
                end++;
            }
            final String run = md.substring( start, end );
            out.append( isCode ? run : f.apply( run ) );
            start = end;
        }
        return out.toString();
    }
}
