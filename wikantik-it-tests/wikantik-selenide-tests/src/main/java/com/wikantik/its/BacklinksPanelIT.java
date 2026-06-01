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
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * UI test for the reader "Referenced by" (backlinks) panel.
 *
 * <p>Seeds a source page that wikilinks to a target page, then opens the
 * target and asserts the backlinks panel surfaces the referring page. The
 * panel is backed by {@code /api/backlinks/{name}} and only renders when at
 * least one referrer exists.</p>
 */
public class BacklinksPanelIT extends WithIntegrationTestSetup {

    @Test
    void backlinksPanelListsReferringPages() throws Exception {
        final long ts = System.currentTimeMillis();
        final String target = "BacklinkTarget" + ts;
        final String source = "BacklinkSource" + ts;

        RestSeedHelper.writePage( target, "# " + target + "\n\nThe target page.\n" );
        RestSeedHelper.writePage( source, "# " + source + "\n\nSee [" + target + "](" + target + ").\n" );
        try {
            // Reference indexing happens on save; allow a few reloads in case the
            // referrer set is updated slightly after the PUT returns.
            ViewWikiPage.open( target );
            boolean found = false;
            for ( int attempt = 0; attempt < 10 && !found; attempt++ ) {
                found = $( "[data-testid=backlinks-panel]" ).exists()
                     && $( "[data-testid=backlinks-panel]" ).$( By.linkText( source ) ).exists();
                if ( !found ) {
                    Selenide.sleep( 500 );
                    Selenide.refresh();
                    $( "[data-testid=page-view]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
                }
            }

            $( "[data-testid=backlinks-panel]" )
                .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) )
                .$( By.linkText( source ) )
                .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        } finally {
            RestSeedHelper.deletePageQuietly( source );
            RestSeedHelper.deletePageQuietly( target );
        }
    }
}
