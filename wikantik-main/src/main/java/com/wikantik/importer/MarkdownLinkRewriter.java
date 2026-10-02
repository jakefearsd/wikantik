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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Converts markdown links and images that point at vault notes or files into wiki links and embeds. */
final class MarkdownLinkRewriter {

    private static final Logger LOG = LogManager.getLogger( MarkdownLinkRewriter.class );
    private static final Pattern LINK = Pattern.compile(
            "(!?)\\[([^\\]\\n]*)\\]\\((?:<([^>\\n]+)>|([^)\\s]+))(?:\\s+\"[^\"\\n]*\")?\\)" );
    private static final Pattern SCHEME = Pattern.compile( "^[A-Za-z][A-Za-z0-9+.-]*:.*", Pattern.DOTALL );

    private MarkdownLinkRewriter() {
    }

    static String rewrite( final String prose, final RewriteCtx ctx ) {
        final Matcher m = LINK.matcher( prose );
        final StringBuilder sb = new StringBuilder();
        while ( m.find() ) {
            final String dest = m.group( 3 ) != null ? m.group( 3 ) : m.group( 4 );
            final String rep = convert( m.group( 0 ), m.group( 1 ), m.group( 2 ), dest, ctx );
            m.appendReplacement( sb, Matcher.quoteReplacement( rep ) );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String convert( final String whole, final String bang, final String text, final String dest,
                                   final RewriteCtx ctx ) {
        if ( SCHEME.matcher( dest ).matches() || dest.startsWith( "#" ) ) {
            return whole;
        }
        final String decoded;
        try {
            decoded = URLDecoder.decode( dest.replace( "+", "%2B" ), StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Leaving markdown link with undecodable destination '{}': {}", dest, e.getMessage(), e );
            return whole;
        }
        final int hash = decoded.indexOf( '#' );
        final String path = hash < 0 ? decoded : decoded.substring( 0, hash );
        final String frag = hash < 0 ? null : ctx.cutBlock( decoded.substring( hash + 1 ) );
        final String out = path.toLowerCase( java.util.Locale.ROOT ).endsWith( ".md" )
                ? note( bang, text, path, frag, ctx ) : file( bang, text, path, ctx );
        return out == null ? whole : out;
    }

    private static String note( final String bang, final String text, final String path, final String frag,
                                final RewriteCtx ctx ) {
        final String page = path.substring( 0, path.length() - 3 );
        final LinkTarget lt = ctx.targets.page( page, ctx.fromPath, true );
        final String name;
        switch ( lt.kind() ) {
            case RENAME -> name = lt.value();
            case KEEP -> name = page.substring( page.lastIndexOf( '/' ) + 1 );
            default -> {
                ctx.unresolvedPage( page );
                return null;
            }
        }
        final String target = name + ( frag != null && !frag.isEmpty() ? "#" + frag : "" );
        return !bang.isEmpty() ? "![[" + target + "]]" : "[[" + target + ( text.isEmpty() ? "" : "|" + text ) + "]]";
    }

    private static String file( final String bang, final String text, final String path, final RewriteCtx ctx ) {
        final LinkTarget lt = ctx.targets.attachment( path, ctx.fromPath, true );
        if ( lt.kind() != LinkTarget.Kind.RENAME ) {
            return null;
        }
        if ( !bang.isEmpty() ) {
            return "![[" + lt.value() + "]]";
        }
        return "[[" + lt.value() + ( text.isEmpty() ? "" : "|" + text ) + "]]";
    }
}
