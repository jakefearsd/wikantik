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

import com.wikantik.ontology.Iris;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds the JSON-LD structured-data blocks emitted by
 * {@link SemanticHeadRenderer#renderHead}: the main Article/CollectionPage
 * document, the BreadcrumbList for clustered non-hub pages, and the
 * homepage-only WebSite + SearchAction block. Split out of
 * {@code SemanticHeadRenderer} — the field-emission order and string content
 * are copied verbatim, so output is byte-identical to before the split.
 */
final class JsonLdEmitter {

    private JsonLdEmitter() {
    }

    /**
     * Build the main Article (or CollectionPage, for hubs) JSON-LD document
     * for {@code model}.
     *
     * @param model the derived page SEO model
     * @return the JSON-LD object, without surrounding {@code <script>} tags
     */
    static String buildMainJsonLd( final PageSeoModel model ) {
        final StringBuilder sb = new StringBuilder( 512 );
        sb.append( '{' );
        sb.append( "\"@context\":\"https://schema.org\"," );
        appendTypeAndHubParts( sb, model );
        appendHeadlineAndOptionalFields( sb, model );
        appendPublisherAndMainEntity( sb, model );
        appendRelationalFields( sb, model );
        sb.append( '}' );
        return sb.toString();
    }

    private static void appendTypeAndHubParts( final StringBuilder sb, final PageSeoModel model ) {
        if ( !model.isHub() ) {
            sb.append( "\"@type\":" ).append( jsonStr( model.schemaType() ) ).append(',');
            return;
        }
        sb.append( "\"@type\":" ).append( jsonStr( model.schemaType() ) ).append(',');
        sb.append( "\"name\":" ).append( jsonStr( model.safePageName() ) ).append(',');
        final List< String > related = model.related();
        if ( !related.isEmpty() ) {
            sb.append( "\"hasPart\":[" );
            appendHasPartItems( sb, related, model.safeBaseUrl() );
            sb.append( "]," );
        }
    }

    private static void appendHasPartItems( final StringBuilder sb, final List< String > related, final String baseUrl ) {
        for ( int i = 0; i < related.size(); i++ ) {
            final String rel = related.get( i );
            if ( i > 0 ) {
                sb.append( ',' );
            }
            sb.append( '{' );
            sb.append( "\"@type\":\"Article\"," );
            sb.append( "\"name\":" ).append( jsonStr( rel ) ).append(',');
            sb.append( "\"url\":" ).append( jsonStr( baseUrl + "/wiki/" + rel ) );
            sb.append( '}' );
        }
    }

    private static void appendHeadlineAndOptionalFields( final StringBuilder sb, final PageSeoModel model ) {
        sb.append( "\"headline\":" ).append( jsonStr( model.safePageName() ) ).append(',');
        if ( !model.effectiveDescription().isBlank() ) {
            sb.append( "\"description\":" ).append( jsonStr( model.effectiveDescription() ) ).append(',');
        }
        if ( !model.effectiveKeywords().isBlank() ) {
            sb.append( "\"keywords\":" ).append( jsonStr( model.effectiveKeywords() ) ).append(',');
        }
        if ( !model.pageDate().isBlank() ) {
            sb.append( "\"datePublished\":" ).append( jsonStr( model.pageDate() ) ).append(',');
        }
        final Date modified = model.modified();
        if ( modified != null ) {
            final SimpleDateFormat modFmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT );
            modFmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
            sb.append( "\"dateModified\":" ).append( jsonStr( modFmt.format( modified ) ) ).append(',');
        }
        if ( !model.cluster().isBlank() ) {
            sb.append( "\"articleSection\":" ).append( jsonStr( model.cluster() ) ).append(',');
        }
    }

    private static void appendPublisherAndMainEntity( final StringBuilder sb, final PageSeoModel model ) {
        final String baseUrl = model.safeBaseUrl();
        sb.append( "\"publisher\":{\"@type\":\"Organization\",\"name\":" )
          .append( jsonStr( model.safeAppName() ) ).append( ",\"url\":" ).append( jsonStr( baseUrl + "/" ) )
          .append( ",\"logo\":{\"@type\":\"ImageObject\",\"url\":" )
          .append( jsonStr( baseUrl + "/og-default.png" ) ).append( "}}," );
        sb.append( "\"mainEntityOfPage\":{\"@type\":\"WebPage\",\"@id\":" )
          .append( jsonStr( model.canonical() ) ).append('}');
    }

    private static void appendRelationalFields( final StringBuilder sb, final PageSeoModel model ) {
        if ( !model.isHub() && !model.cluster().isBlank() ) {
            sb.append( ",\"isPartOf\":{\"@type\":\"CollectionPage\",\"name\":" )
              .append( jsonStr( model.cluster() ) ).append('}');
        }
        if ( !model.isHub() && !model.related().isEmpty() ) {
            appendRelatedLinks( sb, model.related(), model.safeBaseUrl() );
        }
        // Additive: link the schema.org entity to its dereferenceable RDF resource (Phase 6).
        final String canonicalId = model.canonicalId();
        if ( canonicalId != null && !canonicalId.isBlank() ) {
            sb.append( ",\"sameAs\":" ).append( jsonStr( Iris.page( canonicalId ) ) );
        }
    }

    private static void appendRelatedLinks( final StringBuilder sb, final List< String > related, final String baseUrl ) {
        sb.append( ",\"relatedLink\":[" );
        for ( int i = 0; i < related.size(); i++ ) {
            if ( i > 0 ) {
                sb.append( ',' );
            }
            sb.append( jsonStr( baseUrl + "/wiki/" + related.get( i ) ) );
        }
        sb.append( ']' );
    }

    /**
     * Build the BreadcrumbList JSON-LD document for a clustered, non-hub page.
     *
     * @param pageName  the wiki page name
     * @param baseUrl   the fully-qualified base URL (no trailing slash)
     * @param canonical the page's canonical URL
     * @param cluster   the page's cluster name
     * @return the JSON-LD object, without surrounding {@code <script>} tags
     */
    static String buildBreadcrumbJsonLd( final String pageName, final String baseUrl,
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
          .append( jsonStr( canonical ) ).append('}');
        sb.append( "]}" );
        return sb.toString();
    }

    /**
     * Build the homepage-only WebSite + SearchAction JSON-LD document.
     *
     * @param baseUrl the fully-qualified base URL (no trailing slash)
     * @return the JSON-LD object, without surrounding {@code <script>} tags
     */
    static String buildWebSiteJsonLd( final String baseUrl ) {
        final StringBuilder sb = new StringBuilder( 256 );
        sb.append( '{' );
        sb.append( "\"@context\":\"https://schema.org\"," );
        sb.append( "\"@type\":\"WebSite\"," );
        sb.append( "\"url\":" ).append( jsonStr( baseUrl + "/" ) ).append( ',' );
        sb.append( "\"potentialAction\":{" );
        sb.append( "\"@type\":\"SearchAction\"," );
        sb.append( "\"target\":{\"@type\":\"EntryPoint\",\"urlTemplate\":" )
          .append( jsonStr( baseUrl + "/search?q={search_term_string}" ) ).append( "}," );
        sb.append( "\"query-input\":\"required name=search_term_string\"" );
        sb.append( "}}" );
        return sb.toString();
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
