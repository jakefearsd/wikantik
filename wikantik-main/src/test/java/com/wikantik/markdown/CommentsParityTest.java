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
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AccessRules;
import com.wikantik.auth.acl.AclDirectiveText;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code %%comments%%} end to end: the shared fixture ({@code comments.json}, also run by {@code obsidianComments.test.js}),
 * the rendered page, and the one rule that must NOT follow the display text — an access rule inside a comment is still read.
 */
class CommentsParityTest {

    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "comments.json" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    @Test
    void stripMatchesTheSharedFixture() throws Exception {
        final JsonArray rows = JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray();
        assertTrue( rows.size() > 10 );
        final List< String > mismatches = new ArrayList<>();
        for ( final JsonElement element : rows ) {
            final JsonObject row = element.getAsJsonObject();
            final String actual = com.wikantik.parser.markdown.ReaderComments.strip( row.get( "input" ).getAsString() );
            if ( !row.get( "output" ).getAsString().equals( actual ) ) {
                mismatches.add( row.get( "name" ).getAsString() + ": expected " + row.get( "output" ) + " but was \"" + actual + "\"" );
            }
        }
        assertEquals( List.of(), mismatches, String.join( "\n", mismatches ) );
    }

    @Test
    void aRenderedPageHasNoComment() throws Exception {
        final String html = render( "Shown %%hiddenprobe%% prose.\n\n%%a\nmulti-line\nhiddenprobe2%%\n\nAfter.\n" );
        assertTrue( html.contains( "Shown" ) && html.contains( "After." ), html );
        assertFalse( html.contains( "hiddenprobe" ), html );
    }

    @Test
    void codeAndMermaidKeepTheirPercentPairs() throws Exception {
        final String html = render( "`%%kept%%` text\n\n```mermaid\ngraph TD\n%% keptmermaid\nA-->B\n```\n" );
        assertTrue( html.contains( "%%kept%%" ), html );
        assertTrue( html.contains( "keptmermaid" ), html );
    }

    /**
     * Comments are stripped for display, index and render, never for the access-rule reader, which reads the stored
     * text. So a rule inside a comment would be enforced while it reads as disabled: it locks the page instead
     * ({@code IN_COMMENT}), and stays hidden from readers.
     */
    @Test
    void anAccessRuleInsideACommentLocksThePageAndIsNotDisplayed() throws Exception {
        final String text = "Public prose.\n%%[{ALLOW view Admin}] private note%%\nMore prose.\n";

        final AccessRules.Locked locked = assertInstanceOf( AccessRules.Locked.class, AccessRules.read( text ),
                "a commented-out rule must not silently take effect" );
        assertEquals( List.of( AccessRules.Shape.IN_COMMENT ), locked.reasons().stream().map( AccessRules.Reason::shape ).toList() );

        final String shown = com.wikantik.parser.markdown.ReaderComments.strip( AclDirectiveText.strip( text ) );
        assertFalse( shown.contains( "ALLOW" ) || shown.contains( "private note" ), shown );
        final String html = renderUnfiltered( text );
        assertFalse( html.contains( "ALLOW" ) || html.contains( "private note" ), html );
        assertTrue( html.contains( "Public prose." ) && html.contains( "More prose." ), html );
    }

    /** The save path refuses such a rule, so only a stored page (or an administrator's unchanged span) can hold one. */
    private String renderUnfiltered( final String markdown ) throws Exception {
        testEngine.saveTextUnfiltered( "CommentsHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "CommentsHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }

    private String render( final String markdown ) throws Exception {
        testEngine.saveText( "CommentsHost", markdown );
        final Page p = Wiki.contents().page( testEngine, "CommentsHost" );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }
}
