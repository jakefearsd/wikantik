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


/**
 * Turns an Obsidian note body into wiki markdown: renamed {@code [[ ]]} targets, attachment references as
 * {@code Owner/file}, markdown links to notes as {@code [[ ]]}.
 * {@code %% comments %%} are kept: the reader hides them and the editor shows them (links inside one are rewritten like any other).
 * Block ids ({@code ^id}) and {@code [[x#^id]]} links are kept verbatim. Code (fenced, indented, inline) is never touched.
 */
public final class VaultBodyRewriter {

    /** Rewrites {@code body} of the note at {@code fromPath}; link targets are resolved through {@code targets}. */
    public RewriteResult rewrite( final String body, final String fromPath, final VaultTargets targets ) {
        final RewriteCtx ctx = new RewriteCtx( fromPath, targets );
        final String links = CodeSegments.mapProseAt( body, ( s, start ) -> {
            ctx.enterRun( body, start );
            return MarkdownLinkRewriter.rewrite( WikiLinkRewriter.rewrite( s, ctx ), ctx );
        } );
        return new RewriteResult( links, warnings( ctx ) );
    }

    private static List< String > warnings( final RewriteCtx ctx ) {
        final List< String > w = new ArrayList<>();
        ctx.unresolved.forEach( t -> w.add( "link: unresolved [[" + t + "]]" ) );
        return w;
    }
}
