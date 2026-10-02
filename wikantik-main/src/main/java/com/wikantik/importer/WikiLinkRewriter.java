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

import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.api.parser.WikiLinkSyntax.WikiLinkRef;

/** Rewrites {@code [[ ]]} links and embeds for the wiki, using the shared {@link WikiLinkSyntax} parser. */
final class WikiLinkRewriter {

    private WikiLinkRewriter() {
    }

    static String rewrite( final String prose, final RewriteCtx ctx ) {
        return WikiLinkSyntax.replaceAll( prose, ref -> rewriteOne( prose, ref, ctx ) );
    }

    private static String rewriteOne( final String prose, final WikiLinkRef ref, final RewriteCtx ctx ) {
        if ( ref.isSamePage() ) {
            return null;
        }
        final String sep = separator( prose, ref, ctx );
        final String t = ref.target();
        final int before = ctx.blockRefs;
        final String frag = ctx.cutBlock( ref.heading() );
        if ( hasExtension( t ) ) {
            final LinkTarget att = ctx.targets.attachment( t, ctx.fromPath, false );
            if ( att.kind() == LinkTarget.Kind.RENAME ) {
                return build( ref, att.value(), frag, ref.alias(), sep );
            }
            if ( att.kind() == LinkTarget.Kind.KEEP ) {
                return null;
            }
        }
        return rewritePage( ref, frag, sep, ctx, ctx.blockRefs != before );
    }

    private static String rewritePage( final WikiLinkRef ref, final String frag, final String sep, final RewriteCtx ctx,
                                       final boolean blockCut ) {
        final String t = ref.target();
        final LinkTarget lt = ctx.targets.page( t, ctx.fromPath, false );
        if ( lt.kind() == LinkTarget.Kind.RENAME && !lt.value().equals( t ) ) {
            final String alias = ref.alias() != null || ref.embed() ? ref.alias()
                    : frag == null ? t : t + " > " + frag;
            return build( ref, lt.value(), frag, alias, sep );
        }
        if ( lt.kind() == LinkTarget.Kind.UNRESOLVED ) {
            ctx.unresolvedPage( t );
        }
        return blockCut ? build( ref, t, frag, ref.alias(), sep ) : null;
    }

    /** The separator the token used (decided by the character before its first pipe), else {@code \\|} in a table row. */
    private static String separator( final String prose, final WikiLinkRef ref, final RewriteCtx ctx ) {
        final String token = prose.substring( ref.start(), ref.end() );
        final int pipe = token.indexOf( '|' );
        if ( pipe < 0 ) {
            return ctx.inTableRow( prose, ref.start() ) ? "\\|" : "|";
        }
        return pipe > 0 && token.charAt( pipe - 1 ) == '\\' ? "\\|" : "|";
    }

    private static String build( final WikiLinkRef ref, final String target, final String frag, final String alias,
                                 final String sep ) {
        return ( ref.embed() ? "!" : "" ) + "[[" + target + ( frag != null ? "#" + frag : "" )
                + ( alias != null ? sep + alias : "" ) + "]]";
    }

    /** True when the last path segment has an extension other than {@code md}. */
    static boolean hasExtension( final String t ) {
        final int dot = t.lastIndexOf( '.' );
        return dot > t.lastIndexOf( '/' ) && dot < t.length() - 1 && !"md".equalsIgnoreCase( t.substring( dot + 1 ) );
    }
}
