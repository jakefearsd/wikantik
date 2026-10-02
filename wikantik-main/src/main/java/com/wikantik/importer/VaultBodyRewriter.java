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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.api.parser.CodeMask;

/**
 * Turns an Obsidian note body into wiki markdown: renamed {@code [[ ]]} targets, attachment references as
 * {@code Owner/file}, markdown links to notes as {@code [[ ]]}, and block references and {@code %% comments %%}
 * stripped. Code (fenced, indented, inline) is never touched.
 */
public final class VaultBodyRewriter {

    private static final Pattern COMMENT = Pattern.compile( "%%[\\s\\S]*?%%" );
    /** Anchored at the start of a blank run (same matches as without the look-behind; one attempt per run, not per blank). */
    private static final Pattern TRAILING_BLOCK = Pattern.compile( "(?m)(?<![ \\t])[ \\t]+\\^[A-Za-z0-9-]+[ \\t]*$" );
    private static final Pattern LINE_BLOCK = Pattern.compile( "(?m)^\\^[A-Za-z0-9-]+[ \\t]*$" );

    /** Rewrites {@code body} of the note at {@code fromPath}; link targets are resolved through {@code targets}. */
    public RewriteResult rewrite( final String body, final String fromPath, final VaultTargets targets ) {
        final RewriteCtx ctx = new RewriteCtx( fromPath, targets );
        final String noComments = CodeSegments.mapProse( body, s -> removeComments( s, ctx ) );
        final String links = CodeSegments.mapProseAt( noComments, ( s, start ) -> {
            ctx.enterRun( noComments, start );
            return MarkdownLinkRewriter.rewrite( WikiLinkRewriter.rewrite( s, ctx ), ctx );
        } );
        return new RewriteResult( stripBlockMarkers( links, ctx ), warnings( ctx ) );
    }

    private static String removeComments( final String s, final RewriteCtx ctx ) {
        final Matcher m = COMMENT.matcher( s );
        final StringBuilder sb = new StringBuilder();
        while ( m.find() ) {
            ctx.comments++;
            m.appendReplacement( sb, "" );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String stripBlockMarkers( final String s, final RewriteCtx ctx ) {
        return strip( LINE_BLOCK, strip( TRAILING_BLOCK, s, ctx ), ctx );
    }

    private static String strip( final Pattern p, final String s, final RewriteCtx ctx ) {
        final Matcher m = p.matcher( maskCode( s ) );
        final StringBuilder sb = new StringBuilder();
        int pos = 0;
        while ( m.find() ) {
            ctx.blockRefs++;
            sb.append( s, pos, m.start() );
            pos = m.end();
        }
        return sb.append( s, pos, s.length() ).toString();
    }

    /** Same length as {@code s}, with every code character (newlines excepted) replaced so patterns anchor at real line ends. */
    private static String maskCode( final String s ) {
        final boolean[] code = CodeMask.of( s );
        final char[] c = s.toCharArray();
        for ( int i = 0; i < c.length; i++ ) {
            if ( code[ i ] && c[ i ] != '\n' ) {
                c[ i ] = 'X';
            }
        }
        return new String( c );
    }

    private static List< String > warnings( final RewriteCtx ctx ) {
        final List< String > w = new ArrayList<>();
        if ( ctx.comments > 0 ) {
            w.add( "comment: " + ctx.comments + " Obsidian comment(s) removed" );
        }
        ctx.unresolved.forEach( t -> w.add( "link: unresolved [[" + t + "]]" ) );
        if ( ctx.blockRefs > 0 ) {
            w.add( "blockref: " + ctx.blockRefs + " block reference(s) stripped" );
        }
        return w;
    }
}
