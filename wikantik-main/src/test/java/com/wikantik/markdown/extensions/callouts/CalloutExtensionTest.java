package com.wikantik.markdown.extensions.callouts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.wikantik.parser.markdown.MarkdownDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CalloutExtensionTest {
    private static final MutableDataSet OPTIONS = MarkdownDocument.structuralOptions();
    private static final Parser PARSER = Parser.builder( OPTIONS ).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder( OPTIONS ).build();

    @Test
    void matchesTheSharedFrontendFixture() throws Exception {
        final JsonArray cases = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "callouts.json" ) ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        for ( final JsonElement e : cases ) {
            final JsonObject c = e.getAsJsonObject();
            final Document doc = PARSER.parse( c.get( "markdown" ).getAsString() );
            assertEquals( c.getAsJsonArray( "expected" ), describe( topLevel( doc ) ), c.get( "name" ).getAsString() );
        }
    }

    private static List< CalloutBlock > topLevel( final Node root ) {
        final List< CalloutBlock > out = new ArrayList<>();
        for ( final Node n : root.getDescendants() ) {
            if ( n instanceof CalloutBlock cb && nearestCallout( cb.getParent() ) == nearestCallout( root ) ) {
                out.add( cb );
            }
        }
        return out;
    }

    private static Node nearestCallout( final Node n ) {
        for ( Node p = n; p != null; p = p.getParent() ) {
            if ( p instanceof CalloutBlock ) return p;
        }
        return null;
    }

    private static JsonArray describe( final List< CalloutBlock > callouts ) {
        final JsonArray arr = new JsonArray();
        for ( final CalloutBlock cb : callouts ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "tag", cb.fold() == CalloutBlock.Fold.NONE ? "div" : "details" );
            o.addProperty( "style", cb.style() );
            o.addProperty( "open", cb.fold() == CalloutBlock.Fold.EXPANDED );
            o.addProperty( "title", cb.titleText() );
            final JsonArray tags = new JsonArray();
            cb.titleTagNames().forEach( tags::add );
            o.add( "titleTags", tags );
            o.addProperty( "content", cb.contentTextExcludingNested() );
            o.add( "children", describe( topLevel( cb ) ) );
            arr.add( o );
        }
        return arr;
    }

    @Test
    void rendersTheDocumentedHtmlShape() {
        final String html = RENDERER.render( PARSER.parse( "> [!warning] Watch **out**\n> Careful.\n" ) );
        assertTrue( html.contains( "<div class=\"callout callout-warning\" data-callout=\"warning\">" ), html );
        assertTrue( html.contains( "<div class=\"callout-title\"><span class=\"callout-icon\" aria-hidden=\"true\"></span>"
                + "<span class=\"callout-title-inner\">Watch <strong>out</strong></span></div>" ), html );
        assertTrue( html.contains( "<div class=\"callout-content\">" ) && html.contains( "<p>Careful.</p>" ), html );
        assertFalse( html.contains( "<blockquote" ), html );
    }

    @Test
    void foldedCalloutsUseDetailsAndSummary() {
        final String collapsed = RENDERER.render( PARSER.parse( "> [!tip]- Hidden\n> Inside.\n" ) );
        assertTrue( collapsed.contains( "<details class=\"callout callout-tip\" data-callout=\"tip\">" ), collapsed );
        assertTrue( collapsed.contains( "<summary class=\"callout-title\">" ), collapsed );
        final String expanded = RENDERER.render( PARSER.parse( "> [!tip]+ Shown\n> Inside.\n" ) );
        assertTrue( expanded.contains( "<details class=\"callout callout-tip\" data-callout=\"tip\" open=\"\">" ), expanded );
    }

    @Test
    void escapesTheTypeIntoTheAttribute() {
        final String html = RENDERER.render( PARSER.parse( "> [!no\"te]\n> x\n" ) );
        assertFalse( html.contains( "data-callout=\"no\"te\"" ), html );
    }

    @Test
    void typeVocabulary() {
        assertEquals( "question", CalloutTypes.styleOf( "FAQ" ) );
        assertEquals( "danger", CalloutTypes.styleOf( "error" ) );
        assertEquals( "note", CalloutTypes.styleOf( "recipe" ) );
        assertEquals( "Recipe", CalloutTypes.defaultTitle( "recipe" ) );
        assertEquals( "WARNING", CalloutTypes.defaultTitle( "WARNING" ) );
    }
}
