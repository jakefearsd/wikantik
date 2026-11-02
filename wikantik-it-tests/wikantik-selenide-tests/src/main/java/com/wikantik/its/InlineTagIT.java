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

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Obsidian inline {@code #tags} in a real browser: the reader renders {@code #tag} as a link, clicking it opens the tag
 * view ({@code /search?tag=x}), and that view lists every page carrying the tag — a page that wrote it inline and a page
 * that declares it in frontmatter (spelled with a capital, so the two meet only through normalisation).
 *
 * <p>The structural index is refreshed by the save event, off the request thread, so the tag view is reloaded until the
 * seeded pages appear rather than asserted once.</p>
 */
public class InlineTagIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 30 );
    private static final long TS = System.currentTimeMillis();
    private static final String TAG = "tagit" + TS;
    private static final String INLINE = "InlineTagPage" + TS;
    private static final String FRONT = "FrontTagPage" + TS;

    @BeforeAll
    static void seed() throws Exception {
        RestSeedHelper.writePage( INLINE, "Notes that mention #" + TAG + " in passing.\n\nAnd `#" + TAG + "x` in code.\n" );
        RestSeedHelper.writePageWithFrontmatter( FRONT,
            "---\ntype: article\ntags: [" + TAG.toUpperCase() + "]\nsummary: A page whose tag is declared in its frontmatter\n---\nBody.\n" );
    }

    @AfterAll
    static void cleanUp() {
        RestSeedHelper.deletePageQuietly( INLINE );
        RestSeedHelper.deletePageQuietly( FRONT );
    }

    @Test
    void clickingAnInlineTagOpensTheTagViewListingEveryPageWithTheTag() {
        ViewWikiPage.open( INLINE );
        final SelenideElement link = $( "a.tag.inline-tag" ).shouldBe( Condition.visible, WAIT );
        link.shouldHave( Condition.text( "#" + TAG ) );
        assertTrue( link.getAttribute( "href" ).endsWith( "/search?tag=" + TAG ), "the chip must lead to the tag view: " + link.getAttribute( "href" ) );
        link.click();

        $( "[data-testid=search-results-page]" ).shouldBe( Condition.visible, WAIT );
        final long deadline = System.currentTimeMillis() + 60_000;
        while ( System.currentTimeMillis() < deadline && !listsBoth() ) {
            Selenide.sleep( 1_000 );
            Selenide.refresh();
            $( "[data-testid=search-results-page]" ).shouldBe( Condition.visible, WAIT );
        }
        $( "[data-testid=search-results-heading]" ).shouldHave( Condition.text( "tagged #" + TAG ) );
        $( "[data-testid=search-result-card][data-page-name='" + INLINE + "']" ).shouldBe( Condition.visible );
        $( "[data-testid=search-result-card][data-page-name='" + FRONT + "']" ).shouldBe( Condition.visible );
    }

    @Test
    void aFrontmatterTagChipLeadsToTheSameView() {
        ViewWikiPage.open( FRONT );
        final SelenideElement chip = $( "a.tag" ).shouldBe( Condition.visible, WAIT );
        assertTrue( chip.getAttribute( "href" ).endsWith( "/search?tag=" + TAG ),
            "a frontmatter chip must link to the normalised tag view: " + chip.getAttribute( "href" ) );
    }

    private static boolean listsBoth() {
        return $( "[data-testid=search-result-card][data-page-name='" + INLINE + "']" ).exists()
            && $( "[data-testid=search-result-card][data-page-name='" + FRONT + "']" ).exists();
    }
}
