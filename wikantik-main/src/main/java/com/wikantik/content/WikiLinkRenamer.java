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
package com.wikantik.content;

import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.parser.MarkupParser;
import com.wikantik.util.TextUtil;

import java.util.Locale;

/**
 * Rewrites native {@code [[ ]]} links and {@code ![[ ]]} embeds when a page is renamed, keeping the embed
 * marker, heading, alias, size and attachment file. Links inside code are untouched (the syntax scanner
 * skips them); alias-resolved targets are left alone because the alias still resolves.
 */
final class WikiLinkRenamer {

    private WikiLinkRenamer() {
    }

    static String rewrite( final String text, final String from, final String to ) {
        return WikiLinkSyntax.replaceAll( text, ref -> renames( ref, from )
                ? text.substring( ref.start(), ref.nameFrom() ) + to + text.substring( ref.nameTo(), ref.end() )
                : null );
    }

    /**
     * True when {@code ref} names the page being renamed.
     *
     * <p><b>Known limitation:</b> this uses string heuristics ({@code equalsIgnoreCase}, {@code cleanLink},
     * {@code wikifyLink} and a whitespace/case fold of the beautified old name), not the
     * {@link com.wikantik.wikilink.WikiLinkResolver}. By the time referrers are rewritten the page has already been
     * moved, so the resolver can no longer answer "did this link point at the old name". A link whose text is also an
     * alias phrase of a different page (for example {@code [[old name]]} where another page lists "old name" as an
     * alias) can therefore be rewritten even though it resolved to that other page.</p>
     */
    private static boolean renames( final WikiLinkSyntax.WikiLinkRef ref, final String from ) {
        if ( ref.isSamePage() ) {
            return false;
        }
        final String name = ref.pageName();
        return name.equalsIgnoreCase( from )
                || from.equals( MarkupParser.cleanLink( name ) )
                || from.equals( MarkupParser.wikifyLink( name ) )
                || fold( name ).equals( fold( TextUtil.beautifyString( from ) ) );
    }

    private static String fold( final String s ) {
        return s.trim().replaceAll( "\\s+", " " ).toLowerCase( Locale.ROOT );
    }
}
