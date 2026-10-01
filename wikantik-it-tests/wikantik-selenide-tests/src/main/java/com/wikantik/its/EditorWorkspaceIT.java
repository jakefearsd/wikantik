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

import com.codeborne.selenide.Selenide;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.actions;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser IT for the editor workspace: the Ctrl-O page switcher and Obsidian
 * callout rendering on the read view. The switcher test types the name of a
 * startup fixture page (never a freshly created one) so it cannot be affected
 * by structural-index lag.
 */
public class EditorWorkspaceIT extends WithIntegrationTestSetup {

    /** Startup fixture page, present before the structural index ever rebuilds. */
    private static final String FIXTURE = "SemanticArticle";

    private static final Duration ASYNC_WAIT = Duration.ofSeconds( 15 );

    @BeforeEach
    void login() {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
                .clickOnLogin()
                .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    @Test
    void ctrlOSwitcherNavigatesToPage() {
        ViewWikiPage.open( "Main" );
        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );

        actions().keyDown( Keys.CONTROL ).sendKeys( "o" ).keyUp( Keys.CONTROL ).perform();
        $( "[data-testid=search-overlay]" ).shouldBe( visible, ASYNC_WAIT );

        $( "[data-testid=search-overlay-input]" ).sendKeys( FIXTURE );
        $( "[data-testid=quick-row][data-kind=page][data-page-name=" + FIXTURE + "]" )
                .shouldBe( visible, ASYNC_WAIT );

        actions().sendKeys( Keys.ENTER ).perform();

        $( "[data-testid=page-view]" ).shouldBe( visible, ASYNC_WAIT );
        final String url = com.codeborne.selenide.WebDriverRunner.url();
        assertTrue( url.endsWith( "/wiki/" + FIXTURE ), "expected URL to end with /wiki/" + FIXTURE + " but was " + url );
    }

    @Test
    void calloutRendersOnReadView() throws Exception {
        final String page = "EwCalloutIT";
        RestSeedHelper.writePage( page, "> [!warning] Mind the gap\n> Careful.\n" );
        try {
            ViewWikiPage.open( page );
            $( ".callout.callout-warning .callout-title-inner" ).shouldBe( visible, ASYNC_WAIT )
                    .shouldHave( exactText( "Mind the gap" ) );
            $( ".callout.callout-warning .callout-content" ).shouldHave( exactText( "Careful." ) );
        } finally {
            RestSeedHelper.deletePageQuietly( page );
        }
    }
}
