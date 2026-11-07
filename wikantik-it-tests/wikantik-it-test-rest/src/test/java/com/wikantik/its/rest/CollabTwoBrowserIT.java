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
package com.wikantik.its.rest;


import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideConfig;
import com.codeborne.selenide.SelenideDriver;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.RestSeedHelper;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-time collaborative editing in two real browsers: janne and Alice open the same page in the editor (collaboration
 * is enabled for this module's IT wiki only), see each other, see each other's typing, converge when they type at once, and a publish
 * makes the merged text the page. The two drivers are switched with {@link WebDriverRunner#setWebDriver}, so the
 * ordinary page objects drive whichever browser is current.
 */
public class CollabTwoBrowserIT extends WithIntegrationTestSetup {

    private static final Logger LOG = LoggerFactory.getLogger( CollabTwoBrowserIT.class );

    private static final String PAGE = "CollabTwoBrowserIT" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );
    private static final String SEED = "Collab E2E seed.\n";
    private static final String ALICE = "Alice";
    private static final String ALICE_PASSWORD = "password";
    private static final Duration WAIT = Duration.ofSeconds( 20 );
    /** How long one browser may take to show the other's typing (push, wake the long-poll, apply, paint). */
    private static final Duration PROPAGATE = Duration.ofSeconds( 5 );

    private WebDriver janneBrowser;
    private WebDriver aliceBrowser;

    @BeforeEach
    void twoBrowsersSignedIn() throws Exception {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" ).clickOnLogin().performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();
        RestSeedHelper.writePage( PAGE, SEED );
        janneBrowser = WebDriverRunner.getWebDriver();

        final SelenideConfig config = new SelenideConfig().headless( Env.TESTS_CONFIG_HEADLESS )
            .browserSize( Env.TESTS_CONFIG_BROWSER_SIZE ).reportsFolder( Env.TESTS_CONFIG_REPORTS_FOLDER )
            .downloadsFolder( Env.TESTS_CONFIG_DOWNLOADS_FOLDER );
        aliceBrowser = new SelenideDriver( config ).getAndCheckWebDriver();
        use( aliceBrowser );
        ViewWikiPage.open( "Main" ).clickOnLogin().performLogin( ALICE, ALICE_PASSWORD );
        use( janneBrowser );
    }

    @AfterEach
    void closeBoth() {
        try {
            use( aliceBrowser );
            clearEditorMode();
        } catch ( final RuntimeException e ) {
            LOG.warn( "could not clear Alice's editor mode preference: {}", e.toString() );
        }
        try {
            aliceBrowser.quit();
        } catch ( final RuntimeException e ) {
            LOG.warn( "could not close Alice's browser: {}", e.toString() );
        }
        use( janneBrowser );
        Selenide.closeWebDriver();
        RestSeedHelper.deletePageQuietly( PAGE );
    }

    @Test
    void twoBrowsersEditTogetherConvergeAndPublishTheMergedText() {
        // Both open the editor; both are in collaboration mode (the status says so), and each sees both of them.
        use( janneBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        use( aliceBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        awaitEditorsCount( "2 editing" );
        use( janneBrowser );
        awaitEditorsCount( "2 editing" );
        $( ".collab-presence" ).shouldHave( text( ALICE ), WAIT );
        EditWikiPage.awaitDocumentEquals( SEED, WAIT );

        // janne types at the end; Alice sees it.
        EditWikiPage.setCaret( EditWikiPage.documentText().length() );
        EditWikiPage.pressKeys( "JANNE-SAYS-HI" );
        use( aliceBrowser );
        EditWikiPage.awaitDocumentContaining( "JANNE-SAYS-HI", PROPAGATE );

        // Alice types at the start; janne sees it.
        EditWikiPage.setCaret( 0 );
        EditWikiPage.pressKeys( "ALICE-SAYS-HI " );
        use( janneBrowser );
        EditWikiPage.awaitDocumentContaining( "ALICE-SAYS-HI", PROPAGATE );

        // Both type at once, at opposite ends, without waiting for each other.
        EditWikiPage.setCaret( EditWikiPage.documentText().length() );
        EditWikiPage.pressKeys( " J2" );
        use( aliceBrowser );
        EditWikiPage.setCaret( 0 );
        EditWikiPage.pressKeys( "A2 " );

        // Both converge on one document holding every edit.
        final String merged = awaitBoth( d -> d.contains( "JANNE-SAYS-HI" ) && d.contains( "ALICE-SAYS-HI" )
            && d.contains( " J2" ) && d.contains( "A2 " ) );
        LOG.info( "converged document: [{}]", merged );
        assertTrue( merged.contains( "Collab E2E seed." ), "the seed text survived: " + merged );

        // Alice publishes; her page view shows the merged text.
        use( aliceBrowser );
        $( "[data-testid=editor-save]" ).shouldBe( visible, WAIT ).shouldBe( Condition.enabled, WAIT ).click();
        new WebDriverWait( aliceBrowser, WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT )
            .shouldHave( text( "JANNE-SAYS-HI" ), WAIT ).shouldHave( text( "ALICE-SAYS-HI" ), WAIT )
            .shouldHave( text( "Collab E2E seed." ), WAIT );

        // janne, still in the editor, is not thrown out; the published page is the merged draft.
        use( janneBrowser );
        $( "[data-testid=collab-status]" ).shouldBe( visible, WAIT );
        final String after = EditWikiPage.documentText();
        assertTrue( after.contains( "ALICE-SAYS-HI" ) && after.contains( "JANNE-SAYS-HI" ), after );
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void use( final WebDriver driver ) {
        WebDriverRunner.setWebDriver( driver );
    }

    private static void awaitLive() {
        $( "[data-testid=collab-status]" ).shouldBe( visible, WAIT ).shouldHave( attribute( "data-status", "live" ), WAIT );
    }

    private static void awaitEditorsCount( final String label ) {
        $( ".collab-presence-count" ).shouldBe( visible, WAIT ).shouldHave( text( label ), WAIT );
    }

    /** Waits until both browsers hold the same document and it satisfies {@code condition}; returns it. */
    private String awaitBoth( final java.util.function.Predicate< String > condition ) {
        final long deadline = System.currentTimeMillis() + WAIT.toMillis();
        String a = "";
        String b = "";
        while ( System.currentTimeMillis() < deadline ) {
            use( janneBrowser );
            a = EditWikiPage.documentText();
            use( aliceBrowser );
            b = EditWikiPage.documentText();
            if ( a.equals( b ) && condition.test( a ) ) {
                return a;
            }
            Selenide.sleep( 150 );
        }
        throw new AssertionError( "the two editors did not converge on one document holding every edit within " + WAIT
            + "; janne has [" + a + "], Alice has [" + b + "]" );
    }

    /** Clears the signed-in (current) browser's server editor-mode preference, which {@code pinMode} set. */
    private static void clearEditorMode() {
        Selenide.executeAsyncJavaScript( """
            const cb = arguments[arguments.length - 1];
            fetch(arguments[0], { method: 'PUT',
              headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
              credentials: 'same-origin', body: JSON.stringify({ editorMode: null }) })
              .then(r => cb(r.status)).catch(e => cb(String(e)));
            """, Env.TESTS_BASE_URL + "/api/auth/profile" );
    }
}
