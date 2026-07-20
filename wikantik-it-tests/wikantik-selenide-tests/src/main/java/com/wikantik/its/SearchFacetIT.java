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

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Condition;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * UI test for client-side search faceting on the results page.
 *
 * <p>Seeds two pages that share a unique search term but sit in different
 * clusters, runs the search, then clicks the cluster facet chip and asserts the
 * result set narrows to the single matching page.</p>
 */
public class SearchFacetIT extends WithIntegrationTestSetup {

    @Test
    void clusterFacetNarrowsResults() throws Exception {
        final long ts = System.currentTimeMillis();
        final String term = "facettest" + ts;
        final String clusterA = "FacetClusterA" + ts;
        final String clusterB = "FacetClusterB" + ts;
        final String pageA = "FacetPageA" + ts;
        final String pageB = "FacetPageB" + ts;

        RestSeedHelper.writePageWithFrontmatter( pageA,
            "---\ncluster: " + clusterA + "\ntype: article\n---\n# " + pageA + "\n\n" + term + " content\n" );
        RestSeedHelper.writePageWithFrontmatter( pageB,
            "---\ncluster: " + clusterB + "\ntype: article\n---\n# " + pageB + "\n\n" + term + " content\n" );
        try {
            // Retry the search until BOTH freshly-saved pages are indexed
            // (per-change indexing runs async with indexdelay=1s; a fixed
            // sleep here was a latent flake under machine load).
            ViewWikiPage.open( "Main" ).searchForUntilFound( term, pageA );
            ViewWikiPage.open( "Main" ).searchForUntilFound( term, pageB );

            // Both pages are returned.
            $$( "[data-testid=search-result-card]" )
                .shouldHave( CollectionCondition.sizeGreaterThanOrEqual( 2 ), Duration.ofSeconds( 10 ) );

            // The facet rail is present with a chip for each cluster.
            $( ".search-facets" ).shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
            $$( ".facet-chip" )
                .findBy( Condition.text( clusterA ) )
                .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
                .click();

            // Selecting clusterA narrows to exactly the page in that cluster.
            $$( "[data-testid=search-result-card]" )
                .shouldHave( CollectionCondition.size( 1 ), Duration.ofSeconds( 5 ) );
            $( "[data-testid=search-result-card][data-page-name=\"" + pageA + "\"]" )
                .shouldBe( Condition.exist, Duration.ofSeconds( 5 ) );
        } finally {
            RestSeedHelper.deletePageQuietly( pageA );
            RestSeedHelper.deletePageQuietly( pageB );
        }
    }
}
