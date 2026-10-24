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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownBriefingRendererTest {

    @Test
    void rendersFullBriefing() {
        final CitationHandle citation = new CitationHandle(
            "deploy-guide", 7, List.of( "Setup" ), "<section text>", "sha256:abc" );
        final BundleSection section = new BundleSection(
            "deploy-guide", "DeployGuide", List.of( "Setup" ), "<section text>", 0.91, citation );
        final BundleCoverage coverage = new BundleCoverage( 2, 2, 0.91, BundleCoverage.STRONG );
        final BriefingItem included = new BriefingItem(
            "BillingProcess", "billing-process", "Billing Process", "", "pin", true, "<page body>" );
        final BriefingItem pointer = new BriefingItem(
            "Q3Goals", "q3-goals", "Q3 Goals", "One-line summary here.", "pin", false, null );
        final ContextBriefing briefing = new ContextBriefing(
            "what is the deploy process", List.of( section ), coverage,
            List.of( included, pointer ), List.of( "unknown pin: Nope" ), 4000, 1200 );

        final String expected = """
            # Wiki context briefing
            _Coverage: strong — 2 sections across 2 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

            ## Task-relevant sections

            ### DeployGuide › Setup (deploy-guide @ v7)

            <section text>

            ## Standing context

            ### Billing Process (`BillingProcess`)

            <page body>

            ## Available on request

            - **Q3 Goals** (`Q3Goals`) — One-line summary here.

            > Briefing warnings: unknown pin: Nope
            """;

        assertEquals( expected, MarkdownBriefingRenderer.render( briefing ) );
    }

    /** The pins-only briefing of {@link #rendersFullBriefing()}, with its pre-tier-headings expected output. */
    @Test
    void omitsNewHeadingsWhenEmpty() {
        final CitationHandle citation = new CitationHandle(
            "deploy-guide", 7, List.of( "Setup" ), "<section text>", "sha256:abc" );
        final BundleSection section = new BundleSection(
            "deploy-guide", "DeployGuide", List.of( "Setup" ), "<section text>", 0.91, citation );
        final BundleCoverage coverage = new BundleCoverage( 2, 2, 0.91, BundleCoverage.STRONG );
        final BriefingItem included = new BriefingItem(
            "BillingProcess", "billing-process", "Billing Process", "", "pin", true, "<page body>" );
        final BriefingItem pointer = new BriefingItem(
            "Q3Goals", "q3-goals", "Q3 Goals", "One-line summary here.", "pin", false, null );
        final ContextBriefing briefing = new ContextBriefing(
            "what is the deploy process", List.of( section ), coverage,
            List.of( included, pointer ), List.of( "unknown pin: Nope" ), 4000, 1200 );

        // Copied verbatim from rendersFullBriefing before the cited/elsewhere tiers were added.
        final String expected = """
            # Wiki context briefing
            _Coverage: strong — 2 sections across 2 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

            ## Task-relevant sections

            ### DeployGuide › Setup (deploy-guide @ v7)

            <section text>

            ## Standing context

            ### Billing Process (`BillingProcess`)

            <page body>

            ## Available on request

            - **Q3 Goals** (`Q3Goals`) — One-line summary here.

            > Briefing warnings: unknown pin: Nope
            """;

        final String rendered = MarkdownBriefingRenderer.render( briefing );

        assertEquals( expected, rendered );
        assertFalse( rendered.contains( "Contracts this project cites" ) );
        assertFalse( rendered.contains( "Elsewhere in the wiki" ) );
    }

    @Test
    void rendersCitedUnderOwnHeading() {
        final BriefingItem member = new BriefingItem(
            "RollerMcp", "01RollerMcp", "Roller MCP", "", "cluster", true, "<member body>", null );
        final BriefingItem citedBody = new BriefingItem(
            "TelemetryContract", "01TelemetryContract", "Telemetry Contract", "Contract.",
            "cited", true, "<contract body>", "simple-agility" );
        final BriefingItem citedPointer = new BriefingItem(
            "CommerceDataFlow", "01CommerceDataFlow", "Commerce Data Flow", "Data flow.",
            "cited", false, null, "simple-agility" );
        final BriefingItem memberPointer = new BriefingItem(
            "RollerSpec", "01RollerSpec", "Roller Spec", "The spec.", "cluster", false, null, null );
        final ContextBriefing briefing = new ContextBriefing(
            null, List.of(), BundleCoverage.empty(),
            List.of( member, citedBody, citedPointer, memberPointer ), List.of(), 4000, 100 );

        final String expected = """
            # Wiki context briefing
            _Coverage: unknown — 0 sections across 0 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

            ## Standing context

            ### Roller MCP (`RollerMcp`)

            <member body>

            ## Contracts this project cites

            ### Telemetry Contract (`TelemetryContract`)

            <contract body>

            - **Commerce Data Flow** (`CommerceDataFlow`) — Data flow.

            ## Available on request

            - **Roller Spec** (`RollerSpec`) — The spec.
            """;

        assertEquals( expected, MarkdownBriefingRenderer.render( briefing ) );
    }

    @Test
    void rendersCitedPointersWithoutBodiesUnderTheSameHeading() {
        final BriefingItem citedPointer = new BriefingItem(
            "CommerceDataFlow", "01CommerceDataFlow", "Commerce Data Flow", "",
            "cited", false, null, "simple-agility" );
        final ContextBriefing briefing = new ContextBriefing(
            null, List.of(), BundleCoverage.empty(), List.of( citedPointer ), List.of(), 4000, 0 );

        final String expected = """
            # Wiki context briefing
            _Coverage: unknown — 0 sections across 0 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

            ## Contracts this project cites

            - **Commerce Data Flow** (`CommerceDataFlow`)
            """;

        assertEquals( expected, MarkdownBriefingRenderer.render( briefing ) );
    }

    @Test
    void rendersElsewhereGroupedByCluster() {
        final BriefingItem first = elsewhere( "CommerceDataFlow", "01CommerceDataFlow", "Setup", "simple-agility" );
        final BriefingItem second = elsewhere( "Phase7Plan", "01Phase7Plan", "Plan › Risks", "stockroom" );
        final BriefingItem third = elsewhere( "Q3Goals", "01Q3Goals", "Goals", "simple-agility" );
        final BriefingItem unclustered = elsewhere( "Rogue", "01Rogue", "Loose", null );
        final BriefingItem bare = elsewhere( "NoHeading", null, "", "stockroom" );
        final ContextBriefing briefing = new ContextBriefing(
            "q", List.of(), BundleCoverage.empty(),
            List.of( first, second, third, unclustered, bare ), List.of(), 4000, 0 );

        final String expected = """
            # Wiki context briefing
            _Coverage: unknown — 0 sections across 0 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._

            ## Elsewhere in the wiki

            - **simple-agility**
              - CommerceDataFlow › Setup (`01CommerceDataFlow`)
              - Q3Goals › Goals (`01Q3Goals`)
            - **stockroom**
              - Phase7Plan › Plan › Risks (`01Phase7Plan`)
              - NoHeading
            - **(unclustered)**
              - Rogue › Loose (`01Rogue`)
            """;

        assertEquals( expected, MarkdownBriefingRenderer.render( briefing ) );
    }

    @Test
    void rendersEveryTierInOrder() {
        final BundleSection section = new BundleSection( "01A", "A", List.of( "H" ), "<text>", 0.9,
            new CitationHandle( "01A", 3, List.of( "H" ), "<text>", "sha" ) );
        final ContextBriefing briefing = new ContextBriefing(
            "q", List.of( section ), new BundleCoverage( 1, 1, 0.9, BundleCoverage.PARTIAL ),
            List.of(
                new BriefingItem( "P", "01P", "Pin", "", "pin", false, null, null ),
                elsewhere( "E", "01E", "Eh", "stockroom" ),
                new BriefingItem( "C", "01C", "Cited", "", "cited", true, "<cited>", "roller" ),
                new BriefingItem( "M", "01M", "Member", "", "cluster", true, "<member>", null ) ),
            List.of( "w" ), 4000, 10 );

        final String rendered = MarkdownBriefingRenderer.render( briefing );

        final List< String > headings = List.of( "## Task-relevant sections", "## Standing context",
            "## Contracts this project cites", "## Elsewhere in the wiki", "## Available on request",
            "> Briefing warnings: w" );
        int at = -1;
        for ( final String heading : headings ) {
            final int next = rendered.indexOf( heading );
            assertTrue( next > at, heading + " must follow the previous tier in: " + rendered );
            at = next;
        }
    }

    private static BriefingItem elsewhere( final String slug, final String canonicalId,
                                           final String headingPath, final String cluster ) {
        return new BriefingItem( slug, canonicalId, slug, headingPath, "elsewhere", false, null, cluster );
    }

    @Test
    void rendersEmptyBriefing() {
        final ContextBriefing briefing = new ContextBriefing(
            null, List.of(), BundleCoverage.empty(), List.of(), List.of(), 4000, 0 );

        final String expected = """
            # Wiki context briefing
            _Coverage: unknown — 0 sections across 0 pages. Deepen with `assemble_bundle("<question>")`; fetch full pages with `read_pages`._
            """;

        assertEquals( expected, MarkdownBriefingRenderer.render( briefing ) );
    }
}
