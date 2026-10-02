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
package com.wikantik.wikilink;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.export.HeadingSlugs;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders the transcluded body of an {@code ![[Page]]} / {@code ![[Page#Heading]]} embed with view-ACL,
 * loop, depth and size protections. The body is rendered through the non-caching
 * {@code parse()} + {@code getHTML(Context, WikiDocument)} path so nothing is written to the HTML cache under
 * the outer page's key; every render flags the outer context viewer-sensitive.
 */
public final class WikiEmbedRenderer {

    private static final Logger LOG = LogManager.getLogger( WikiEmbedRenderer.class );

    public static final String PROP_MAX_CHARS = "wikantik.embed.maxChars";
    public static final int DEFAULT_MAX_CHARS = 20000;
    public static final int MAX_DEPTH = 3;
    public static final String ATTR_EMBED_STACK = "com.wikantik.wikilink.WikiEmbedRenderer.stack";

    /** Whether the caller may view a page (silent check, no audit). */
    @FunctionalInterface
    public interface ViewCheck {
        boolean canView( Context context, Page page );
    }

    /** Renders Markdown source to HTML in the given context. */
    @FunctionalInterface
    public interface BodyRenderer {
        String render( Context context, String markdown ) throws IOException;
    }

    public record EmbedResult( String title, String href, String bodyHtml,
                               boolean missing, boolean restricted, boolean truncated ) {}

    private final WikiLinkResolver resolver;
    private final PageManager pages;
    private final ViewCheck viewCheck;
    private final BodyRenderer bodyRenderer;
    private final int maxChars;

    public WikiEmbedRenderer( final WikiLinkResolver resolver, final PageManager pages, final ViewCheck viewCheck,
                              final BodyRenderer bodyRenderer, final int maxChars ) {
        this.resolver = resolver;
        this.pages = pages;
        this.viewCheck = viewCheck;
        this.bodyRenderer = bodyRenderer;
        this.maxChars = maxChars;
    }

    public static WikiEmbedRenderer forEngine( final Engine engine ) {
        final AuthorizationManager auth = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        final ViewCheck view = ( ctx, page ) ->
                auth.isPermitted( ctx.getWikiSession(), PermissionFactory.getPagePermission( page, "view" ) );
        return new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), PageSubsystemBridge.fromLegacyEngine( engine ).pages(), view,
                defaultBodyRenderer( engine ),
                TextUtil.getIntegerProperty( engine.getWikiProperties(), PROP_MAX_CHARS, DEFAULT_MAX_CHARS ) );
    }

    /** Non-caching renderer: parse then render the document, never touching the HTML cache. */
    public static BodyRenderer defaultBodyRenderer( final Engine engine ) {
        return ( ctx, markdown ) -> {
            final RenderingManager rm = RenderingSubsystemBridge.fromLegacyEngine( engine ).renderingManager();
            final MarkupParser parser = rm.getParser( ctx, markdown );
            final WikiDocument doc = parser.parse();
            return rm.getHTML( ctx, doc );
        };
    }

    /** Renders the embed; never throws. */
    public EmbedResult render( final Context context, final String target, final String heading ) {
        context.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
        String name = target;
        String href = "";
        try {
            final WikiLinkResolver.Resolution res = resolver.resolve( target );
            name = res.pageName();
            final String title = name + ( heading != null ? " › " + heading : "" );
            href = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), name )
                    + ( heading != null ? "#" + HeadingSlugs.slug( heading ) : "" );
            final Page page = res.exists() ? pages.getPage( name ) : null;
            if ( page == null ) {
                return missing( title, href, "<a class=\"createpage\" href=\"" + attr( editUrl( context, name ) )
                        + "\">Not created yet</a>", "wiki-embed-missing" );
            }
            return renderPage( context, page, heading, title, href );
        } catch ( final RuntimeException e ) {
            return failed( context, target, e, name, href );
        }
    }

    private EmbedResult renderPage( final Context context, final Page page, final String heading,
                                    final String title, final String href ) {
        final List< String > stack = currentStack( context );
        if ( stack.contains( page.getName() ) || stack.size() > MAX_DEPTH ) {
            return state( title, href, "wiki-embed-error", "Embed loop stopped", false, false );
        }
        if ( !viewCheck.canView( context, page ) ) {
            return state( title, href, "wiki-embed-restricted", "You don't have access to this page.", false, true );
        }
        final Optional< String > text = sourceText( page, heading );
        if ( text.isEmpty() ) {
            return state( title, href, "wiki-embed-missing", "Section not found: " + attr( heading ), true, false );
        }
        return renderBody( context, page, text.get(), stack, title, href );
    }

    private EmbedResult renderBody( final Context context, final Page page, final String source, final List< String > stack,
                                    final String title, final String href ) {
        final boolean cut = source.length() > maxChars;
        final String text = cut ? truncate( source, maxChars ) : source;
        // WikiContext.clone() shares the variable map with the original, so the stack is
        // set on the passed context and restored in finally rather than relying on the clone.
        final Object prev = context.getVariable( ATTR_EMBED_STACK );
        try {
            final Context inner = context.clone();
            inner.setPage( page );
            final List< String > next = new ArrayList<>( stack );
            next.add( page.getName() );
            inner.setVariable( ATTR_EMBED_STACK, next );
            final String html = bodyRenderer.render( inner, text );
            final String more = cut ? "<p class=\"wiki-embed-more\"><a href=\"" + attr( href ) + "\">Continue reading \u2192</a></p>" : "";
            return new EmbedResult( title, href, html + more, false, false, cut );
        } catch ( final IOException | RuntimeException e ) {
            return failed( context, page.getName(), e, title, href );
        } finally {
            context.setVariable( ATTR_EMBED_STACK, prev );
        }
    }

    private Optional< String > sourceText( final Page page, final String heading ) {
        final String text = FrontmatterParser.parse( pages.getPureText( page ) ).body();
        return heading == null ? Optional.of( text ) : HeadingSlugs.sectionBody( text, HeadingSlugs.slug( heading ) )
                .filter( s -> !s.isBlank() );
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > currentStack( final Context context ) {
        final Object o = context.getVariable( ATTR_EMBED_STACK );
        return o instanceof List< ? > ? ( List< String > ) o : List.of( context.getPage().getName() );
    }

    private static EmbedResult missing( final String title, final String href, final String innerHtml, final String cssClass ) {
        return new EmbedResult( title, href, "<p class=\"" + cssClass + "\">" + innerHtml + "</p>", true, false, false );
    }

    private static EmbedResult state( final String title, final String href, final String cssClass, final String message,
                                      final boolean missing, final boolean restricted ) {
        return new EmbedResult( title, href, "<p class=\"" + cssClass + "\">" + message + "</p>", missing, restricted, false );
    }

    private static EmbedResult failed( final Context context, final String target, final Exception e,
                                       final String title, final String href ) {
        LOG.warn( "Embed of '{}' into '{}' failed: {}", target, context.getPage() == null ? "?" : context.getPage().getName(),
                e.getMessage() );
        return state( title, href, "wiki-embed-error", "Embed could not be rendered", false, false );
    }

    /** Cuts at a block boundary at or before {@code max}, never inside an open code fence. */
    static String truncate( final String text, final int max ) {
        int cut = text.lastIndexOf( "\n\n", max );
        if ( cut < 0 ) {
            cut = text.lastIndexOf( '\n', max );
        }
        if ( cut < 0 ) {
            cut = max;
        }
        final String head = text.substring( 0, openFenceStart( text, cut ) ).stripTrailing();
        return head.isEmpty() ? text.substring( 0, max ) : head;
    }

    /** Index of the unclosed fence line's start before {@code cut}, or {@code cut} when no fence is open. */
    private static int openFenceStart( final String text, final int cut ) {
        int open = -1;
        int pos = 0;
        while ( pos < cut ) {
            int eol = text.indexOf( '\n', pos );
            if ( eol < 0 || eol > cut ) {
                eol = cut;
            }
            final String line = text.substring( pos, eol ).stripLeading();
            if ( line.startsWith( "```" ) || line.startsWith( "~~~" ) ) {
                open = open < 0 ? pos : -1;
            }
            pos = eol + 1;
        }
        return open < 0 ? cut : open;
    }

    /** The full {@code <div class="wiki-embed">} block. */
    public String renderBlock( final Context context, final String target, final String heading ) {
        final EmbedResult r = render( context, target, heading );
        final String section = heading != null ? " data-section=\"" + attr( heading ) + "\"" : "";
        final String title = attr( r.title() );
        final String titleHtml = r.restricted() ? title : "<a class=\"wikipage\" href=\"" + attr( r.href() ) + "\">" + title + "</a>";
        return "<div class=\"wiki-embed\" data-embed=\"" + attr( target ) + "\"" + section
                + "><div class=\"wiki-embed-title\">" + titleHtml + "</div><div class=\"wiki-embed-body\">"
                + r.bodyHtml() + "</div></div>";
    }

    private static String editUrl( final Context context, final String name ) {
        return context.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), name );
    }

    private static String attr( final String s ) {
        return TextUtil.replaceEntities( s );
    }
}
