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
package com.wikantik.ui;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

import java.util.Date;

/**
 * Builds the semantic-web {@code <head>} fragment for a wiki page: meta
 * description/keywords, Open Graph and Twitter Card meta tags, JSON-LD
 * structured data (Article or CollectionPage), BreadcrumbList for clustered
 * non-hub pages, canonical link, and Atom feed autodiscovery.
 *
 * <p>This is the React-era replacement for the head block that the old haddock
 * {@code commonheader.jsp} generated via JSTL. The renderer is a pure function
 * of {@code (pageName, rawPageText, baseUrl, appName)} so it can be unit-tested
 * in isolation without a servlet container.
 *
 * <p>Crawlers typically do not execute JavaScript, so this head is injected
 * server-side by {@code SpaRoutingFilter} into the SPA's {@code index.html}
 * before it is served for {@code /wiki/*} routes.
 */
public final class SemanticHeadRenderer {

    private static final String NL = "\n";

    private SemanticHeadRenderer() {
    }

    /**
     * Render the full semantic {@code <head>} fragment for a page. Convenience
     * overload that emits no {@code dateModified} in the JSON-LD — kept for
     * callers and tests that do not have a {@link Page#getLastModified()}
     * available.
     *
     * @param pageName    the wiki page name (used as the heading and URL slug)
     * @param rawPageText the raw page text, possibly with a YAML frontmatter block
     * @param baseUrl     the fully-qualified base URL (no trailing slash), e.g. {@code http://host:port/ctx}
     * @param appName     the wiki application name (used as the OG site_name / publisher)
     * @return an HTML fragment containing only {@code <meta>}, {@code <link>}, and
     *         {@code <script type="application/ld+json">} tags — no wrapping element
     */
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName ) {
        return renderHead( pageName, rawPageText, baseUrl, appName, null );
    }

    /**
     * Render the full semantic {@code <head>} fragment for a page.
     *
     * @param pageName    the wiki page name (used as the heading and URL slug)
     * @param rawPageText the raw page text, possibly with a YAML frontmatter block
     * @param baseUrl     the fully-qualified base URL (no trailing slash), e.g. {@code http://host:port/ctx}
     * @param appName     the wiki application name (used as the OG site_name / publisher)
     * @param modified    the page's last-modified timestamp, emitted as
     *                    {@code dateModified} in the Article JSON-LD; {@code null} to omit
     * @return an HTML fragment containing only {@code <meta>}, {@code <link>}, and
     *         {@code <script type="application/ld+json">} tags — no wrapping element
     */
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName,
                                      final Date modified ) {
        final PageSeoModel model = PageSeoModel.from( pageName, rawPageText, baseUrl, appName, modified );

        final StringBuilder sb = new StringBuilder( 2048 );

        HeadTagWriter.write( sb, model );

        // Main JSON-LD
        sb.append( "<script type=\"application/ld+json\">" ).append( NL );
        sb.append( JsonLdEmitter.buildMainJsonLd( model ) );
        sb.append( NL ).append( "</script>" ).append( NL );

        // BreadcrumbList for clustered non-hub pages
        if ( !model.cluster().isBlank() && !model.isHub() ) {
            sb.append( "<script type=\"application/ld+json\">" ).append( NL );
            sb.append( JsonLdEmitter.buildBreadcrumbJsonLd( model.safePageName(), model.safeBaseUrl(), model.canonical(), model.cluster() ) );
            sb.append( NL ).append( "</script>" ).append( NL );
        }

        // WebSite + SearchAction — homepage only
        if ( "Main".equals( model.safePageName() ) ) {
            sb.append( "<script type=\"application/ld+json\">" ).append( NL );
            sb.append( JsonLdEmitter.buildWebSiteJsonLd( model.safeBaseUrl() ) );
            sb.append( NL ).append( "</script>" ).append( NL );
        }

        return sb.toString();
    }

    /**
     * Render a minimal no-JS fallback body fragment so crawlers that do not
     * execute JavaScript still see the page title and content. The React SPA
     * replaces this when it mounts on {@code <div id="root">}.
     *
     * <p>The fragment intentionally does <em>not</em> try to be a full markdown
     * renderer — it extracts the first {@code # heading} as an {@code <h1>} and
     * escapes the remaining body lines as paragraphs. That is enough to
     * satisfy crawler indexing and the SemanticWebIT "plain page renders" test
     * without pulling a full flexmark pipeline into the request path.
     *
     * @param pageName    the wiki page name (used as heading fallback)
     * @param rawPageText the raw page text, possibly with a YAML frontmatter block
     * @return an HTML fragment containing an {@code <article>} with a heading and text
     */
    public static String renderBodyFragment( final String pageName, final String rawPageText ) {
        final ParsedPage parsed = FrontmatterParser.parse( rawPageText == null ? "" : rawPageText );
        final String body = parsed.body() == null ? "" : parsed.body();
        final String safePageName = pageName == null ? "" : pageName;

        String heading = safePageName;
        final StringBuilder content = new StringBuilder( 256 );
        boolean headingFound = false;
        for ( final String rawLine : body.split( "\r?\n" ) ) {
            final String line = rawLine.trim();
            if ( line.isEmpty() ) {
                continue;
            }
            if ( !headingFound && line.startsWith( "# " ) ) {
                heading = line.substring( 2 ).trim();
                headingFound = true;
                continue;
            }
            // Skip subsequent markdown headings (e.g. "## Section") — they
            // become heading-style paragraphs without the # prefix so crawlers
            // still index the text.
            final String textLine = line.replaceAll( "^#+\\s*", "" );
            content.append( "<p>" ).append( escAttr( textLine ) ).append( "</p>" ).append( NL );
        }

        final StringBuilder out = new StringBuilder( 512 );
        out.append( "<article class=\"ssr-fallback\">" ).append( NL );
        out.append( "<h1>" ).append( escAttr( heading ) ).append( "</h1>" ).append( NL );
        out.append( content );
        out.append( "</article>" ).append( NL );
        return out.toString();
    }

    // -------- escaping --------

    /**
     * Minimal HTML attribute-value escaping: {@code & < > " '}. Package-private —
     * shared between this class's {@link #renderBodyFragment} and
     * {@link HeadTagWriter}'s head-tag emission.
     */
    static String escAttr( final String s ) {
        if ( s == null || s.isEmpty() ) {
            return "";
        }
        final StringBuilder out = new StringBuilder( s.length() + 16 );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch ( c ) {
                case '&' -> out.append( "&amp;" );
                case '<' -> out.append( "&lt;" );
                case '>' -> out.append( "&gt;" );
                case '"' -> out.append( "&quot;" );
                case '\'' -> out.append( "&#39;" );
                default -> out.append( c );
            }
        }
        return out.toString();
    }
}
