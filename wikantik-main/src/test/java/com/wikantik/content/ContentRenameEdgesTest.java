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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mutation-testing follow-up: the small helpers of the rename path and the block-link boundaries. */
class ContentRenameEdgesTest {

    private TestEngine engine;

    @BeforeEach
    void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterEach
    void stop() {
        engine.stop();
    }

    // ---- RenameRenderEviction ------------------------------------------------------------------------------------

    @Test
    void everyNameAndReferrerOfARenameIsEvictedFromTheRenderCache() {
        final RenderingManager rm = mock( RenderingManager.class );
        final RenderingManager original = engine.getManager( RenderingManager.class );
        engine.setManager( RenderingManager.class, rm );
        try {
            RenameRenderEviction.evict( engine, "Old", "New", Set.of( "RefA", "Shared" ), Set.of( "RefB", "Shared" ) );
        } finally {
            engine.setManager( RenderingManager.class, original );
        }
        for( final String name : List.of( "Old", "New", "RefA", "RefB", "Shared" ) ) {
            verify( rm ).evictRenderCache( name );
        }
        verify( rm, Mockito.times( 5 ) ).evictRenderCache( anyString() );
    }

    @Test
    void evictionWithoutARenderingManagerDoesNothing() {
        final Engine notAWikiEngine = mock( Engine.class );
        RenameRenderEviction.evict( notAWikiEngine, "Old", "New", Set.of( "A" ), Set.of() );
        assertEquals( Set.of(), RenameRenderEviction.referrersOf( notAWikiEngine, "Old" ) );
    }

    @Test
    void referrersOfAreTheReferenceManagersReferrersOfThatName() {
        final ReferenceManager refs = mock( ReferenceManager.class );
        when( refs.findReferrers( "Target" ) ).thenReturn( Set.of( "Zed", "Alpha" ) );
        final ReferenceManager original = engine.getManager( ReferenceManager.class );
        engine.setManager( ReferenceManager.class, refs );
        try {
            assertEquals( List.of( "Alpha", "Zed" ), List.copyOf( RenameRenderEviction.referrersOf( engine, "Target" ) ) );
        } finally {
            engine.setManager( ReferenceManager.class, original );
        }
    }

    // ---- RenameTextReader ----------------------------------------------------------------------------------------

    @Test
    void textOfAMissingPageReadsAsEmptyAndOfAnExistingPageAsItsText() throws Exception {
        engine.saveText( "ReadMe", "hello" );
        try {
            assertEquals( "hello", RenameTextReader.textOf( engine, "ReadMe" ).trim() );
            assertEquals( "", RenameTextReader.textOf( engine, "NoSuchPageAnywhere" ) );
            assertEquals( "", RenameTextReader.referrerText( engine, "NoSuchPageAnywhere" ) );
        } finally {
            engine.deleteQuietly( "ReadMe" );
        }
    }

    // ---- WikiLinkRenamer -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource( delimiter = '|', value = {
            "[[OLD   page]]|[[NewPage]]",
            "[[#^a]] [[OldPage]]|[[#^a]] [[NewPage]]",
            "[[OtherPage]]|[[OtherPage]]" } )
    void wikiLinksAreRewrittenOnlyWhenTheyNameTheRenamedPage( final String in, final String expected ) {
        assertEquals( expected, WikiLinkRenamer.rewrite( in, "OldPage", "NewPage" ) );
    }

    // ---- BlockIdService ------------------------------------------------------------------------------------------

    @Test
    void aStaleVersionIsRefusedBeforeAnythingIsSaved() throws Exception {
        final PageManager pages = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 3 );
        when( pages.getPageWithoutMetadata( "P", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        final PageSaveHelper saver = mock( PageSaveHelper.class );

        final VersionConflictException e = assertThrows( VersionConflictException.class,
                () -> new BlockIdService( pages, saver ).assign( "P", 2, 0, "", "a" ) );
        assertTrue( e.getMessage().contains( "P" ), e.getMessage() );
        verify( saver, never() ).saveText( anyString(), anyString(), any() );
    }

    @Test
    void aPageWhoseTextCannotBeReadIsNotLocated() throws Exception {
        final PageManager pages = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 1 );
        when( pages.getPageWithoutMetadata( "P", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        when( pages.getPureText( "P", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        final PageSaveHelper saver = mock( PageSaveHelper.class );

        final BlockIdService.Refused r = assertThrows( BlockIdService.Refused.class,
                () -> new BlockIdService( pages, saver ).assign( "P", 1, 0, "", "a" ) );
        assertEquals( BlockIdService.Reason.NOT_LOCATED, r.reason() );
        verify( saver, never() ).saveText( anyString(), anyString(), any() );
    }

    // ---- BlockDrafts ---------------------------------------------------------------------------------------------

    private static Block block( final int index, final int start, final int end ) {
        return new Block( index, null, BlockIds.Kind.PARAGRAPH, start, end, -1, -1, "" );
    }

    @ParameterizedTest
    @CsvSource( { "9,-1", "10,0", "20,0", "21,-1" } )
    void aBlockHoldsItsStartAndItsEndOffsetButNotTheOnesOutside( final int offset, final int expectedIndex ) {
        final var found = BlockDrafts.blockAt( List.of( block( 0, 10, 20 ) ), offset );
        assertEquals( expectedIndex, found.map( Block::index ).orElse( -1 ) );
    }

    @Test
    void ofTwoBlocksHoldingAnOffsetTheOneStartingLastWinsAndOnATieTheShorter() {
        final Block outer = block( 0, 0, 100 );
        final Block inner = block( 1, 10, 50 );
        assertSame( inner, BlockDrafts.blockAt( List.of( outer, inner ), 20 ).orElseThrow() );
        assertSame( inner, BlockDrafts.blockAt( List.of( inner, outer ), 20 ).orElseThrow() );

        final Block longer = block( 0, 10, 90 );
        final Block shorter = block( 1, 10, 30 );
        assertSame( shorter, BlockDrafts.blockAt( List.of( longer, shorter ), 20 ).orElseThrow() );
        assertSame( shorter, BlockDrafts.blockAt( List.of( shorter, longer ), 20 ).orElseThrow() );
        // an equal-length tie keeps the first
        assertSame( longer, BlockDrafts.blockAt( List.of( longer, block( 2, 10, 90 ) ), 20 ).orElseThrow() );
    }

    @Test
    void offsetsAtTheEdgesOfTheTextAreAcceptedAndOutsideItRefused() throws Exception {
        final String text = "Para.\n";
        assertEquals( "x1", BlockDrafts.linkAt( "D", text, 0, t -> "x1" ).id() );
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "D", text, -1, t -> "x1" ) ).reason() );
        assertEquals( BlockDrafts.Refusal.NO_BLOCK, assertThrows( BlockDrafts.Refused.class,
                () -> BlockDrafts.linkAt( "D", text, text.length() + 1, t -> "x1" ) ).reason() );
    }

    @Test
    void aBlockWithAnIdIsLinkedWithoutAnEditAndWithoutAskingForAFreshId() throws Exception {
        final BlockDrafts.Link link = BlockDrafts.linkAt( "D", "Para ^keep\n", 0, t -> {
            throw new AssertionError( "no fresh id is needed" );
        } );
        assertEquals( "keep", link.id() );
        assertEquals( null, link.change() );
    }
}
