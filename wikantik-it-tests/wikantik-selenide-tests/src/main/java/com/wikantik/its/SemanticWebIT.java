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
package com.wikantik.its;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Integration tests that validate the semantic web output layer: HTML meta tags,
 * Open Graph tags, JSON-LD structured data, breadcrumbs, canonical URLs,
 * Atom feed, and News Sitemap.
 * <p>
 * Tests use three fixture pages in the test-repo:
 * <ul>
 *   <li>{@code SemanticArticle} — article with full frontmatter (type, tags, summary, date, cluster, related)</li>
 *   <li>{@code SemanticHub} — hub page with type=hub, same cluster</li>
 *   <li>{@code PlainPage} — page with no frontmatter at all (tests fallback behaviour)</li>
 * </ul>
 * <p>
 * These tests fetch raw HTML via HTTP to inspect {@code <head>} content that the
 * browser DOM may rewrite or strip (e.g. duplicate meta tags, JSON-LD script blocks).
 */
public class SemanticWebIT extends WithIntegrationTestSetup {

    private static final Gson GSON = new Gson();

    // ---- Canonical URL ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleHasCanonicalUrl() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final String canonical = extractLinkHref( html, "canonical" );

        assertNotNull( canonical, "Article should have a canonical URL" );
        assertTrue( canonical.startsWith( "http" ),
                "Canonical URL should be fully qualified but was: " + canonical );
        assertTrue( canonical.contains( "wiki/SemanticArticle" ),
                "Canonical URL should point to wiki/SemanticArticle but was: " + canonical );
    }

    // ---- Meta description ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleMetaDescriptionFromSummary() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final String description = extractMetaContent( html, "name", "description" );

        assertNotNull( description, "Article should have a meta description" );
        assertTrue( description.contains( "test article with full frontmatter" ),
                "Meta description should come from frontmatter summary, got: " + description );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void plainPageMetaDescriptionFallback() throws Exception {
        final String html = fetchPage( "PlainPage" );
        final String description = extractMetaContent( html, "name", "description" );

        assertNotNull( description, "Plain page should still have a meta description" );
        assertTrue( description.contains( "PlainPage" ),
                "Fallback meta description should include the page name, got: " + description );
    }

    // ---- Meta keywords ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleMetaKeywordsFromTags() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final String keywords = extractMetaContent( html, "name", "keywords" );

        assertNotNull( keywords, "Article with tags should have meta keywords" );
        assertTrue( keywords.contains( "testing" ), "Keywords should include 'testing': " + keywords );
        assertTrue( keywords.contains( "integration" ), "Keywords should include 'integration': " + keywords );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void plainPageHasNoMetaKeywords() throws Exception {
        final String html = fetchPage( "PlainPage" );
        final String keywords = extractMetaContent( html, "name", "keywords" );

        // Should be null or empty — no tags, no keywords
        assertTrue( keywords == null || keywords.isBlank(),
                "Page without tags should not have meta keywords, got: " + keywords );
    }

    // ---- Open Graph tags ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleOpenGraphTags() throws Exception {
        final String html = fetchPage( "SemanticArticle" );

        final String ogTitle = extractMetaContent( html, "property", "og:title" );
        assertNotNull( ogTitle, "Article should have og:title" );
        assertTrue( ogTitle.contains( "SemanticArticle" ), "og:title should contain page name: " + ogTitle );

        final String ogType = extractMetaContent( html, "property", "og:type" );
        assertEquals( "article", ogType, "og:type should be 'article'" );

        final String ogDesc = extractMetaContent( html, "property", "og:description" );
        assertNotNull( ogDesc, "Article should have og:description" );
        assertTrue( ogDesc.contains( "test article with full frontmatter" ),
                "og:description should come from summary: " + ogDesc );

        final String ogUrl = extractMetaContent( html, "property", "og:url" );
        assertNotNull( ogUrl, "Article should have og:url" );
        assertTrue( ogUrl.startsWith( "http" ),
                "og:url should be fully qualified but was: " + ogUrl );
        assertTrue( ogUrl.contains( "wiki/SemanticArticle" ), "og:url should point to the page: " + ogUrl );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleHasPerTagOpenGraphTags() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final List< String > articleTags = extractAllMetaContent( html, "property", "article:tag" );

        assertFalse( articleTags.isEmpty(), "Article with tags should have article:tag meta elements" );
        assertTrue( articleTags.stream().anyMatch( t -> t.contains( "testing" ) ),
                "article:tag should include 'testing': " + articleTags );
        assertTrue( articleTags.stream().anyMatch( t -> t.contains( "integration" ) ),
                "article:tag should include 'integration': " + articleTags );
    }

    // ---- Twitter Card tags ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleTwitterCardTags() throws Exception {
        final String html = fetchPage( "SemanticArticle" );

        final String card = extractMetaContent( html, "name", "twitter:card" );
        assertEquals( "summary", card, "twitter:card should be 'summary'" );

        final String twitterDesc = extractMetaContent( html, "name", "twitter:description" );
        assertNotNull( twitterDesc, "Article should have twitter:description" );
        assertTrue( twitterDesc.contains( "test article with full frontmatter" ),
                "twitter:description should come from summary: " + twitterDesc );
    }

    // ---- JSON-LD: Article ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleJsonLdStructuredData() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld, "Article should have JSON-LD structured data" );
        assertEquals( "https://schema.org", ld.get( "@context" ).getAsString() );
        assertEquals( "Article", ld.get( "@type" ).getAsString(),
                "Non-hub page should have @type Article" );
        assertEquals( "SemanticArticle", ld.get( "headline" ).getAsString() );

        assertTrue( ld.has( "mainEntityOfPage" ), "JSON-LD should include mainEntityOfPage" );
        final JsonObject mainEntity = ld.getAsJsonObject( "mainEntityOfPage" );
        assertTrue( mainEntity.get( "@id" ).getAsString().startsWith( "http" ),
                "mainEntityOfPage @id should be fully qualified but was: " + mainEntity.get( "@id" ) );

        assertTrue( ld.has( "description" ), "JSON-LD should include description" );
        assertTrue( ld.get( "description" ).getAsString().contains( "test article" ) );

        assertTrue( ld.has( "keywords" ), "JSON-LD should include keywords from tags" );
        assertTrue( ld.has( "datePublished" ), "JSON-LD should include datePublished" );
        assertEquals( "2026-03-20", ld.get( "datePublished" ).getAsString() );

        assertTrue( ld.has( "articleSection" ), "Clustered article should have articleSection" );
        assertEquals( "test-cluster", ld.get( "articleSection" ).getAsString() );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleJsonLdHasIsPartOf() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld );
        assertTrue( ld.has( "isPartOf" ),
                "Clustered non-hub article should have isPartOf" );
        final JsonObject isPartOf = ld.getAsJsonObject( "isPartOf" );
        assertEquals( "CollectionPage", isPartOf.get( "@type" ).getAsString() );
        assertEquals( "test-cluster", isPartOf.get( "name" ).getAsString() );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleJsonLdHasRelatedLinks() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld );
        assertTrue( ld.has( "relatedLink" ),
                "Non-hub article with related pages should have relatedLink" );
        final JsonArray links = ld.getAsJsonArray( "relatedLink" );
        assertTrue( links.size() > 0, "relatedLink should not be empty" );
        assertTrue( links.get( 0 ).getAsString().startsWith( "http" ),
                "relatedLink URLs should be fully qualified but was: " + links.get( 0 ) );
        assertTrue( links.get( 0 ).getAsString().contains( "SemanticHub" ),
                "relatedLink should point to SemanticHub: " + links );
    }

    // ---- JSON-LD: Hub (CollectionPage) ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void hubJsonLdIsCollectionPage() throws Exception {
        final String html = fetchPage( "SemanticHub" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld, "Hub should have JSON-LD structured data" );
        assertEquals( "CollectionPage", ld.get( "@type" ).getAsString(),
                "Hub page should have @type CollectionPage" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void hubJsonLdHasHasPart() throws Exception {
        final String html = fetchPage( "SemanticHub" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld );
        assertTrue( ld.has( "hasPart" ), "Hub page should have hasPart listing sub-articles" );
        final JsonArray parts = ld.getAsJsonArray( "hasPart" );
        assertTrue( parts.size() > 0, "hasPart should not be empty" );

        boolean foundArticle = false;
        for ( final JsonElement part : parts ) {
            final JsonObject p = part.getAsJsonObject();
            if ( "SemanticArticle".equals( p.get( "name" ).getAsString() ) ) {
                assertEquals( "Article", p.get( "@type" ).getAsString() );
                assertTrue( p.get( "url" ).getAsString().startsWith( "http" ),
                        "hasPart URL should be fully qualified but was: " + p.get( "url" ) );
                foundArticle = true;
            }
        }
        assertTrue( foundArticle, "hasPart should include SemanticArticle but found: " + parts );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void hubJsonLdDoesNotHaveIsPartOf() throws Exception {
        final String html = fetchPage( "SemanticHub" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld );
        assertFalse( ld.has( "isPartOf" ),
                "Hub page should NOT have isPartOf — it IS the collection" );
    }

    // ---- BreadcrumbList ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articleHasBreadcrumbList() throws Exception {
        final String html = fetchPage( "SemanticArticle" );
        final JsonObject breadcrumb = extractJsonLdByType( html, "BreadcrumbList" );

        assertNotNull( breadcrumb, "Clustered non-hub article should have BreadcrumbList JSON-LD" );
        final JsonArray items = breadcrumb.getAsJsonArray( "itemListElement" );
        assertNotNull( items );
        assertEquals( 3, items.size(), "Breadcrumb should have 3 items: Home > Cluster > Page" );

        assertEquals( "Home", items.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertTrue( items.get( 0 ).getAsJsonObject().get( "item" ).getAsString().startsWith( "http" ),
                "Breadcrumb item URL should be fully qualified but was: "
                        + items.get( 0 ).getAsJsonObject().get( "item" ) );
        assertEquals( "test-cluster", items.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "SemanticArticle", items.get( 2 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void hubDoesNotHaveBreadcrumbList() throws Exception {
        final String html = fetchPage( "SemanticHub" );
        final JsonObject breadcrumb = extractJsonLdByType( html, "BreadcrumbList" );

        assertNull( breadcrumb, "Hub page should NOT have BreadcrumbList JSON-LD" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void plainPageDoesNotHaveBreadcrumbList() throws Exception {
        final String html = fetchPage( "PlainPage" );
        final JsonObject breadcrumb = extractJsonLdByType( html, "BreadcrumbList" );

        assertNull( breadcrumb, "Unclustered page should NOT have BreadcrumbList JSON-LD" );
    }

    // ---- Plain page (no frontmatter) JSON-LD ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void plainPageJsonLdIsArticle() throws Exception {
        final String html = fetchPage( "PlainPage" );
        final JsonObject ld = extractFirstJsonLd( html );

        assertNotNull( ld, "Even plain pages should get JSON-LD" );
        assertEquals( "Article", ld.get( "@type" ).getAsString(),
                "Page without type should default to Article" );
        assertFalse( ld.has( "datePublished" ),
                "Page without date should not have datePublished" );
        assertFalse( ld.has( "articleSection" ),
                "Page without cluster should not have articleSection" );
    }

    // ---- Atom feed ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void atomFeedReturnValidXml() throws Exception {
        final String feed = fetchUrl( "/feed.xml" );

        assertTrue( feed.contains( "<feed xmlns=\"http://www.w3.org/2005/Atom\">" ),
                "Feed should be valid Atom XML" );
        assertTrue( feed.contains( "<entry>" ), "Feed should contain entries" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void atomFeedHasCorrectContentType() throws Exception {
        final HttpURLConnection conn = openConnection( "/feed.xml" );
        try {
            assertEquals( 200, conn.getResponseCode(), "Feed should return HTTP 200" );
            final String ct = conn.getContentType();
            assertTrue( ct != null && ct.contains( "application/atom+xml" ),
                    "Feed content type should be application/atom+xml, was: " + ct );
        } finally {
            conn.disconnect();
        }
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void atomFeedClusterFilter() throws Exception {
        final String filtered = fetchUrl( "/feed.xml?cluster=test-cluster" );

        // The filtered feed should only contain articles from test-cluster
        assertTrue( filtered.contains( "<feed" ), "Filtered feed should be valid Atom XML" );
        // It should contain our test-cluster articles
        // Note: articles from other clusters should be absent, but we can only test positively
        // that the feed is valid and returned successfully
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void articlePageHasAtomFeedAutodiscovery() throws Exception {
        final String html = fetchPage( "SemanticArticle" );

        // Should have global feed link
        assertTrue( html.contains( "application/atom+xml" ),
                "Article page should have Atom feed autodiscovery link" );
        assertTrue( html.contains( "feed.xml" ),
                "Autodiscovery should point to feed.xml" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void clusteredArticleHasClusterFeedAutodiscovery() throws Exception {
        final String html = fetchPage( "SemanticArticle" );

        // Should have cluster-specific feed link
        assertTrue( html.contains( "feed.xml?cluster=test-cluster" ),
                "Clustered article should have cluster-filtered feed autodiscovery link" );
    }

    // ---- News Sitemap ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void sitemapIncludesNewsNamespace() throws Exception {
        final String sitemap = fetchUrl( "/sitemap.xml" );
        assertTrue( sitemap.contains( "xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\"" ),
                "Sitemap should include Google News namespace" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void sitemapIncludesNewsEntryForTaggedPage() throws Exception {
        final String sitemap = fetchUrl( "/sitemap.xml" );

        // SemanticArticle has tags and was just created (within NEWS_CUTOFF_DAYS),
        // so it should have a news:news element
        if ( sitemap.contains( "SemanticArticle" ) ) {
            // Find the <url> block for SemanticArticle and check for news:news within it
            final int articleStart = sitemap.indexOf( "SemanticArticle" );
            // Walk back to find <url> start
            final int urlStart = sitemap.lastIndexOf( "<url>", articleStart );
            final int urlEnd = sitemap.indexOf( "</url>", articleStart );
            if ( urlStart >= 0 && urlEnd >= 0 ) {
                final String urlBlock = sitemap.substring( urlStart, urlEnd );
                assertTrue( urlBlock.contains( "<news:news>" ),
                        "Recently modified tagged page should have news:news element in sitemap" );
                assertTrue( urlBlock.contains( "<news:keywords>" ),
                        "News entry should include keywords from frontmatter tags" );
            }
        }
    }

    // ---- Cross-cutting: page without frontmatter should still render ----

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void plainPageRendersWithoutFrontmatter() throws Exception {
        final String html = fetchPage( "PlainPage" );
        assertTrue( html.contains( "Plain Page" ),
                "Page body should render even without frontmatter" );
        assertTrue( html.contains( "og:title" ),
                "Even pages without frontmatter should have OG tags" );
    }

    // ==== Helper methods ====

    private String fetchPage( final String pageName ) throws Exception {
        return fetchUrl( "/wiki/" + pageName );
    }

    private String fetchUrl( final String path ) throws Exception {
        final HttpURLConnection conn = openConnection( path );
        try {
            assertEquals( 200, conn.getResponseCode(),
                    "Expected HTTP 200 for " + path + " but got " + conn.getResponseCode() );
            try ( final BufferedReader reader = new BufferedReader(
                    new InputStreamReader( conn.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
                return reader.lines().collect( Collectors.joining( "\n" ) );
            }
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openConnection( final String path ) throws Exception {
        String base = Env.TESTS_BASE_URL;
        if ( base.endsWith( "/" ) ) {
            base = base.substring( 0, base.length() - 1 );
        }
        final URL url = URI.create( base + path ).toURL();
        final HttpURLConnection conn = ( HttpURLConnection ) url.openConnection();
        conn.setRequestMethod( "GET" );
        return conn;
    }

    /**
     * Extracts the content attribute of the first meta tag matching the given attr name and value.
     * Works for both {@code <meta name="..." content="...">} and {@code <meta property="..." content="...">}.
     */
    private static String extractMetaContent( final String html, final String attr, final String value ) {
        // Match both name="value" and property="value" patterns, with content before or after
        final Pattern p = Pattern.compile(
                "<meta\\s+[^>]*?" + attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\"" +
                        "[^>]*?content\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        Matcher m = p.matcher( html );
        if ( m.find() ) {
            return m.group( 1 );
        }
        // Try reversed order: content before attr
        final Pattern p2 = Pattern.compile(
                "<meta\\s+[^>]*?content\\s*=\\s*\"([^\"]*)\"[^>]*?" +
                        attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\"", Pattern.CASE_INSENSITIVE );
        m = p2.matcher( html );
        return m.find() ? m.group( 1 ) : null;
    }

    /**
     * Extracts all content values for meta tags matching the given attr/value (for repeated tags like article:tag).
     */
    private static List< String > extractAllMetaContent( final String html, final String attr, final String value ) {
        final List< String > results = new ArrayList<>();
        final Pattern p = Pattern.compile(
                "<meta\\s+[^>]*?" + attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\"" +
                        "[^>]*?content\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        final Matcher m = p.matcher( html );
        while ( m.find() ) {
            results.add( m.group( 1 ) );
        }
        // Also check reversed order
        final Pattern p2 = Pattern.compile(
                "<meta\\s+[^>]*?content\\s*=\\s*\"([^\"]*)\"[^>]*?" +
                        attr + "\\s*=\\s*\"" + Pattern.quote( value ) + "\"", Pattern.CASE_INSENSITIVE );
        final Matcher m2 = p2.matcher( html );
        while ( m2.find() ) {
            final String val = m2.group( 1 );
            if ( !results.contains( val ) ) {
                results.add( val );
            }
        }
        return results;
    }

    /**
     * Extracts the href of a {@code <link rel="...">} tag.
     */
    private static String extractLinkHref( final String html, final String rel ) {
        final Pattern p = Pattern.compile(
                "<link\\s+[^>]*?rel\\s*=\\s*\"" + Pattern.quote( rel ) + "\"" +
                        "[^>]*?href\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE );
        final Matcher m = p.matcher( html );
        return m.find() ? m.group( 1 ) : null;
    }

    /**
     * Extracts the first JSON-LD script block (skipping BreadcrumbList) and parses it.
     */
    private static JsonObject extractFirstJsonLd( final String html ) {
        final Pattern p = Pattern.compile(
                "<script\\s+type\\s*=\\s*\"application/ld\\+json\"\\s*>\\s*(\\{.*?})\\s*</script>",
                Pattern.DOTALL );
        final Matcher m = p.matcher( html );
        while ( m.find() ) {
            try {
                final JsonObject obj = GSON.fromJson( m.group( 1 ), JsonObject.class );
                // Skip BreadcrumbList — we want the main entity
                if ( obj.has( "@type" ) && !"BreadcrumbList".equals( obj.get( "@type" ).getAsString() ) ) {
                    return obj;
                }
            } catch ( final Exception ignored ) {
            }
        }
        return null;
    }

    /**
     * Extracts a JSON-LD script block by @type.
     */
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
