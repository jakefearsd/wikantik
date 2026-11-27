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
package com.wikantik.content;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mutation-testing follow-up for the rename path: link rewriting precision, the change note and author, edge guards. */
class DefaultPageRenamerMutationTest {

    private TestEngine engine;

    @BeforeEach
    void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterEach
    void stop() {
        engine.deleteQuietly( "MutOld", "MutNew", "MutRef", "MutSame" );
        engine.stop();
    }

    @ParameterizedTest
    @CsvSource( delimiter = '|', value = {
            "OldPage#frag|NewPage#frag",
            "OldPage/file.png|NewPage/file.png",
            "OldPage/file.png#frag|NewPage/file.png#frag",
            "OldPage/dir/file.png|NewPage/dir/file.png",
            "OldPage#a/b|NewPage#a/b",
            "OtherPage#frag|OtherPage#frag",
            "OldPage|NewPage" } )
    void markdownLinkTargetsKeepTheirAnchorAndAttachmentPathExactlyOnce( final String target, final String expected ) {
        assertEquals( "[x](" + expected + ")",
                DefaultPageRenamer.rewriteMarkdownLinks( "[x](" + target + ")", "OldPage", "NewPage" ) );
    }

    @Test
    void renameChangeNoteAndAuthorAreRecordedOnTheMovedPageAndOnARewrittenReferrer() throws Exception {
        engine.saveText( "MutOld", "body" );
        engine.saveText( "MutRef", "see [[MutOld]]" );
        final PageManager pages = engine.getManager( PageManager.class );
        final Context ctx = Wiki.context().create( engine, pages.getPage( "MutOld" ) );

        engine.getManager( PageRenamer.class ).renamePage( ctx, "MutOld", "MutNew", true );

        assertEquals( "MutOld ==> MutNew", pages.getPage( "MutNew" ).getAttribute( Page.CHANGENOTE ) );
        assertEquals( "MutOld ==> MutNew", pages.getPage( "MutRef" ).getAttribute( Page.CHANGENOTE ) );
        assertEquals( AuthorKeys.authorKeyFor( ctx.getWikiSession() ), pages.getPage( "MutRef" ).getAuthor() );
        assertEquals( AuthorKeys.authorKeyFor( ctx.getWikiSession() ), pages.getPage( "MutNew" ).getAuthor() );
        assertEquals( "body", pages.getPageText( "MutNew", PageProvider.LATEST_VERSION ).trim() );
        assertTrue( pages.getPageText( "MutRef", PageProvider.LATEST_VERSION ).contains( "[[MutNew]]" ) );
    }

    @Test
    void aCamelCaseMentionAtTheVeryStartOfAReferrerIsRenamed() throws Exception {
        final TestEngine camel = TestEngine.build( TestEngine.with( "wikantik.translatorReader.camelCaseLinks", "true" ) );
        try {
            camel.saveText( "OldCamelPg", "body" );
            camel.saveText( "CamelRefPg", "OldCamelPg opens, [[OldCamelPg]] links, [x OldCamelPg" );
            final PageManager pages = camel.getManager( PageManager.class );
            final Context ctx = Wiki.context().create( camel, pages.getPage( "OldCamelPg" ) );

            camel.getManager( PageRenamer.class ).renamePage( ctx, "OldCamelPg", "NewCamelPg", true );

            assertEquals( "NewCamelPg opens, [[NewCamelPg]] links, [x OldCamelPg",
                    pages.getPageText( "CamelRefPg", PageProvider.LATEST_VERSION ).trim() );
        } finally {
            camel.deleteQuietly( "OldCamelPg", "NewCamelPg", "CamelRefPg" );
            camel.stop();
        }
    }

    @Test
    void renameWithoutReferrerChangeLeavesReferrersUntouched() throws Exception {
        engine.saveText( "MutOld", "body" );
        engine.saveText( "MutRef", "see [[MutOld]]" );
        final PageManager pages = engine.getManager( PageManager.class );
        final Context ctx = Wiki.context().create( engine, pages.getPage( "MutOld" ) );

        engine.getManager( PageRenamer.class ).renamePage( ctx, "MutOld", "MutNew", false );

        assertEquals( "see [[MutOld]]", pages.getPageText( "MutRef", PageProvider.LATEST_VERSION ).trim() );
        assertFalse( pages.pageExists( "MutOld" ) );
    }

    @Test
    void renameToNullOrEmptyOrSelfThrowsAndChangesNothing() throws Exception {
        engine.saveText( "MutSame", "x" );
        final PageManager pages = engine.getManager( PageManager.class );
        final Context ctx = Wiki.context().create( engine, pages.getPage( "MutSame" ) );
        final PageRenamer renamer = engine.getManager( PageRenamer.class );

        assertThrows( WikiException.class, () -> renamer.renamePage( ctx, null, "A", false ) );
        assertThrows( WikiException.class, () -> renamer.renamePage( ctx, "MutSame", null, false ) );
        assertThrows( WikiException.class, () -> renamer.renamePage( ctx, "MutSame", "MutSame", false ) );
        assertTrue( pages.pageExists( "MutSame" ) );
    }
}
