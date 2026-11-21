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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubReferenceManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the complete {@code verify_pages} output — every key, its order, and the summary gates —
 * for all checks active together and for each check alone.
 */
class VerifyPagesToolOutputTest {

    private StubPageManager pm;
    private StubReferenceManager refMgr;
    private VerifyPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        refMgr = new StubReferenceManager();
        tool = new VerifyPagesTool( pm, refMgr );
        pm.savePage( "Healthy", "---\ntype: article\ntags: [alpha]\nsummary: "
                + "A reasonably specific summary describing how the healthy page demonstrates verification.\n"
                + "date: \"2026-01-15\"\ncluster: verify-cluster\n---\n## Specific Heading\nBody text." );
        pm.savePage( "Weak", "Body only." );
        pm.savePage( "Lonely", "Nobody links here." );
        refMgr.addReferences( "Healthy", Set.of( "Weak" ) );
        refMgr.addReferences( "Weak", Set.of( "GhostPage", "Healthy" ) );
        refMgr.addReferences( "Referrer", Set.of( "Healthy" ) );
    }

    private String run( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        // The nested frontmatter map's own key order is the parser's, not this tool's, so mask it;
        // the entry-level key order, which is what the tool controls, stays pinned.
        return gson.fromJson( json, Map.class ).toString().replaceAll( "metadata=\\{[^}]*\\}", "metadata={...}" );
    }

    private String run( final String... checks ) {
        return checks.length == 0
                ? run( Map.of( "slugs", List.of( "Healthy", "Weak", "Lonely", "Missing" ) ) )
                : run( Map.of( "slugs", List.of( "Healthy", "Weak", "Lonely", "Missing" ), "checks", List.of( checks ) ) );
    }

    @Test
    void metadataEntryCarriesFrontmatter() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "slugs", List.of( "Healthy" ), "checks", List.of( "metadata_completeness" ) ) );
        final Map< ?, ? > first = ( Map< ?, ? > ) ( ( List< ? > ) gson.fromJson(
                ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text(), Map.class ).get( "pages" ) ).get( 0 );
        assertEquals( Map.of( "type", "article", "tags", List.of( "alpha" ),
                "summary", "A reasonably specific summary describing how the healthy page demonstrates verification.",
                "date", "2026-01-15", "cluster", "verify-cluster" ), first.get( "metadata" ) );
    }

    @Test
    void defaultChecks() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, outboundLinks=[Weak], brokenLinks=[], backlinks=[Referrer, Weak], metadata={...}, missingMetadata=[]}, "
                + "{pageName=Weak, exists=true, version=1.0, outboundLinks=[GhostPage, Healthy], brokenLinks=[GhostPage], backlinks=[Healthy], missingMetadata=[summary, tags, type]}, "
                + "{pageName=Lonely, exists=true, version=1.0, outboundLinks=[], brokenLinks=[], backlinks=[], missingMetadata=[summary, tags, type]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, totalBrokenLinks=1.0, pagesWithNoBacklinks=[Lonely], metadataIssues=[Weak missing: summary, tags, type, Lonely missing: summary, tags, type]}}", run() );
    }

    @Test
    void allChecks() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, outboundLinks=[Weak], brokenLinks=[], backlinks=[Referrer, Weak], metadata={...}, missingMetadata=[], seoWarnings=[], retrievalWarnings=[No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Weak, exists=true, version=1.0, outboundLinks=[GhostPage, Healthy], brokenLinks=[GhostPage], backlinks=[Healthy], missingMetadata=[summary, tags, type], seoWarnings=[No summary — no meta description for search engines, No tags — page will not appear in Google News Sitemap, No date — JSON-LD will lack datePublished], retrievalWarnings=[No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74, Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections, No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries, No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Lonely, exists=true, version=1.0, outboundLinks=[], brokenLinks=[], backlinks=[], missingMetadata=[summary, tags, type], seoWarnings=[No summary — no meta description for search engines, No tags — page will not appear in Google News Sitemap, No date — JSON-LD will lack datePublished], retrievalWarnings=[No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74, Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections, No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries, No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, totalBrokenLinks=1.0, pagesWithNoBacklinks=[Lonely], metadataIssues=[Weak missing: summary, tags, type, Lonely missing: summary, tags, type], seoIssues=[Weak: No summary — no meta description for search engines; No tags — page will not appear in Google News Sitemap; No date — JSON-LD will lack datePublished, Lonely: No summary — no meta description for search engines; No tags — page will not appear in Google News Sitemap; No date — JSON-LD will lack datePublished], retrievalIssues=[Healthy: No title — the embedding falls back to the slug; add a natural-language title, Weak: No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74; Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections; No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries; No title — the embedding falls back to the slug; add a natural-language title, Lonely: No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74; Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections; No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries; No title — the embedding falls back to the slug; add a natural-language title]}}", run( "existence", "broken_links", "backlinks", "outbound_links",
                "metadata_completeness", "seo_readiness", "retrieval_readiness" ) );
    }

    @Test
    void existenceAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0}, "
                + "{pageName=Weak, exists=true, version=1.0}, "
                + "{pageName=Lonely, exists=true, version=1.0}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false}}", run( "existence" ) );
    }

    @Test
    void brokenLinksAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, brokenLinks=[]}, "
                + "{pageName=Weak, exists=true, version=1.0, brokenLinks=[GhostPage]}, "
                + "{pageName=Lonely, exists=true, version=1.0, brokenLinks=[]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, totalBrokenLinks=1.0}}", run( "broken_links" ) );
    }

    @Test
    void outboundLinksAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, outboundLinks=[Weak]}, "
                + "{pageName=Weak, exists=true, version=1.0, outboundLinks=[GhostPage, Healthy]}, "
                + "{pageName=Lonely, exists=true, version=1.0, outboundLinks=[]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false}}", run( "outbound_links" ) );
    }

    @Test
    void backlinksAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, backlinks=[Referrer, Weak]}, "
                + "{pageName=Weak, exists=true, version=1.0, backlinks=[Healthy]}, "
                + "{pageName=Lonely, exists=true, version=1.0, backlinks=[]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, pagesWithNoBacklinks=[Lonely]}}", run( "backlinks" ) );
    }

    @Test
    void metadataAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, metadata={...}, missingMetadata=[]}, "
                + "{pageName=Weak, exists=true, version=1.0, missingMetadata=[summary, tags, type]}, "
                + "{pageName=Lonely, exists=true, version=1.0, missingMetadata=[summary, tags, type]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, metadataIssues=[Weak missing: summary, tags, type, Lonely missing: summary, tags, type]}}", run( "metadata_completeness" ) );
    }

    @Test
    void seoAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, seoWarnings=[]}, "
                + "{pageName=Weak, exists=true, version=1.0, seoWarnings=[No summary — no meta description for search engines, No tags — page will not appear in Google News Sitemap, No date — JSON-LD will lack datePublished]}, "
                + "{pageName=Lonely, exists=true, version=1.0, seoWarnings=[No summary — no meta description for search engines, No tags — page will not appear in Google News Sitemap, No date — JSON-LD will lack datePublished]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, seoIssues=[Weak: No summary — no meta description for search engines; No tags — page will not appear in Google News Sitemap; No date — JSON-LD will lack datePublished, Lonely: No summary — no meta description for search engines; No tags — page will not appear in Google News Sitemap; No date — JSON-LD will lack datePublished]}}", run( "seo_readiness" ) );
    }

    @Test
    void retrievalAlone() {
        assertEquals( "{pages=[{pageName=Healthy, exists=true, version=1.0, retrievalWarnings=[No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Weak, exists=true, version=1.0, retrievalWarnings=[No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74, Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections, No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries, No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Lonely, exists=true, version=1.0, retrievalWarnings=[No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74, Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections, No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries, No title — the embedding falls back to the slug; add a natural-language title]}, "
                + "{pageName=Missing, exists=false}], summary={totalPages=4.0, allExist=false, retrievalIssues=[Healthy: No title — the embedding falls back to the slug; add a natural-language title, Weak: No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74; Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections; No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries; No title — the embedding falls back to the slug; add a natural-language title, Lonely: No summary — chunk embeddings lack the page-level disambiguation context that lifted section recall ~0.60→0.74; Page has no section headings — the whole body shares one chunk context; add descriptive H2/H3 sections; No cluster — chunk embeddings lack the domain prefix that disambiguates cross-topic queries; No title — the embedding falls back to the slug; add a natural-language title]}}", run( "retrieval_readiness" ) );
    }
}
