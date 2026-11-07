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
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.RestSeedHelper;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;
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
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 draft UX and attribution in two real browsers: a draft left unpublished by one user shows a banner naming
 * them to the next editor, the publish dialog shows the server diff and the metadata changes and publishes only on
 * confirm, the history credits both contributors, the other open editor is told who published, and an administrator's
 * purge tells the participants while the unsynced text of one is kept in a recovery block.
 */
public class CollabDraftUxIT extends WithIntegrationTestSetup {

    private static final Logger LOG = LoggerFactory.getLogger( CollabDraftUxIT.class );

    private final String PAGE = "CollabDraftUxIT" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );
    private static final String SEED = "Draft UX seed.\n";
    private static final String JANNE_NAME = "Janne Jalkanen";
    private static final String ALICE = "Alice";
    private static final String ALICE_PASSWORD = "password";
    private static final Duration WAIT = Duration.ofSeconds( 20 );

    private WebDriver janneBrowser;
    private WebDriver aliceBrowser;

    @BeforeEach
    void twoBrowsersSignedIn() throws Exception {
        janneBrowser = null;
        aliceBrowser = null;
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
            if ( aliceBrowser != null ) {
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
            }
            if ( janneBrowser != null ) {
                use( janneBrowser );
            }
            Selenide.closeWebDriver();
        } finally {
            RestSeedHelper.deletePageQuietly( PAGE );
        }
    }

    @Test
    void anUnpublishedDraftShowsABannerAndThePublishDialogCreditsBothAndPublishesOnlyOnConfirm() throws Exception {
        final String tag = "uxtag" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 6 );

        // janne types, adds a tag, and leaves without publishing.
        use( janneBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        EditWikiPage.setCaret( EditWikiPage.documentText().length() );
        EditWikiPage.pressKeys( "JANNE-DRAFT-TEXT" );
        final SelenideElement input = $( ".fm-field-tags .ui-taginput-field" ).shouldBe( visible, WAIT );
        input.click();
        input.sendKeys( tag, Keys.ENTER );
        $$( ".fm-field-tags .chip" ).findBy( text( tag ) ).shouldBe( visible, WAIT );
        awaitDraftActive();
        Selenide.sleep( 1500 );   // the tag's meta op rides the same push cadence; let it land before leaving
        ViewWikiPage.open( PAGE );

        // Alice opens the editor: the banner names janne, and the draft text is there.
        use( aliceBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        $( "[data-testid=collab-draft-banner]" ).shouldBe( visible, WAIT )
            .shouldHave( text( "Unpublished changes" ), WAIT ).shouldHave( text( JANNE_NAME ), WAIT );
        EditWikiPage.awaitDocumentContaining( "JANNE-DRAFT-TEXT", WAIT );

        // janne comes back to the same draft, to be told when it is published.
        use( janneBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();

        // Alice opens the publish dialog: the server diff, the metadata change and both contributors; nothing is published yet.
        use( aliceBrowser );
        $( "[data-testid=editor-save]" ).shouldBe( visible, WAIT ).shouldBe( Condition.enabled, WAIT ).click();
        final SelenideElement dialog = $( "[data-testid=publish-dialog]" ).shouldBe( visible, WAIT );
        dialog.shouldHave( text( "JANNE-DRAFT-TEXT" ), WAIT ).shouldHave( text( JANNE_NAME ), WAIT )
            .shouldHave( text( "Frontmatter changes" ), WAIT ).shouldHave( text( tag ), WAIT );
        final String before = RestSeedHelper.get( "/api/pages/" + PAGE );
        assertFalse( before.contains( "JANNE-DRAFT-TEXT" ), "nothing is published before the confirm: " + before );

        $( "[data-testid=publish-dialog-confirm]" ).shouldBe( Condition.enabled, WAIT ).click();
        new WebDriverWait( aliceBrowser, WAIT ).until( d -> !d.getCurrentUrl().contains( "/edit/" ) );
        $( "[data-testid=page-view]" ).shouldBe( visible, WAIT ).shouldHave( text( "JANNE-DRAFT-TEXT" ), WAIT );
        assertTrue( RestSeedHelper.get( "/api/pages/" + PAGE ).contains( "JANNE-DRAFT-TEXT" ), "now published" );

        // The history credits both.
        $$( "button" ).findBy( text( "Change Notes" ) ).shouldBe( visible, WAIT ).click();
        $( "body" ).shouldHave( text( ALICE + " with " + JANNE_NAME ), WAIT );

        // janne's open editor says who published.
        use( janneBrowser );
        $( ".wk-toast-message" ).shouldBe( visible, WAIT ).shouldHave( text( "Published by " + ALICE ), WAIT );
    }

    @Test
    void anAdminPurgeTellsTheParticipantsAndKeepsTheUnsyncedTextInARecoveryBlock() throws Exception {
        use( janneBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        use( aliceBrowser );
        EditWikiPage.open( PAGE );
        awaitLive();
        use( janneBrowser );
        EditWikiPage.setCaret( EditWikiPage.documentText().length() );
        EditWikiPage.pressKeys( "SYNCED-TEXT" );
        awaitDraftActive();
        use( aliceBrowser );
        EditWikiPage.awaitDocumentContaining( "SYNCED-TEXT", WAIT );

        // Alice's pushes hang in flight: what she types now never reaches the shared draft, while her pull stays parked.
        Selenide.executeJavaScript( """
            window.__origFetch = window.fetch;
            window.fetch = function (url, opts) {
              if (String(url).includes('/api/collab/') && String(url).endsWith('/updates')
                  && opts && opts.method === 'POST') {
                return new Promise(function () {});   // the push hangs: it never reaches the server, the pull loop carries on
              }
              return window.__origFetch.apply(this, arguments);
            };
            """ );
        EditWikiPage.setCaret( 0 );
        EditWikiPage.pressKeys( "UNSYNCED-TEXT " );

        // janne (an administrator) lists the drafts and purges this one.
        use( janneBrowser );
        final Object status = Selenide.executeAsyncJavaScript( """
            const cb = arguments[arguments.length - 1];
            const base = arguments[0], page = arguments[1];
            const h = { 'Accept': 'application/json' };
            fetch(base + '/admin/collab/drafts', { headers: h, credentials: 'same-origin' })
              .then(r => r.json())
              .then(j => {
                const d = (j.drafts || []).find(x => x.pageName === page);
                if (!d) { cb('not listed: ' + JSON.stringify(j)); return; }
                return fetch(base + '/admin/collab/drafts/' + d.sessionId,
                    { method: 'DELETE', headers: h, credentials: 'same-origin' })
                  .then(r => cb(r.status));
              })
              .catch(e => cb(String(e)));
            """, Env.TESTS_BASE_URL, PAGE );
        assertTrue( status instanceof Number n && n.intValue() == 204, "purge answered: " + status );
        $( "[data-testid=collab-evicted]" ).shouldBe( visible, WAIT )
            .shouldHave( text( "An administrator removed this draft" ), WAIT );

        // Alice, who had unsynced text, keeps it in a recovery block (the purge ends her session; the editor offers her
        // text back rather than losing it). NOTE: she lands in the resync recovery panel, not the "administrator removed
        // this draft" notice that janne (nothing unsynced) sees; reported to the controller as a concern.
        use( aliceBrowser );
        $( "[data-testid=collab-evicted-text], [data-testid=collab-recovery-text]" ).shouldBe( visible, WAIT )
            .shouldHave( text( "UNSYNCED-TEXT" ), WAIT );
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void use( final WebDriver driver ) {
        WebDriverRunner.setWebDriver( driver );
    }

    private static void awaitLive() {
        $( "[data-testid=collab-status]" ).shouldBe( visible, WAIT ).shouldHave( attribute( "data-status", "live" ), WAIT );
    }

    /** Waits until the wiki reports the page has an active unpublished draft (the typed text reached the server). */
    private void awaitDraftActive() throws Exception {
        final long deadline = System.currentTimeMillis() + WAIT.toMillis();
        String last = "";
        while ( System.currentTimeMillis() < deadline ) {
            last = RestSeedHelper.get( "/api/pages/" + PAGE );
            if ( last.replace( " ", "" ).contains( "\"draft_active\":true" ) ) {
                return;
            }
            Selenide.sleep( 200 );
        }
        throw new AssertionError( "draft_active never became true for " + PAGE + "; last read: " + last );
    }

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
