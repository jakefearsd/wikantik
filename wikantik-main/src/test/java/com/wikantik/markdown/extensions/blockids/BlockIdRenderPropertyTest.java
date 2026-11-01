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
package com.wikantik.markdown.extensions.blockids;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockTextGenerator;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agreement {@code BlockIdsRenderAgreementTest} proves on the text, checked against a real render: for generated
 * pages (access rules in every position, markers beside them, lists, quotes, code, math …) the engine's HTML stamps
 * every {@code BlockIds.list(raw)} index at most once as {@code data-block}, on an element of the block's kind, with the
 * block's {@code id="^id"}; only a paragraph may be unstamped (one in a tight list item renders as bare text). (Texts are compared on the text level, list(raw) against list(stripped), by
 * {@code BlockIdsRenderAgreementTest}: rendered HTML spells math, attributes and entities its own way.)
 */
class BlockIdRenderPropertyTest {

    private static final String HOST = "BlockIdRenderPropertyHost";

    /** Cases rendered: each is a full engine render, so a sample of the text-level property's. */
    private static final int CASES = 400;

    /** An opening tag carrying a {@code data-block}: tag name, attributes, ordinal. */
    private static final Pattern STAMPED = Pattern.compile( "<([a-z0-9]+)\\b([^>]*\\bdata-block=\"(\\d+)\"[^>]*)>" );
    private static final Pattern ID = Pattern.compile( "\\bid=\"(\\^[^\"]*)\"" );

    private static final Map< BlockIds.Kind, String > TAG = Map.of(
            BlockIds.Kind.PARAGRAPH, "p", BlockIds.Kind.LIST_ITEM, "li", BlockIds.Kind.TABLE, "table",
            BlockIds.Kind.QUOTE, "blockquote", BlockIds.Kind.CODE, "pre" );

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ),
            TestEngine.with( "wikantik.translatorReader.allowHTML", "true" ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    @Test
    void generatedPagesRenderTheOrdinalsAndIdsBlockIdsLists() throws Exception {
        testEngine.saveText( HOST, "Host.\n" );
        final Page page = Wiki.contents().page( testEngine, HOST );
        final RenderingManager renderer = testEngine.getManager( RenderingManager.class );
        final BlockTextGenerator generator = new BlockTextGenerator( 0x5EED1L );
        final List< String > problems = new ArrayList<>();
        int ids = 0;
        for ( int i = 0; i < CASES && problems.size() < 10; i++ ) {
            final String raw = generator.next();
            final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), page );
            final String html = renderer.textToHTML( context, raw );
            final List< Block > blocks = BlockIds.list( raw );
            ids += blocks.stream().anyMatch( b -> b.id() != null ) ? 1 : 0;
            final String why = disagreement( blocks, html );
            if ( why != null ) {
                problems.add( "#" + i + " " + esc( raw ) + " -> " + why + "\n   html " + html );
            }
        }
        assertEquals( List.of(), problems, String.join( "\n", problems ) );
        assertTrue( ids > CASES / 3, "too few rendered pages carry a block id: " + ids );
    }

    /** Why the stamped HTML disagrees with the listed blocks, or {@code null}. */
    private static String disagreement( final List< Block > blocks, final String html ) {
        final Map< Integer, String[] > stamped = new TreeMap<>();
        final Matcher m = STAMPED.matcher( html );
        while ( m.find() ) {
            final Matcher id = ID.matcher( m.group( 2 ) );
            if ( stamped.put( Integer.parseInt( m.group( 3 ) ), new String[]{ m.group( 1 ), id.find() ? id.group( 1 ) : null } ) != null ) {
                return "data-block " + m.group( 3 ) + " is stamped twice";
            }
        }
        if ( !stamped.isEmpty() && stamped.keySet().stream().mapToInt( k -> k ).max().getAsInt() >= blocks.size() ) {
            return "rendered ordinals " + stamped.keySet() + " for " + blocks.size() + " listed blocks";
        }
        for ( final Block b : blocks ) {
            final String[] r = stamped.get( b.index() );
            if ( r == null ) {
                // A paragraph of a tight list item renders as bare text, with no element to carry its ordinal; its
                // index is then absent and every later block keeps its own. Nothing else may be missing.
                if ( b.kind() != BlockIds.Kind.PARAGRAPH ) {
                    return "block " + b.index() + " (" + b.kind() + ") is not rendered";
                }
                continue;
            }
            final String expectedId = b.id() == null ? null : "^" + b.id();
            if ( !java.util.Objects.equals( expectedId, r[ 1 ] ) ) {
                return "block " + b.index() + " has id " + expectedId + " but renders " + r[ 1 ];
            }
            final String tag = TAG.get( b.kind() );
            if ( tag != null && !tag.equals( r[ 0 ] ) ) {
                return "block " + b.index() + " is a " + b.kind() + " but renders as <" + r[ 0 ] + ">";
            }
        }
        return null;
    }

    private static String esc( final String s ) {
        return s.replace( "\r", "\\r" ).replace( "\n", "\\n" ).replace( "\t", "\\t" ).replace( "\u000B", "\\v" )
                .replace( "\f", "\\f" );
    }
}
