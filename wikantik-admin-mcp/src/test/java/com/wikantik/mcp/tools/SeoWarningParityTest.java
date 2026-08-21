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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract BETWEEN two tools that answer the same question: the SEO warnings
 * reported by {@code preview_structured_data} and by {@code verify_pages}'
 * {@code seo_readiness} check. Neither tool's own test owns this relationship, so it
 * lives here.
 *
 * <p>{@code PreviewStructuredDataTool} used to hand-roll its own copy of the five SEO
 * rules — its own source said so ({@code // Warnings (same checks as seo_readiness)}) —
 * while {@code VerifyPagesTool} composed them from the shared {@link PageChecks}
 * Strategy implementations. Three of the five rules had already drifted apart, which is
 * exactly what {@code PageChecks}' javadoc says it exists to prevent: "composed by audit
 * and verification tools ... without duplicating the validation logic across tools".
 *
 * <p>The drift cases below are the interesting ones — they are pages where the two tools
 * genuinely disagreed about whether a rule fires, not merely about wording.
 */
class SeoWarningParityTest {

    private StubPageManager pm;
    private PreviewStructuredDataTool preview;
    private VerifyPagesTool verify;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        preview = new PreviewStructuredDataTool( pm, "TestWiki", "http://localhost:8080" );
        verify = new VerifyPagesTool( pm, new StubReferenceManager() );
    }

    @SuppressWarnings( "unchecked" )
    private List< String > previewWarnings( final String slug ) {
        final Map< String, Object > args = new HashMap<>();
        args.put( "slug", slug );
        final McpSchema.CallToolResult r = preview.execute( args );
        final Map< String, Object > data =
            gson.fromJson( ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text(), Map.class );
        return ( List< String > ) data.get( "warnings" );
    }

    @SuppressWarnings( "unchecked" )
    private List< String > seoReadinessWarnings( final String slug ) {
        final Map< String, Object > args = new HashMap<>();
        args.put( "slugs", List.of( slug ) );
        args.put( "checks", List.of( "seo_readiness" ) );
        final McpSchema.CallToolResult r = verify.execute( args );
        final Map< String, Object > data =
            gson.fromJson( ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text(), Map.class );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        return ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
    }

    /**
     * Drift case 1 — a whitespace-only summary. {@code PageChecks.SummaryCheck} strips
     * before testing for emptiness, so it reports a MISSING summary. The hand-rolled copy
     * did not strip, so it measured a 3-character summary and reported it as merely "too
     * short" — a materially different piece of advice for the same page.
     */
    @Test
    void whitespaceOnlySummary_bothToolsCallItMissing() {
        pm.savePage( "SeoBlankSummary",
            "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\nsummary: '   '\n---\nBody." );

        assertTrue( seoReadinessWarnings( "SeoBlankSummary" ).stream()
                .anyMatch( w -> w.contains( "No summary" ) ),
            "Baseline: seo_readiness treats a whitespace-only summary as missing." );

        assertTrue( previewWarnings( "SeoBlankSummary" ).stream()
                .anyMatch( w -> w.contains( "No summary" ) ),
            "preview_structured_data must agree that a whitespace-only summary is missing, "
          + "not report it as a 3-character 'too short' summary." );
    }

    /**
     * Drift case 2 — a {@code date:} key present with an empty value. SnakeYAML puts the
     * key in the map with a null value. {@code PageChecks.DateCheck} keys off
     * {@code containsKey}, so it stays quiet; the hand-rolled copy keyed off the extracted
     * string being null, so it claimed the date was missing.
     */
    @Test
    void dateKeyPresentButEmpty_bothToolsAgree() {
        pm.savePage( "SeoEmptyDate",
            "---\ntype: article\ntags:\n- ai\ndate:\n"
          + "summary: A sufficiently long summary that comfortably clears the fifty character floor\n---\nBody." );

        final boolean verifyFlagsIt = seoReadinessWarnings( "SeoEmptyDate" ).stream()
            .anyMatch( w -> w.contains( "No date" ) );
        final boolean previewFlagsIt = previewWarnings( "SeoEmptyDate" ).stream()
            .anyMatch( w -> w.contains( "No date" ) );

        assertEquals( verifyFlagsIt, previewFlagsIt,
            "The two tools must agree on whether an empty date: key counts as a missing date." );
    }

    /**
     * Drift case 3 — a {@code cluster:} key present with an empty value and no
     * {@code type:}. {@code ClusterTypeCheck} keys off {@code containsKey} and fires; the
     * hand-rolled copy required a non-null extracted cluster and stayed quiet.
     */
    @Test
    void clusterKeyPresentButEmptyWithNoType_bothToolsAgree() {
        pm.savePage( "SeoEmptyCluster",
            "---\ntags:\n- ai\ndate: 2026-03-15\ncluster:\n"
          + "summary: A sufficiently long summary that comfortably clears the fifty character floor\n---\nBody." );

        final boolean verifyFlagsIt = seoReadinessWarnings( "SeoEmptyCluster" ).stream()
            .anyMatch( w -> w.contains( "cluster but no type" ) );
        final boolean previewFlagsIt = previewWarnings( "SeoEmptyCluster" ).stream()
            .anyMatch( w -> w.contains( "cluster but no type" ) );

        assertEquals( verifyFlagsIt, previewFlagsIt,
            "The two tools must agree on whether an empty cluster: key without a type is a problem." );
    }

    /**
     * The bulk case: for a page that trips several rules at once, every warning
     * {@code seo_readiness} reports must also be reported by {@code preview_structured_data}.
     * Preview may add its own extras (see {@link #inRangeSummary_previewKeepsItsPositiveConfirmation}),
     * so this is containment rather than set equality.
     */
    @Test
    void pageTrippingEveryRule_previewReportsEverythingSeoReadinessDoes() {
        pm.savePage( "SeoBad", "---\ntype: hub\ncluster: ai\n---\nBody with no metadata to speak of." );

        final List< String > expected = seoReadinessWarnings( "SeoBad" );
        final List< String > actual = previewWarnings( "SeoBad" );

        assertTrue( expected.size() >= 3, "Fixture should trip several rules, got: " + expected );
        for ( final String warning : expected ) {
            assertTrue( actual.contains( warning ),
                "preview_structured_data is missing a warning that seo_readiness reports.\n"
              + "  missing: " + warning + "\n  preview reported: " + actual );
        }
    }

    /**
     * The one deliberate difference worth keeping. {@code PageChecks} only ever emits
     * problems, but the preview surface also confirms a summary that is in range — useful
     * when you are previewing a page precisely to check its SEO shape. This test exists so
     * the confirmation is not "cleaned up" as an inconsistency later.
     */
    @Test
    void inRangeSummary_previewKeepsItsPositiveConfirmation() {
        pm.savePage( "SeoGood",
            "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\n"
          + "summary: A comprehensive and appropriately sized summary of this particular page\n"
          + "---\nBody." );

        assertTrue( seoReadinessWarnings( "SeoGood" ).isEmpty(),
            "Good metadata should trip no seo_readiness rule." );
        assertTrue( previewWarnings( "SeoGood" ).stream().anyMatch( w -> w.contains( "good" ) ),
            "Preview keeps its positive in-range confirmation, which PageChecks does not emit." );
    }
}
