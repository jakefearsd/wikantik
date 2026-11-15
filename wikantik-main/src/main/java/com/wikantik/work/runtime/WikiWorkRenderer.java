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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.work.WorkRenderer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders work item text (descriptions, comments) to HTML through the page pipeline, display-only.
 * See {@link #toHtml} for the guarantees.
 */
public final class WikiWorkRenderer implements WorkRenderer {

    /** The throwaway page every render runs against; the angle brackets keep it from being a real page name. */
    static final String SCRATCH_PAGE = "<work>";

    private final Engine engine;

    public WikiWorkRenderer( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Same pipeline, sanitising and wikilink/math/fence handling as a page, with these differences, each deliberate:
     * <ul>
     *   <li>the render runs against a throwaway page, so an {@code [{ALLOW ...}]} line in the text can only touch that
     *       page, and is shown as nothing (display renders strip access rules);</li>
     *   <li>nothing is registered with the reference manager (that only happens when a page is saved);</li>
     *   <li>the context is flagged {@link Context#VAR_VIEWER_SENSITIVE}, so the shared document and HTML caches never
     *       store the output;</li>
     *   <li>plugins do not run, as in the editor preview.</li>
     * </ul>
     */
    @Override
    public String toHtml( final String markdown ) {
        final RenderingManager rm = RenderingSubsystemBridge.fromLegacyEngine( engine ).renderingManager();
        final Page scratch = Wiki.contents().page( engine, SCRATCH_PAGE );
        final Context ctx = Wiki.context().create( engine, scratch );
        ctx.setPage( scratch );
        ctx.setRealPage( scratch );
        ctx.setVariable( Context.VAR_EXECUTE_PLUGINS, Boolean.FALSE );
        ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
        final String html = rm.textToHTML( ctx, demoteEmbeds( markdown == null ? "" : markdown ) );
        return uniformLinks( ctx, html );
    }

    /** A link the pipeline drew for a page that does not exist: an anchor carrying the create-page class. */
    private static final Pattern CREATE_PAGE_ANCHOR = Pattern.compile( "<a\\b[^>]*\\bclass=\"createpage\"[^>]*>" );
    private static final Pattern HREF = Pattern.compile( "\\bhref=\"([^\"]*)\"" );
    private static final String PROBE = "WorkRendererProbe";

    /**
     * The pipeline draws a link to a missing page as an edit link and one to an existing page as a read link. In a
     * work item that difference would tell any reader which page names exist, so a missing-page link is redrawn
     * exactly as a read link: view URL, {@code wikipage} class, no "Create" title.
     */
    private static String uniformLinks( final Context ctx, final String html ) {
        final String editPrefix = urlPrefix( ctx, ContextEnum.PAGE_EDIT );
        final String viewPrefix = urlPrefix( ctx, ContextEnum.PAGE_VIEW );
        final Matcher m = CREATE_PAGE_ANCHOR.matcher( html );
        final StringBuilder out = new StringBuilder();
        while ( m.find() ) {
            final Matcher href = HREF.matcher( m.group() );
            String replacement = m.group();
            if ( href.find() && href.group( 1 ).startsWith( editPrefix ) ) {
                replacement = "<a href=\"" + viewPrefix + href.group( 1 ).substring( editPrefix.length() )
                    + "\" class=\"wikipage\">";
            }
            m.appendReplacement( out, Matcher.quoteReplacement( replacement ) );
        }
        m.appendTail( out );
        return out.toString();
    }

    private static String urlPrefix( final Context ctx, final ContextEnum kind ) {
        final String url = ctx.getURL( kind.getRequestContext(), PROBE );
        return url.substring( 0, url.lastIndexOf( PROBE ) );
    }

    /**
     * Page embeds ({@code ![[Page]]}) would show a page's content, or that it is missing or restricted, so they are
     * drawn as plain links instead. Fenced code is left alone.
     */
    static String demoteEmbeds( final String markdown ) {
        if ( !markdown.contains( "![[" ) ) {
            return markdown;
        }
        final StringBuilder out = new StringBuilder( markdown.length() );
        boolean fenced = false;
        for ( final String line : markdown.split( "\n", -1 ) ) {
            if ( line.stripLeading().startsWith( "```" ) || line.stripLeading().startsWith( "~~~" ) ) {
                fenced = !fenced;
            }
            out.append( fenced ? line : line.replace( "![[", "[[" ) ).append( '\n' );
        }
        out.setLength( out.length() - 1 );
        return out.toString();
    }
}
