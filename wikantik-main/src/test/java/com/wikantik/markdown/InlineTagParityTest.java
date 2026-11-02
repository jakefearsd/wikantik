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
package com.wikantik.markdown;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.markdown.InlineTagSyntax;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's half of the inline {@code #tag} parity fixture ({@code inline-tags.json}): the editor preview
 * ({@code remarkInlineTags.test.jsx}) and the editor's scanner ({@code inlineTags.test.js}) run the same rows. A row with
 * {@code html} must render to exactly that; a {@code noTag} row must produce no {@code inline-tag} link; every row's
 * {@code tags} are what {@link InlineTagSyntax#normalisedTags} finds.
 */
class InlineTagParityTest {

    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "inline-tags.json" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    @Test
    void readerRendersEveryFixtureRowAsTheEditorPreviewDoes() throws Exception {
        final JsonArray rows = JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray();
        assertTrue( rows.size() > 15 );
        final List< String > mismatches = new ArrayList<>();
        for ( final JsonElement element : rows ) {
            final JsonObject row = element.getAsJsonObject();
            final String html = normalised( render( row.get( "markdown" ).getAsString() ) );
            if ( row.has( "html" ) ) {
                if ( !normalised( row.get( "html" ).getAsString() ).equals( html ) ) {
                    mismatches.add( row.get( "name" ).getAsString() + ": expected " + row.get( "html" ) + " but was " + html );
                }
            } else if ( html.contains( "inline-tag" ) ) {
                mismatches.add( row.get( "name" ).getAsString() + ": no tag expected but was " + html );
            }
        }
        assertEquals( List.of(), mismatches, String.join( "\n", mismatches ) );
    }

    @Test
    void theIndexersScanEveryFixtureRowToTheSameTags() throws Exception {
        final JsonArray rows = JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray();
        final List< String > mismatches = new ArrayList<>();
        for ( final JsonElement element : rows ) {
            final JsonObject row = element.getAsJsonObject();
            final List< String > expected = new ArrayList<>();
            row.getAsJsonArray( "tags" ).forEach( t -> expected.add( t.getAsString() ) );
            final List< String > actual = List.copyOf( InlineTagSyntax.normalisedTags( row.get( "markdown" ).getAsString() ) );
            if ( !expected.equals( actual ) ) {
                mismatches.add( row.get( "name" ).getAsString() + ": expected " + expected + " but was " + actual );
            }
        }
        assertEquals( List.of(), mismatches, String.join( "\n", mismatches ) );
    }

    @Test
    void onlyAnAtxHeadingLineIsSkippedLikeTheScanner() throws Exception {
        final String setext = "Title with #real\n================\n";
        assertTrue( render( setext ).contains( "inline-tag" ), "a setext heading's text is prose" );
        assertEquals( java.util.List.of( "real" ), List.copyOf( InlineTagSyntax.normalisedTags( setext ) ) );
        assertFalse( render( "## Title with #x\n" ).contains( "inline-tag" ) );
    }

    @Test
    void aTagInsideAnAccessRuleLineIsNeverTouched() throws Exception {
        final String html = render( "Prose #kept.\n[{ALLOW view Admin}]\n" );
        assertTrue( html.replace( "&#61;", "=" ).contains( "href=\"/search?tag=kept\"" ), html );
        assertFalse( html.contains( "ALLOW" ), html );
    }

    private static String normalised( final String html ) {
        return html.replace( "&#61;", "=" ).replaceAll( " data-block=\"\\d+\"", "" ).replaceAll( "<h([1-6]) id=\"[^\"]*\"", "<h$1" )
                .replaceAll( ">\\s+<", "><" ).strip();
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( "InlineTagHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "InlineTagHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }
}
