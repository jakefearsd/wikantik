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
