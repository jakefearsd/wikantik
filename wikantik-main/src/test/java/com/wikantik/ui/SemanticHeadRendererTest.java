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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SemanticHeadRenderer}. These assertions mirror the
 * structural expectations of {@code SemanticWebIT} so the IT failures are
 * reproducible without a running servlet container.
 */
class SemanticHeadRendererTest {

    private static final Gson GSON = new Gson();

    private static final String BASE_URL = "http://example.com";
    private static final String APP_NAME = "Wikantik";

    private static final String ARTICLE_BODY = """
            ---
            type: article
            tags:
            - testing
            - integration
            summary: A test article with full frontmatter for validating semantic web output
            date: 2026-03-20
            cluster: test-cluster
            related:
            - SemanticHub
            status: active
            ---
            # Semantic Article

            This is a test article.
            """;

    private static final String HUB_BODY = """
            ---
            type: hub
            tags:
            - testing
            summary: A hub page for the test cluster
            date: 2026-03-20
            cluster: test-cluster
            related:
            - SemanticArticle
            status: active
            ---
            # Semantic Hub

            Hub content.
            """;

    private static final String PLAIN_BODY = """
            # Plain Page

            This is a page with no YAML frontmatter.
            """;

    // ---- Canonical URL ----

    @Test
    void articleHasCanonicalUrl() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final String canonical = extractLinkHref( html, "canonical" );
        assertNotNull( canonical );
        assertTrue( canonical.startsWith( "http" ) );
        assertTrue( canonical.contains( "wiki/SemanticArticle" ),
                "canonical should contain wiki/SemanticArticle: " + canonical );
    }

    // ---- Meta description ----

    @Test
    void articleMetaDescriptionFromSummary() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final String desc = extractMetaContent( html, "name", "description" );
        assertNotNull( desc );
        assertTrue( desc.contains( "test article with full frontmatter" ),
                "description should come from frontmatter summary: " + desc );
    }

    @Test
    void plainPageMetaDescriptionFallback() {
        final String html = SemanticHeadRenderer.renderHead(
                "PlainPage", PLAIN_BODY, BASE_URL, APP_NAME );
        final String desc = extractMetaContent( html, "name", "description" );
        assertNotNull( desc );
        assertTrue( desc.contains( "PlainPage" ),
                "fallback description should include the page name: " + desc );
    }

    // ---- Meta keywords ----

    @Test
    void articleMetaKeywordsFromTags() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final String keywords = extractMetaContent( html, "name", "keywords" );
        assertNotNull( keywords );
        assertTrue( keywords.contains( "testing" ) );
        assertTrue( keywords.contains( "integration" ) );
    }

    @Test
    void plainPageHasNoMetaKeywords() {
        final String html = SemanticHeadRenderer.renderHead(
                "PlainPage", PLAIN_BODY, BASE_URL, APP_NAME );
        final String keywords = extractMetaContent( html, "name", "keywords" );
        assertTrue( keywords == null || keywords.isBlank(),
                "Plain page should have no keywords, got: " + keywords );
    }

    // ---- Open Graph ----

    @Test
    void articleOpenGraphTags() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );

        final String ogTitle = extractMetaContent( html, "property", "og:title" );
        assertNotNull( ogTitle );
        assertTrue( ogTitle.contains( "SemanticArticle" ) );

        assertEquals( "article", extractMetaContent( html, "property", "og:type" ) );

        final String ogDesc = extractMetaContent( html, "property", "og:description" );
        assertNotNull( ogDesc );
        assertTrue( ogDesc.contains( "test article with full frontmatter" ) );

        final String ogUrl = extractMetaContent( html, "property", "og:url" );
        assertNotNull( ogUrl );
        assertTrue( ogUrl.startsWith( "http" ) );
        assertTrue( ogUrl.contains( "wiki/SemanticArticle" ) );
    }

    @Test
    void articleHasPerTagOpenGraphTags() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final java.util.List< String > tags = extractAllMetaContent( html, "property", "article:tag" );
        assertFalse( tags.isEmpty() );
        assertTrue( tags.stream().anyMatch( t -> t.contains( "testing" ) ) );
        assertTrue( tags.stream().anyMatch( t -> t.contains( "integration" ) ) );
    }

    // ---- Twitter Card ----

    @Test
    void articleTwitterCardTags() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        assertEquals( "summary", extractMetaContent( html, "name", "twitter:card" ) );
        final String twitterDesc = extractMetaContent( html, "name", "twitter:description" );
        assertNotNull( twitterDesc );
        assertTrue( twitterDesc.contains( "test article with full frontmatter" ) );
    }

    // ---- JSON-LD: Article ----

    @Test
    void articleJsonLdStructuredData() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertEquals( "https://schema.org", ld.get( "@context" ).getAsString() );
        assertEquals( "Article", ld.get( "@type" ).getAsString() );
        assertEquals( "SemanticArticle", ld.get( "headline" ).getAsString() );

        final JsonObject mainEntity = ld.getAsJsonObject( "mainEntityOfPage" );
        assertNotNull( mainEntity );
        assertTrue( mainEntity.get( "@id" ).getAsString().startsWith( "http" ) );

        assertTrue( ld.has( "description" ) );
        assertTrue( ld.get( "description" ).getAsString().contains( "test article" ) );

        assertTrue( ld.has( "keywords" ) );
        assertTrue( ld.has( "datePublished" ) );
        assertEquals( "2026-03-20", ld.get( "datePublished" ).getAsString() );

        assertTrue( ld.has( "articleSection" ) );
        assertEquals( "test-cluster", ld.get( "articleSection" ).getAsString() );
    }

    @Test
    void articleJsonLdHasIsPartOf() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertTrue( ld.has( "isPartOf" ) );
        final JsonObject isPartOf = ld.getAsJsonObject( "isPartOf" );
        assertEquals( "CollectionPage", isPartOf.get( "@type" ).getAsString() );
        assertEquals( "test-cluster", isPartOf.get( "name" ).getAsString() );
    }

    @Test
    void articleJsonLdHasRelatedLinks() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertTrue( ld.has( "relatedLink" ) );
        final JsonArray links = ld.getAsJsonArray( "relatedLink" );
        assertTrue( links.size() > 0 );
        assertTrue( links.get( 0 ).getAsString().startsWith( "http" ) );
        assertTrue( links.get( 0 ).getAsString().contains( "SemanticHub" ) );
    }

    @Test
    void dateModifiedIsEmittedInJsonLd() throws Exception {
        final SimpleDateFormat fmt = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" );
        fmt.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
        final Date modified = fmt.parse( "2026-04-02T14:05:00Z" );
        final String head = SemanticHeadRenderer.renderHead(
                "LinuxSystemAdministration",
                "# Linux System Administration\n\nBody text.\n",
                BASE_URL,
                APP_NAME,
                modified );
        final JsonObject ld = extractFirstJsonLd( head );
        assertNotNull( ld );
        assertTrue( ld.has( "dateModified" ),
                "JSON-LD must contain dateModified; was: " + head );
        assertEquals( "2026-04-02T14:05:00Z", ld.get( "dateModified" ).getAsString() );
    }

    @Test
    void dateModifiedOmittedWhenNullFromFourArgOverload() {
        final String head = SemanticHeadRenderer.renderHead(
                "PlainPage", "# Plain Page\n\nBody.\n", BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( head );
        assertNotNull( ld );
        assertFalse( ld.has( "dateModified" ) );
    }

    // ---- JSON-LD: Hub ----

    @Test
    void hubJsonLdIsCollectionPage() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticHub", HUB_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertEquals( "CollectionPage", ld.get( "@type" ).getAsString() );
    }

    @Test
    void hubJsonLdHasHasPart() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticHub", HUB_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertTrue( ld.has( "hasPart" ) );
        final JsonArray parts = ld.getAsJsonArray( "hasPart" );
        assertTrue( parts.size() > 0 );

        boolean foundArticle = false;
        for ( final JsonElement part : parts ) {
            final JsonObject p = part.getAsJsonObject();
            if ( "SemanticArticle".equals( p.get( "name" ).getAsString() ) ) {
                assertEquals( "Article", p.get( "@type" ).getAsString() );
                assertTrue( p.get( "url" ).getAsString().startsWith( "http" ) );
                foundArticle = true;
            }
        }
        assertTrue( foundArticle );
    }

    @Test
    void hubJsonLdDoesNotHaveIsPartOf() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticHub", HUB_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertFalse( ld.has( "isPartOf" ) );
    }

    // ---- BreadcrumbList ----

    @Test
    void articleHasBreadcrumbList() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        final JsonObject breadcrumb = extractJsonLdByType( html, "BreadcrumbList" );
        assertNotNull( breadcrumb );
        final JsonArray items = breadcrumb.getAsJsonArray( "itemListElement" );
        assertNotNull( items );
        assertEquals( 3, items.size() );
        assertEquals( "Home", items.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertTrue( items.get( 0 ).getAsJsonObject().get( "item" ).getAsString().startsWith( "http" ) );
        assertEquals( "test-cluster", items.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "SemanticArticle", items.get( 2 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void hubDoesNotHaveBreadcrumbList() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticHub", HUB_BODY, BASE_URL, APP_NAME );
        assertNull( extractJsonLdByType( html, "BreadcrumbList" ) );
    }

    @Test
    void plainPageDoesNotHaveBreadcrumbList() {
        final String html = SemanticHeadRenderer.renderHead(
                "PlainPage", PLAIN_BODY, BASE_URL, APP_NAME );
        assertNull( extractJsonLdByType( html, "BreadcrumbList" ) );
    }

    // ---- Plain page JSON-LD ----

    @Test
    void plainPageJsonLdIsArticle() {
        final String html = SemanticHeadRenderer.renderHead(
                "PlainPage", PLAIN_BODY, BASE_URL, APP_NAME );
        final JsonObject ld = extractFirstJsonLd( html );
        assertNotNull( ld );
        assertEquals( "Article", ld.get( "@type" ).getAsString() );
        assertFalse( ld.has( "datePublished" ) );
        assertFalse( ld.has( "articleSection" ) );
    }

    // ---- Atom feed autodiscovery ----

    @Test
    void articlePageHasAtomFeedAutodiscovery() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        assertTrue( html.contains( "application/atom+xml" ) );
        assertTrue( html.contains( "feed.xml" ) );
    }

    @Test
    void clusteredArticleHasClusterFeedAutodiscovery() {
        final String html = SemanticHeadRenderer.renderHead(
                "SemanticArticle", ARTICLE_BODY, BASE_URL, APP_NAME );
        assertTrue( html.contains( "feed.xml?cluster=test-cluster" ),
                "Clustered article should have cluster-filtered feed link" );
    }

    // ---- Open graph present even on plain pages ----

    @Test
    void plainPageHasOpenGraphTags() {
        final String html = SemanticHeadRenderer.renderHead(
                "PlainPage", PLAIN_BODY, BASE_URL, APP_NAME );
        assertTrue( html.contains( "og:title" ) );
    }

    // ---- Body fragment ----

    @Test
    void bodyFragmentPromotesFirstHeadingToH1() {
        final String fragment = SemanticHeadRenderer.renderBodyFragment( "PlainPage", PLAIN_BODY );
        assertTrue( fragment.contains( "<h1>Plain Page</h1>" ),
                "Body fragment should promote '# Plain Page' to an h1: " + fragment );
    }

    @Test
    void bodyFragmentFallsBackToPageNameWhenNoHeading() {
        final String fragment = SemanticHeadRenderer.renderBodyFragment(
                "NoHeadingPage", "Just some body text with no heading." );
        assertTrue( fragment.contains( "<h1>NoHeadingPage</h1>" ) );
    }

    @Test
    void bodyFragmentStripsFrontmatter() {
        final String fragment = SemanticHeadRenderer.renderBodyFragment(
                "SemanticArticle", ARTICLE_BODY );
        assertFalse( fragment.contains( "testing" ) && fragment.contains( "integration" )
                && fragment.contains( "frontmatter" ) && fragment.contains( "type: article" ),
                "Body fragment must not echo the raw YAML frontmatter block" );
        assertTrue( fragment.contains( "<h1>Semantic Article</h1>" ),
                "Body fragment should promote the first markdown heading: " + fragment );
    }

    @Test
    void bodyFragmentEscapesHtml() {
        final String fragment = SemanticHeadRenderer.renderBodyFragment(
                "Evil", "# <script>alert(1)</script>" );
        assertFalse( fragment.contains( "<script>alert" ),
                "Body fragment must escape HTML: " + fragment );
        assertTrue( fragment.contains( "&lt;script&gt;" ) );
    }

    // ==== helpers (mirror SemanticWebIT helpers) ====

    private static String extractMetaContent( final String html, final String attr, final String value ) {
        final Pattern p = Pattern.compile(
                "<meta\\s+[^>]*?" + attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\""
                        + "[^>]*?content\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        final Matcher m = p.matcher( html );
        if ( m.find() ) {
            return m.group( 1 );
        }
        final Pattern p2 = Pattern.compile(
                "<meta\\s+[^>]*?content\\s*=\\s*\"([^\"]*)\"[^>]*?"
                        + attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\"", Pattern.CASE_INSENSITIVE );
        final Matcher m2 = p2.matcher( html );
        return m2.find() ? m2.group( 1 ) : null;
    }

    private static java.util.List< String > extractAllMetaContent( final String html, final String attr, final String value ) {
        final java.util.List< String > out = new java.util.ArrayList<>();
        final Pattern p = Pattern.compile(
                "<meta\\s+[^>]*?" + attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\""
                        + "[^>]*?content\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        final Matcher m = p.matcher( html );
        while ( m.find() ) {
            out.add( m.group( 1 ) );
        }
        return out;
    }

    private static String extractLinkHref( final String html, final String rel ) {
        final Pattern p = Pattern.compile(
                "<link\\s+[^>]*?rel\\s*=\\s*\"" + Pattern.quote( rel ) + "\""
                        + "[^>]*?href\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        final Matcher m = p.matcher( html );
        return m.find() ? m.group( 1 ) : null;
    }

    private static JsonObject extractFirstJsonLd( final String html ) {
        final Pattern p = Pattern.compile(
                "<script\\s+type\\s*=\\s*\"application/ld\\+json\"\\s*>\\s*(\\{.*?})\\s*</script>",
                Pattern.DOTALL );
        final Matcher m = p.matcher( html );
        while ( m.find() ) {
            try {
                final JsonObject obj = GSON.fromJson( m.group( 1 ), JsonObject.class );
                if ( obj.has( "@type" ) && !"BreadcrumbList".equals( obj.get( "@type" ).getAsString() ) ) {
                    return obj;
                }
            } catch ( final Exception ignored ) {
            }
        }
        return null;
    }

    private static JsonObject extractJsonLdByType( final String html, final String type ) {
        final Pattern p = Pattern.compile(
                "<script\\s+type\\s*=\\s*\"application/ld\\+json\"\\s*>\\s*(\\{.*?})\\s*</script>",
                Pattern.DOTALL );
        final Matcher m = p.matcher( html );
        while ( m.find() ) {
            try {
                final JsonObject obj = GSON.fromJson( m.group( 1 ), JsonObject.class );
                if ( obj.has( "@type" ) && type.equals( obj.get( "@type" ).getAsString() ) ) {
                    return obj;
                }
            } catch ( final Exception ignored ) {
            }
        }
        return null;
    }
}
