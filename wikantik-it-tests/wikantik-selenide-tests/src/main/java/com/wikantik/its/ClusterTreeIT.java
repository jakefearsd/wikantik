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
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$$;

/**
 * UI test for the collapsible cluster tree in the reader sidebar.
 *
 * <p>Uses the pre-seeded {@code SemanticArticle}/{@code SemanticHub} fixtures,
 * both in cluster {@code test-cluster} (indexed from frontmatter at startup, so
 * the assertion is provider-agnostic and free of save-time indexing races).
 * Opening the article must render its cluster as a collapsible section header
 * whose body — auto-expanded because it holds the active page — links to the
 * page; collapsing the header removes the link from that section.</p>
 */
public class ClusterTreeIT extends WithIntegrationTestSetup {

    private static final String CLUSTER = "test-cluster";
    private static final String PAGE = "SemanticArticle";

    @Test
    void sidebarGroupsPagesUnderCollapsibleClusterHeaders() {
        ViewWikiPage.open( PAGE );

        // The cluster appears as a collapsible section header in the sidebar.
        final SelenideElement header = $$( ".app-sidebar .personal-section-header" )
            .findBy( Condition.text( CLUSTER ) )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );

        // Scope link assertions to this cluster's own section — the page may also
        // appear in the sidebar's "Recently Modified" feed, so a sidebar-wide
        // query could match that duplicate.
        final SelenideElement section = header.closest( ".personal-section" );

        // The active page's cluster is open by default, so the page link shows.
        section.$$( "a" )
            .findBy( Condition.text( PAGE ) )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );

        // Collapsing the header removes the body (CollapsibleSection unmounts it).
        header.click();
        section.$$( "a" )
            .filterBy( Condition.text( PAGE ) )
            .shouldHave( CollectionCondition.size( 0 ), Duration.ofSeconds( 5 ) );

        // Re-expanding brings the page link back.
        header.click();
        section.$$( "a" )
            .findBy( Condition.text( PAGE ) )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
    }
}
