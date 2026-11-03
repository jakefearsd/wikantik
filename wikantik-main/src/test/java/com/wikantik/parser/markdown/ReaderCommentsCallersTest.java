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
package com.wikantik.parser.markdown;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server callers of {@link ReaderComments} on whole pages. */
class ReaderCommentsCallersTest {

    private final TestEngine testEngine = TestEngine.build(
            TestEngine.with( "wikantik.fileSystemProvider.pageDir", "./target/md-pageDir" ),
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        testEngine.stop();
    }

    private String render( final String page, final String markdown ) throws Exception {
        testEngine.saveText( page, markdown );
        final Page p = Wiki.contents().page( testEngine, page );
        final Context context = Wiki.context().create( testEngine, HttpMockFactory.createHttpRequest(), p );
        return testEngine.getManager( RenderingManager.class ).textToHTML( context, markdown );
    }

    @Test
    void aPercentInFrontmatterIsNoMarker() throws Exception {
        final String text = "---\nnote: '%%x'\n---\nbody %%hiddenprobe%% end";
        final String html = render( "CallersFrontmatter", text );
        assertTrue( html.contains( "body" ) && html.contains( "end" ), html );
        assertFalse( html.contains( "hiddenprobe" ), html );
        assertEquals( "%%x", FrontmatterParser.parse( text ).metadata().get( "note" ) );
        assertEquals( "%%x", FrontmatterParser.parse( testEngine.getManager( com.wikantik.api.managers.PageManager.class )
                .getPureText( "CallersFrontmatter", -1 ) ).metadata().get( "note" ) );
    }

    @Test
    void aBlockIdAfterACommentKeepsItsOrdinal() {
        for ( final String eol : List.of( "\n", "\r\n" ) ) {
            final String text = ( "One %%c%%\n\nTwo ^abc\n" ).replace( "\n", eol );
            final List< BlockIds.Block > blocks = BlockIds.list( text );
            assertEquals( 2, blocks.size(), eol );
            assertEquals( "abc", blocks.get( 1 ).id() );
            assertEquals( 1, blocks.get( 1 ).index() );
        }
    }

    /**
     * Raw-body parses one save of a page holding a rule, a comment and a code span costs. Measured: 6 before the
     * comment-stripped structure reused the comment ranges, 5 after, 4 once the comment ranges were read off the rule
     * structure's own parse. Among them: the rule structure (AccessRules, which also yields the comment ranges), the
     * comment-stripped structure the rule locking compares, and the save-path callers that read the body (block ids,
     * references, index). The render's own flexmark parse is not counted here.
     */
    @Test
    void parsesPerSave() throws Exception {
        final String text = "[{ALLOW view All}]\n\nIntro %%hiddenprobe%% text.\n\n- item ^abc\n\n`%%code%%` end\n";
        final long before = ReaderStructure.parses();
        testEngine.saveText( "CallersParses", text );
        final long perSave = ReaderStructure.parses() - before;
        assertTrue( perSave <= 4, "one save parsed the raw body " + perSave + " times" );
    }

}
