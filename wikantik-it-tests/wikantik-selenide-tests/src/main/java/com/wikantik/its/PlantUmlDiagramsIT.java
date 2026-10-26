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
import com.wikantik.its.environment.Env;
import com.wikantik.its.environment.PlatformKeys;
import com.wikantik.pages.spa.EditWikiPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.Keys;

import java.time.Duration;
import java.util.List;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser coverage for every PlantUML diagram type the sandbox can draw, and every refusal it can make. The
 * matrix was established empirically with {@code bin/check-plantuml.sh}: the policy accepts only
 * {@code @startuml ... @enduml}, layout is Smetana, and the stdlib is stripped to C4. Each drawn type is
 * identified by PlantUML's own {@code data-diagram-type} on the SVG plus labels unique to its source, so an error
 * image (which carries "Syntax Error" text, and is in any case refused rather than drawn) can never pass.
 *
 * <p>Pages hold at most four diagrams each: the page time budget ({@code wikantik.fences.plantuml.pageBudgetMs},
 * 10 s) covers a cold render of every diagram on the page.
 */
public class PlantUmlDiagramsIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 60 );
    private static final long TS = System.currentTimeMillis();
    private static final String STRUCT = "PumlStruct" + TS;
    private static final String BEHAVE = "PumlBehave" + TS;
    private static final String MODEL = "PumlModel" + TS;
    private static final String TEXTY = "PumlTexty" + TS;
    private static final String REFUSED = "PumlRefused" + TS;
    private static final String WIDE = "PumlWide" + TS;
    private static final String LINK_TARGET = "PumlLinkTarget" + TS;
    private static final String LINK = "PumlLink" + TS;
    private static final String EDIT = "PumlEdit" + TS;

    private static final String FIGURE = "figure.wk-fence[data-fence=plantuml]";
    private static final String LIGHT = ".wk-fence-variant[data-variant=light]";
    private static final String DARK = ".wk-fence-variant[data-variant=dark]";

    private static String fence( final String body ) {
        return "```plantuml\n@startuml\n" + body + "\n@enduml\n```\n\n";
    }

    @BeforeAll
    static void seed() throws Exception {
        RestSeedHelper.writePage( STRUCT, "# " + STRUCT + "\n\n"
            + fence( "package ClsPkg {\n  interface ClsShape\n  abstract class ClsBase<T> {\n    +id: int\n  }\n"
                + "  class ClsCircle\n  enum ClsColor {\n    RED\n    GREEN\n  }\n}\n"
                + "ClsShape <|.. ClsCircle\nClsBase <|-- ClsCircle\nClsCircle *-- ClsColor" )
            + fence( "object ObjAlpha {\n  name = \"a\"\n}\nobject ObjBeta\nobject ObjGamma\n"
                + "ObjAlpha --> ObjBeta\nObjBeta --> ObjGamma" )
            + fence( "interface CmpApi\n[CmpWeb] as W\n[CmpCore] as C\ncomponent CmpDb {\n  portin CmpIn\n}\n"
                + "W --> CmpApi\nCmpApi - C\nC --> CmpIn" )
            + fence( "node DepServer {\n  artifact DepWar\n}\ndatabase DepPg\ncloud DepCdn\n"
                + "DepCdn --> DepServer\nDepServer --> DepPg" ) );
        RestSeedHelper.writePage( BEHAVE, "# " + BEHAVE + "\n\n"
            + fence( "actor Alice\nparticipant \"SeqServer\" as S\ndatabase SeqDb\nAlice -> S: SeqRequest\nactivate S\n"
                + "alt SeqOk\n  S -> SeqDb: query\n  SeqDb --> S: rows\nelse SeqFail\n  S --> Alice: error\nend\n"
                + "loop 3 times\n  Alice -> S: SeqPing\nend\nnote right of S: SeqNote\ndeactivate S" )
            + fence( "|ActLaneA|\nstart\n:ActFirst;\nif (ActCond?) then (yes)\n  :ActYes;\nelse (no)\n  :ActNo;\nendif\n"
                + "|ActLaneB|\nwhile (ActMore?)\n  :ActLoop;\nendwhile\npartition ActPart {\n  :ActInPart;\n}\n"
                + "fork\n  :ActF1;\nfork again\n  :ActF2;\nend fork\nstop" )
            + fence( "(*) --> \"LegFirst\"\n\"LegFirst\" --> \"LegSecond\"\nif \"LegCheck\" then\n  -->[true] \"LegThen\"\n"
                + "else\n  -->[false] \"LegElse\"\nendif\n\"LegThen\" --> (*)\n\"LegElse\" --> (*)" )
            + fence( "[*] --> StaIdle\nstate StaWork {\n  [*] --> StaSub1\n  StaSub1 --> StaSub2\n  --\n  [*] --> StaPar1\n}\n"
                + "state StaChoice <<choice>>\nStaIdle --> StaWork\nStaWork --> StaChoice\nStaChoice --> StaDone : ok\n"
                + "StaDone --> [H]" ) );
        RestSeedHelper.writePage( MODEL, "# " + MODEL + "\n\n"
            + fence( "actor UcCustomer\nactor UcClerk\nrectangle UcShop {\n  usecase \"UcCheckout\" as U1\n"
                + "  usecase \"UcRefund\" as U2\n  UcCustomer --> U1\n  UcClerk --> U2\n  U1 .> U2 : include\n}" )
            + fence( "robust \"TimWeb\" as W\nconcise \"TimUser\" as U\n@0\nW is Idle\nU is Waiting\n@100\nW is Busy\n"
                + "U is Active\n@300\nW is Idle" )
            + fence( "!include <C4/C4_Container>\nPerson(c4user, \"C4User\")\nSystem_Boundary(c4b, \"C4Boundary\") {\n"
                + "  Container(c4web, \"C4Web\", \"Java\")\n  ContainerDb(c4db, \"C4Db\", \"Postgres\")\n}\n"
                + "Rel(c4user, c4web, \"uses\")\nRel(c4web, c4db, \"reads\")" )
            + fence( "archimate #Business \"ArcActor\" as a <<business-actor>>\n"
                + "archimate #Business \"ArcRole\" as r <<business-role>>\na -up-> r" ) );
        RestSeedHelper.writePage( TEXTY, "# " + TEXTY + "\n\n"
            + fence( "json JsonDoc {\n  \"name\": \"JsonAlpha\",\n  \"items\": [1, 2, 3],\n"
                + "  \"nested\": {\"k\": \"JsonBeta\"}\n}" )
            + fence( "class CreoleCls {\n  **bold** //italic// --strike--\n  <color:red>CreoleRed</color>\n}\n"
                + "note right of CreoleCls\n  == CreoleHead ==\n  * item\nend note" )
            + fence( "sprite $foo {\n  FFFFFFFFFFFFFFF\n  F0123456789ABCF\n  FFFFFFFFFFFFFFF\n}\n"
                + "class SpriteCls <<$foo>>\nSpriteCls --> SpriteOther" )
            + fence( "entity ChenCust {\n  ChenId\n}\nentity ChenOrder\nChenCust -- ChenOrder" ) );
        // Refusals, in order: see REFUSALS below.
        RestSeedHelper.writePage( REFUSED, "# " + REFUSED + "\n\n"
            + "```plantuml\n@startditaa\n+--+\n|Dt|\n+--+\n@endditaa\n```\n\n"
            + "```plantuml\n@startmindmap\n* MmRoot\n** MmChild\n@endmindmap\n```\n\n"
            + fence( "version" )
            + fence( "A -> B\n@enduml\n@startuml\nC -> D" )
            + fence( "note right\n  {{\n  A -> B\n  }}\nend note\nA -> B" )
            + fence( "!include foo.puml\nA -> B" )
            + fence( "!theme plain from https://example.com/themes\nA -> B" )
            + fence( "!pragma layout elk\nA -> B" )
            + fence( "title %getenv(\"HOME\")\nA -> B" )
            + fence( "A -> B : <img:http://example.com/x.png>" )
            + fence( "class RefA [[javascript:alert(1)]]\nRefA --> RefB" )
            + fence( "this is not valid plantuml at all ???\nfoo bar baz" ) );
        final StringBuilder wide = new StringBuilder();
        for ( int i = 0; i < 8; i++ ) {
            wide.append( "participant \"WideParticipantNumber" ).append( i ).append( "\" as P" ).append( i ).append( '\n' );
        }
        for ( int i = 0; i < 7; i++ ) {
            wide.append( 'P' ).append( i ).append( " -> P" ).append( i + 1 ).append( ": WideMessage" ).append( i ).append( '\n' );
        }
        RestSeedHelper.writePage( WIDE, "# " + WIDE + "\n\n" + fence( wide.toString().stripTrailing() ) );
        RestSeedHelper.writePage( LINK_TARGET, "# " + LINK_TARGET + "\n\nThe diagram link target.\n" );
        RestSeedHelper.writePage( LINK, "# " + LINK + "\n\n"
            + fence( "class LnkA [[/wiki/" + LINK_TARGET + "]]\nclass LnkB\nLnkA --> LnkB\nLnkB --> LnkC" ) );
        RestSeedHelper.writePage( EDIT, "# " + EDIT + "\n\n" + fence( "class EditOriginal\nclass EditPeer\nEditOriginal --> EditPeer" ) );
    }

    @AfterAll
    static void cleanUp() {
        for ( final String n : new String[] { STRUCT, BEHAVE, MODEL, TEXTY, REFUSED, WIDE, LINK, LINK_TARGET, EDIT } ) {
            RestSeedHelper.deletePageQuietly( n );
        }
    }

    // ---- drawn diagram types -------------------------------------------------------------------------------

    @Test
    void classDiagramDraws() {
        assertDrawn( STRUCT, 0, "CLASS", "ClsPkg", "ClsShape", "ClsBase", "ClsCircle", "ClsColor", "RED", "GREEN" );
    }

    @Test
    void objectDiagramDraws() {
        assertDrawn( STRUCT, 1, "CLASS", "ObjAlpha", "ObjBeta", "ObjGamma", "name = \"a\"" );
    }

    @Test
    void componentDiagramDraws() {
        assertDrawn( STRUCT, 2, "DESCRIPTION", "CmpDb", "CmpApi", "CmpWeb", "CmpCore", "CmpIn" );
    }

    @Test
    void deploymentDiagramDraws() {
        assertDrawn( STRUCT, 3, "DESCRIPTION", "DepServer", "DepWar", "DepPg", "DepCdn" );
    }

    @Test
    void sequenceDiagramDraws() {
        assertDrawn( BEHAVE, 0, "SEQUENCE", "SeqServer", "SeqDb", "SeqRequest", "SeqOk", "SeqFail", "SeqPing", "SeqNote" );
    }

    @Test
    void activityDiagramNewSyntaxDraws() {
        assertDrawn( BEHAVE, 1, "ACTIVITY", "ActLaneA", "ActLaneB", "ActFirst", "ActCond?", "ActYes", "ActNo", "ActLoop",
            "ActPart", "ActF1", "ActF2" );
    }

    @Test
    void activityDiagramLegacySyntaxDraws() {
        assertDrawn( BEHAVE, 2, "ACTIVITY", "LegFirst", "LegSecond", "LegCheck", "LegThen", "LegElse" );
    }

    @Test
    void stateDiagramDraws() {
        assertDrawn( BEHAVE, 3, "STATE", "StaWork", "StaSub1", "StaSub2", "StaPar1", "StaIdle", "StaDone" );
    }

    @Test
    void useCaseDiagramDraws() {
        assertDrawn( MODEL, 0, "DESCRIPTION", "UcShop", "UcCustomer", "UcClerk", "UcCheckout", "UcRefund" );
    }

    @Test
    void timingDiagramDraws() {
        assertDrawn( MODEL, 1, "TIMING", "TimWeb", "TimUser", "Idle", "Busy", "Waiting", "Active" );
    }

    @Test
    void c4DiagramDraws() {
        assertDrawn( MODEL, 2, "DESCRIPTION", "C4Boundary", "C4User", "C4Web", "C4Db", "Postgres" );
    }

    @Test
    void archimateDiagramDraws() {
        assertDrawn( MODEL, 3, "DESCRIPTION", "ArcActor", "ArcRole" );
    }

    @Test
    void jsonObjectDraws() {
        assertDrawn( TEXTY, 0, "CLASS", "JsonDoc", "JsonAlpha", "JsonBeta" );
    }

    @Test
    void creoleMarkupDraws() {
        assertDrawn( TEXTY, 1, "CLASS", "CreoleCls", "bold", "italic", "strike", "CreoleRed", "CreoleHead" );
    }

    @Test
    void spriteStereotypeDraws() {
        assertDrawn( TEXTY, 2, "CLASS", "SpriteCls", "SpriteOther" );
    }

    @Test
    void entityRelationshipDraws() {
        assertDrawn( TEXTY, 3, "CLASS", "ChenCust", "ChenId", "ChenOrder" );
    }

    /**
     * Opens {@code page}, waits for the {@code index}-th PlantUML figure to be drawn and checks: both colour
     * variants hold one SVG, the light one is visible with a non-empty box, PlantUML's own diagram type and the
     * source's labels are present in both, no error markup, and nothing active in the inline SVG.
     */
    private static void assertDrawn( final String page, final int index, final String diagramType, final String... labels ) {
        ViewWikiPage.open( page );
        final SelenideElement fig = $$( FIGURE ).get( index );
        fig.shouldHave( Condition.attribute( "data-fence-state", "drawn" ), WAIT );
        fig.$$( ".wk-fence-variant" ).shouldHave( CollectionCondition.size( 2 ) );
        fig.$$( LIGHT + " svg" ).shouldHave( CollectionCondition.size( 1 ) );
        fig.$$( DARK + " svg" ).shouldHave( CollectionCondition.size( 1 ) );
        final SelenideElement light = fig.$( LIGHT + " svg" );
        light.shouldBe( Condition.visible );
        fig.$( ".wk-fence-error" ).shouldNot( Condition.exist );

        final List< ? > box = Selenide.executeJavaScript(
            "const r = arguments[0].getBoundingClientRect(); return [r.width, r.height];", light );
        assertTrue( ( ( Number ) box.get( 0 ) ).doubleValue() > 20 && ( ( Number ) box.get( 1 ) ).doubleValue() > 20,
            "light SVG must have a real box, was " + box );

        for ( final String variant : new String[] { LIGHT, DARK } ) {
            final SelenideElement svg = fig.$( variant + " svg" );
            assertEquals( diagramType, svg.getAttribute( "data-diagram-type" ), variant + " diagram type" );
            final String text = String.valueOf( js( "return arguments[0].textContent;", svg ) );
            for ( final String label : labels ) {
                assertTrue( text.contains( label ), variant + " must contain '" + label + "' but was: " + text );
            }
            // What PlantUML puts in the image when it fails; a drawn figure must never carry it.
            for ( final String error : new String[] { "Syntax Error", "has crashed", "Welcome to PlantUML" } ) {
                assertFalse( text.contains( error ), variant + " must not be an error image: " + text );
            }
            assertNothingActive( svg );
        }
    }

    private static Object js( final String script, final Object... args ) {
        return Selenide.< Object >executeJavaScript( script, args );
    }

    private static void assertNothingActive( final SelenideElement svg ) {
        final Object active = Selenide.executeJavaScript(
            "const svg = arguments[0];"
            + "const bad = [...svg.querySelectorAll('script, foreignObject, iframe, object, embed')].map(e => e.localName);"
            + "[svg, ...svg.querySelectorAll('*')].forEach(el => [...el.attributes].forEach(a => {"
            + "  if (/^on/i.test(a.name) || /^\\s*javascript:/i.test(a.value)) bad.push(el.localName + '@' + a.name);"
            + "}));"
            + "return bad.join(',');", svg );
        assertEquals( "", String.valueOf( active ), "inline SVG must hold no script, foreignObject or handler" );
    }

    // ---- refusals --------------------------------------------------------------------------------------------

    @ParameterizedTest( name = "{1}" )
    @CsvSource( delimiter = '|', value = {
        "0|unsupported-diagram|only PlantUML class, sequence, activity and similar @startuml diagrams are supported.|@startditaa",
        "1|unsupported-diagram|only PlantUML class, sequence, activity and similar @startuml diagrams are supported.|@startmindmap",
        "2|unsupported-diagram|only PlantUML class, sequence, activity and similar @startuml diagrams are supported.|version",
        "3|multiple-diagrams|it contains more than one diagram.|C -> D",
        "4|embedded-diagram|it embeds another diagram|{{",
        "5|include|it includes another file or URL.|!include foo.puml",
        "6|include|it includes another file or URL.|!theme plain from",
        "7|layout-pragma|it selects a layout engine, which wiki diagrams may not do.|!pragma layout elk",
        "8|file-access|it reads a file.|%getenv",
        "9|image|it embeds an image.|<img:http://example.com/x.png>",
        "10|unsafe-link|it links to a target that is not a wiki page or an http(s) URL.|javascript:alert(1)",
        "11|syntax-error|it has a syntax error.|not valid plantuml"
    } )
    void refusalShowsReasonAndKeepsSource( final int index, final String reason, final String text, final String source ) {
        ViewWikiPage.open( REFUSED );
        final SelenideElement fig = $$( FIGURE ).get( index );
        fig.shouldHave( Condition.attribute( "data-fence-error", reason ), WAIT );
        fig.shouldHave( Condition.attribute( "data-fence-state", "refused" ), WAIT );
        fig.$( ".wk-fence-error" ).shouldBe( Condition.visible, WAIT ).shouldHave( Condition.text( text ) );
        fig.$( ".wk-fence-source" ).shouldBe( Condition.visible, WAIT )
            .shouldHave( Condition.attributeMatching( "textContent", "(?s).*" + java.util.regex.Pattern.quote( source ) + ".*" ) );
        fig.$$( "svg" ).shouldHave( CollectionCondition.size( 0 ) );
    }

    @Test
    void syntaxErrorNamesTheLine() {
        ViewWikiPage.open( REFUSED );
        final SelenideElement fig = $$( FIGURE ).get( 11 );
        fig.$( ".wk-fence-error" ).shouldBe( Condition.visible, WAIT ).shouldHave( Condition.text( "line" ) );
    }

    // ---- toolbar, links, editor ------------------------------------------------------------------------------

    @Test
    void showSourceTogglesTheSourceOfADrawnDiagram() {
        ViewWikiPage.open( WIDE );
        final SelenideElement fig = $$( FIGURE ).get( 0 );
        fig.shouldHave( Condition.attribute( "data-fence-state", "drawn" ), WAIT );
        final SelenideElement source = fig.$( ".wk-fence-source" );
        source.shouldNotBe( Condition.visible );
        final SelenideElement toggle = fig.$( ".wk-fence-toolbar [data-action=source]" );
        toggle.shouldBe( Condition.visible, WAIT ).shouldHave( Condition.text( "Show source" ) ).click();
        source.shouldBe( Condition.visible, WAIT )
            .shouldHave( Condition.attributeMatching( "textContent", "(?s).*WideMessage3.*" ) );
        toggle.shouldHave( Condition.text( "Hide source" ) ).click();
        source.shouldNotBe( Condition.visible, WAIT );
    }

    @Test
    void expandDialogHoldsAClonedSvgWithUniqueIds() {
        final org.openqa.selenium.Dimension original = WebDriverRunner.getWebDriver().manage().window().getSize();
        try {
            // A narrow window scales the wide diagram down, which is what reveals the Expand button.
            WebDriverRunner.getWebDriver().manage().window().setSize( new org.openqa.selenium.Dimension( 700, 900 ) );
            ViewWikiPage.open( WIDE );
            final SelenideElement fig = $$( FIGURE ).get( 0 );
            fig.shouldHave( Condition.attribute( "data-fence-state", "drawn" ), WAIT );
            assertEquals( "", duplicateIds(), "ids must be unique before Expand" );
            fig.$( ".wk-fence-toolbar [data-action=expand]" ).shouldBe( Condition.visible, WAIT ).click();
            $( "dialog.wk-fence-dialog" ).shouldBe( Condition.visible, WAIT );
            final SelenideElement clone = $( "dialog.wk-fence-dialog svg" ).shouldBe( Condition.visible );
            final String text = String.valueOf( js( "return arguments[0].textContent;", clone ) );
            assertTrue( text.contains( "WideParticipantNumber7" ) && text.contains( "WideMessage6" ), text );
            assertEquals( "", duplicateIds(), "ids must stay unique with the Expand dialog open" );
            $( "dialog.wk-fence-dialog .wk-fence-dialog-close" ).click();
            $( "dialog.wk-fence-dialog" ).shouldNot( Condition.exist, WAIT );
        } finally {
            WebDriverRunner.getWebDriver().manage().window().setSize( original );
        }
    }

    private static String duplicateIds() {
        return String.valueOf( js(
            "const seen = new Set(), dup = new Set();"
            + "document.querySelectorAll('[id]').forEach(e => { if (seen.has(e.id)) dup.add(e.id); seen.add(e.id); });"
            + "return [...dup].join(',');" ) );
    }

    @Test
    void diagramLinkToAWikiPageNavigates() {
        ViewWikiPage.open( LINK );
        final SelenideElement anchor = $( FIGURE + "[data-fence-state=drawn] " + LIGHT + " svg a" );
        anchor.should( Condition.exist, WAIT );
        final String href = String.valueOf( js(
            "return arguments[0].getAttribute('href') || arguments[0].getAttribute('xlink:href');", anchor ) );
        assertTrue( href.endsWith( "/wiki/" + LINK_TARGET ), "link must point at the target page: " + href );
        Selenide.executeJavaScript(
            "arguments[0].dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));", anchor );
        Selenide.Wait().withTimeout( WAIT ).until( d -> WebDriverRunner.url().endsWith( "/wiki/" + LINK_TARGET ) );
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, WAIT );
    }

    @Test
    void editingADiagramRedrawsThePreview() {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        EditWikiPage.open( EDIT );
        final String drawn = ".editor-preview " + FIGURE + "[data-fence-state=drawn] " + LIGHT + " svg";
        $( drawn ).shouldBe( Condition.exist, WAIT ).shouldHave( Condition.attributeMatching( "textContent", "(?s).*EditOriginal.*" ) );

        final SelenideElement cm = $( "[data-testid=editor-textarea] .cm-content" ).shouldBe( Condition.visible );
        cm.click();
        $( "[data-testid=editor-textarea] .cm-editor" ).shouldHave( Condition.cssClass( "cm-focused" ), Duration.ofSeconds( 5 ) );
        cm.sendKeys( PlatformKeys.selectAll() );
        cm.sendKeys( Keys.DELETE );
        cm.shouldHave( Condition.exactText( "" ), Duration.ofSeconds( 5 ) );
        cm.sendKeys( "```plantuml\n@startuml\nclass EditRenamed\nclass EditPeer\nEditRenamed --> EditPeer\n@enduml\n```\n" );

        // The preview must settle on the new label alone; the old drawing is replaced, not appended to.
        $( drawn ).shouldHave( Condition.attributeMatching( "textContent", "(?s).*EditRenamed.*" ), WAIT );
        $( drawn ).shouldNotHave( Condition.attributeMatching( "textContent", "(?s).*EditOriginal.*" ) );
        $$( ".editor-preview " + FIGURE + " " + LIGHT + " svg" ).shouldHave( CollectionCondition.size( 1 ) );
        $( ".editor-preview " + FIGURE + " .wk-fence-error" ).shouldNot( Condition.exist );
    }
}
