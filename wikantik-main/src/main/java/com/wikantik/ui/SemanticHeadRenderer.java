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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

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
        final ParsedPage parsed = FrontmatterParser.parse( rawPageText == null ? "" : rawPageText );
        final Map< String, Object > meta = parsed.metadata();

        final String safePageName = pageName == null ? "" : pageName;
        final String safeAppName = appName == null ? "" : appName;
        final String safeBaseUrl = stripTrailingSlash( baseUrl == null ? "" : baseUrl );
        final String canonical = safeBaseUrl + "/wiki/" + safePageName;

        final String summary = strOrEmpty( meta.get( "summary" ) );
        final String description = strOrEmpty( meta.get( "description" ) );
        final String pageType = strOrEmpty( meta.get( "type" ) );
        final String cluster = strOrEmpty( meta.get( "cluster" ) );
        final String pageDate = dateOrString( meta.get( "date" ) );
        final List< String > tags = stringList( meta.get( "tags" ) );
        final List< String > related = stringList( meta.get( "related" ) );
        final boolean isHub = "hub".equalsIgnoreCase( pageType );

        // effective description: summary > description > generic fallback
        final String effectiveDescription;
        if ( !summary.isBlank() ) {
            effectiveDescription = summary;
        } else if ( !description.isBlank() ) {
            effectiveDescription = description;
        } else {
            effectiveDescription = safePageName + " - " + safeAppName + " wiki page.";
        }
        final String effectiveKeywords = String.join( ", ", tags );

        final StringBuilder sb = new StringBuilder( 2048 );

        sb.append( "<link rel=\"canonical\" href=\"" ).append( escAttr( canonical ) ).append( "\" />" ).append( NL );

        sb.append( "<meta name=\"description\" content=\"" )
          .append( escAttr( effectiveDescription ) ).append( "\" />" ).append( NL );

        if ( !effectiveKeywords.isBlank() ) {
            sb.append( "<meta name=\"keywords\" content=\"" )
              .append( escAttr( effectiveKeywords ) ).append( "\" />" ).append( NL );
        }

        // Open Graph
        sb.append( "<meta property=\"og:title\" content=\"" )
          .append( escAttr( safePageName + " - " + safeAppName ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:type\" content=\"article\" />" ).append( NL );
        sb.append( "<meta property=\"og:url\" content=\"" ).append( escAttr( canonical ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:description\" content=\"" )
          .append( escAttr( effectiveDescription ) ).append( "\" />" ).append( NL );
        sb.append( "<meta property=\"og:site_name\" content=\"" )
          .append( escAttr( safeAppName ) ).append( "\" />" ).append( NL );

        // article:tag per tag
        for ( final String tag : tags ) {
            sb.append( "<meta property=\"article:tag\" content=\"" )
              .append( escAttr( tag ) ).append( "\" />" ).append( NL );
        }

        // Twitter Card
        sb.append( "<meta name=\"twitter:card\" content=\"summary\" />" ).append( NL );
        sb.append( "<meta name=\"twitter:title\" content=\"" )
          .append( escAttr( safePageName + " - " + safeAppName ) ).append( "\" />" ).append( NL );
        sb.append( "<meta name=\"twitter:description\" content=\"" )
          .append( escAttr( effectiveDescription ) ).append( "\" />" ).append( NL );

        // Atom feed autodiscovery — global and (optionally) cluster-filtered
        sb.append( "<link rel=\"alternate\" type=\"application/atom+xml\" title=\"" )
          .append( escAttr( safeAppName + " - Recent Articles" ) ).append( "\" href=\"" )
          .append( escAttr( safeBaseUrl + "/feed.xml" ) ).append( "\" />" ).append( NL );
        if ( !cluster.isBlank() ) {
            sb.append( "<link rel=\"alternate\" type=\"application/atom+xml\" title=\"" )
              .append( escAttr( safeAppName + " - " + cluster + " Articles" ) ).append( "\" href=\"" )
              .append( escAttr( safeBaseUrl + "/feed.xml?cluster=" + cluster ) ).append( "\" />" ).append( NL );
        }

        // Main JSON-LD
        sb.append( "<script type=\"application/ld+json\">" ).append( NL );
        sb.append( buildMainJsonLd( safePageName, safeAppName, canonical, safeBaseUrl,
                effectiveDescription, effectiveKeywords, pageDate, modified, cluster, isHub, related ) );
        sb.append( NL ).append( "</script>" ).append( NL );

        // BreadcrumbList for clustered non-hub pages
        if ( !cluster.isBlank() && !isHub ) {
            sb.append( "<script type=\"application/ld+json\">" ).append( NL );
            sb.append( buildBreadcrumbJsonLd( safePageName, safeBaseUrl, canonical, cluster ) );
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

    // -------- JSON-LD builders --------

    private static String buildMainJsonLd( final String pageName, final String appName,
                                            final String canonical, final String baseUrl,
                                            final String description, final String keywords,
                                            final String datePublished, final Date modified,
                                            final String cluster, final boolean isHub,
                                            final List< String > related ) {
        final StringBuilder sb = new StringBuilder( 512 );
        sb.append( '{' );
        sb.append( "\"@context\":\"https://schema.org\"," );
        if ( isHub ) {
            sb.append( "\"@type\":\"CollectionPage\"," );
            sb.append( "\"name\":" ).append( jsonStr( pageName ) ).append( "," );
            if ( !related.isEmpty() ) {
                sb.append( "\"hasPart\":[" );
                for ( int i = 0; i < related.size(); i++ ) {
                    final String rel = related.get( i );
                    if ( i > 0 ) {
                        sb.append( ',' );
                    }
                    sb.append( '{' );
                    sb.append( "\"@type\":\"Article\"," );
                    sb.append( "\"name\":" ).append( jsonStr( rel ) ).append( "," );
                    sb.append( "\"url\":" ).append( jsonStr( baseUrl + "/wiki/" + rel ) );
                    sb.append( '}' );
                }
                sb.append( "]," );
            }
        } else {
            sb.append( "\"@type\":\"Article\"," );
        }
        sb.append( "\"headline\":" ).append( jsonStr( pageName ) ).append( "," );
        if ( !description.isBlank() ) {
            sb.append( "\"description\":" ).append( jsonStr( description ) ).append( "," );
        }
        if ( !keywords.isBlank() ) {
            sb.append( "\"keywords\":" ).append( jsonStr( keywords ) ).append( "," );
        }
        if ( !datePublished.isBlank() ) {
            sb.append( "\"datePublished\":" ).append( jsonStr( datePublished ) ).append( "," );
        }
        if ( modified != null ) {
            final SimpleDateFormat modFmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
            modFmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
            sb.append( "\"dateModified\":" ).append( jsonStr( modFmt.format( modified ) ) ).append( "," );
        }
        if ( !cluster.isBlank() ) {
            sb.append( "\"articleSection\":" ).append( jsonStr( cluster ) ).append( "," );
        }
        sb.append( "\"publisher\":{\"@type\":\"Organization\",\"name\":" )
          .append( jsonStr( appName ) ).append( "}," );
        sb.append( "\"mainEntityOfPage\":{\"@type\":\"WebPage\",\"@id\":" )
          .append( jsonStr( canonical ) ).append( "}" );
        if ( !isHub && !cluster.isBlank() ) {
            sb.append( ",\"isPartOf\":{\"@type\":\"CollectionPage\",\"name\":" )
              .append( jsonStr( cluster ) ).append( "}" );
        }
        if ( !isHub && !related.isEmpty() ) {
            sb.append( ",\"relatedLink\":[" );
            for ( int i = 0; i < related.size(); i++ ) {
                if ( i > 0 ) {
                    sb.append( ',' );
                }
                sb.append( jsonStr( baseUrl + "/wiki/" + related.get( i ) ) );
            }
            sb.append( ']' );
        }
        sb.append( '}' );
        return sb.toString();
    }

    private static String buildBreadcrumbJsonLd( final String pageName, final String baseUrl,
                                                   final String canonical, final String cluster ) {
        final StringBuilder sb = new StringBuilder( 256 );
        sb.append( '{' );
        sb.append( "\"@context\":\"https://schema.org\"," );
        sb.append( "\"@type\":\"BreadcrumbList\"," );
        sb.append( "\"itemListElement\":[" );
        sb.append( "{\"@type\":\"ListItem\",\"position\":1,\"name\":\"Home\",\"item\":" )
          .append( jsonStr( baseUrl + "/" ) ).append( "}," );
        sb.append( "{\"@type\":\"ListItem\",\"position\":2,\"name\":" )
          .append( jsonStr( cluster ) ).append( ",\"item\":" )
          .append( jsonStr( baseUrl + "/wiki/" + cluster ) ).append( "}," );
        sb.append( "{\"@type\":\"ListItem\",\"position\":3,\"name\":" )
          .append( jsonStr( pageName ) ).append( ",\"item\":" )
          .append( jsonStr( canonical ) ).append( "}" );
        sb.append( "]}" );
        return sb.toString();
    }

    // -------- type coercion helpers --------

    private static String strOrEmpty( final Object value ) {
        if ( value == null ) {
            return "";
        }
        if ( value instanceof Date date ) {
            return new SimpleDateFormat( "yyyy-MM-dd" ).format( date );
        }
        return value.toString();
    }

    private static String dateOrString( final Object value ) {
        if ( value == null ) {
            return "";
        }
        if ( value instanceof Date date ) {
            return new SimpleDateFormat( "yyyy-MM-dd" ).format( date );
        }
        return value.toString();
    }

    private static List< String > stringList( final Object value ) {
        if ( value == null ) {
            return List.of();
        }
        if ( value instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object e : list ) {
                if ( e != null ) {
                    out.add( e.toString().trim() );
                }
            }
            return out;
        }
        // Comma-separated scalar (e.g. "a, b, c")
        final String csv = value.toString();
        if ( csv.isBlank() ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>();
        for ( final String part : csv.split( "," ) ) {
            final String trimmed = part.trim();
            if ( !trimmed.isEmpty() ) {
                out.add( trimmed );
            }
        }
        return out;
    }

    private static String stripTrailingSlash( final String s ) {
        if ( s == null || s.isEmpty() ) {
            return s;
        }
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }

    // -------- escaping --------

    /** Minimal HTML attribute-value escaping: {@code & < > " '}. */
    private static String escAttr( final String s ) {
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

    /** JSON string literal with quotes. Escapes backslash, quote, and control chars. */
    private static String jsonStr( final String s ) {
        if ( s == null ) {
            return "\"\"";
        }
        final StringBuilder out = new StringBuilder( s.length() + 16 );
        out.append( '"' );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch ( c ) {
                case '\\' -> out.append( "\\\\" );
                case '"' -> out.append( "\\\"" );
                case '\n' -> out.append( "\\n" );
                case '\r' -> out.append( "\\r" );
                case '\t' -> out.append( "\\t" );
                case '/' -> out.append( "\\/" );
                default -> {
                    if ( c < 0x20 ) {
                        out.append( String.format( "\\u%04x", ( int ) c ) );
                    } else {
                        out.append( c );
                    }
                }
            }
        }
        out.append( '"' );
        return out.toString();
    }
}
