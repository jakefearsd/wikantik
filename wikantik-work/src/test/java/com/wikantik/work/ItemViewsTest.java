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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How items, comments and boards are shown: text rendered against its own item, extras batched, products named. */
class ItemViewsTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );

    private WorkStore store;
    private ItemViews views;
    private final Project plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null, null );
    private final Actor alice = Actor.human( "u-c01d", Set.of(), false );

    @BeforeEach
    void setUp() {
        store = mock( WorkStore.class );
        views = new ItemViews( store, new WorkRenderer() {
            @Override public String toHtml( final String markdown ) { return "<p>" + markdown + "</p>"; }
            @Override public String toHtml( final String markdown, final String itemKey ) {
                return "[" + itemKey + "]" + markdown;
            }
        }, new WorkPeople( WorkPeopleTest.DIRECTORY ) );
    }

    private static Item item( final long id, final WorkLevel level, final String body, final Long productId ) {
        return new Item( id, 1, "PLAT-" + id, level, null, productId, WorkKind.SOFTWARE, "t", body, WorkStatus.READY, 3,
            1, null, null, "alice", null, null, null, 1, NOW, NOW, null, null, null, null, null, null, null, null, null,
            null );
    }

    private static Product product( final long id, final String key, final String cluster ) {
        return new Product( id, key, key, null, null, cluster, Autonomy.DELEGATED, DeployGate.HUMAN, null );
    }

    // ---- text

    @Test
    void aBodyIsRenderedAgainstItsOwnItemAndNothingIsRenderedForNoBody() {
        assertEquals( "[PLAT-1]hello", views.bodyHtml( item( 1, WorkLevel.TASK, "hello", null ) ) );
        assertEquals( "", views.bodyHtml( item( 1, WorkLevel.TASK, null, null ) ) );
        assertEquals( "", views.bodyHtml( item( 1, WorkLevel.TASK, "   ", null ) ) );
        assertEquals( "[PLAT-1]md", views.htmlOf( "md", "PLAT-1" ) );
        assertEquals( "", views.htmlOf( null, "PLAT-1" ) );
        assertEquals( "", views.htmlOf( " ", "PLAT-1" ) );
    }

    @Test
    void aCommentShowsMentionsAsCurrentNamesAndARemovedOneShowsNothing() {
        final CommentView shown = views.comment( new Comment( 1, 5, "bob", "hi @[Old](user:id-ana)", NOW, null, null, null ), "PLAT-5" );
        assertEquals( "hi @[Old](user:id-ana)", shown.body(), "the stored text is kept" );
        assertEquals( "[PLAT-5]hi @Ana Lima", shown.bodyHtml() );
        assertEquals( List.of( "Ana Lima" ), shown.mentions() );
        final CommentView removed = views.comment( new Comment( 2, 5, "bob", "secret", NOW, null, NOW, null ), "PLAT-5" );
        assertEquals( "", removed.body(), "a removed comment's text is never shown" );
        assertEquals( "", removed.bodyHtml() );
        assertEquals( List.of(), removed.mentions() );
        assertEquals( NOW, removed.removedAt() );
        final CommentView blank = views.comment( new Comment( 3, 5, "bob", "  ", NOW, null, null, null ), "PLAT-5" );
        assertEquals( "", blank.bodyHtml() );
        assertEquals( "  ", blank.body() );
        assertEquals( "", views.comment( new Comment( 4, 5, "bob", null, NOW, null, null, null ), "PLAT-5" ).bodyHtml() );
    }

    @Test
    void aReportersCommentNeverShowsAnIdAndARemovedOneStillShowsNothing() {
        final CommentView shown = views.reporterComment(
            new Comment( 1, 5, "bob", "see @[Old](user:id-ana) and @[X](user:bad id!)", NOW, null, null, null ), "PLAT-5" );
        assertEquals( "see @Ana Lima and @X", shown.body() );
        assertEquals( "[PLAT-5]see @Ana Lima and @X", shown.bodyHtml() );
        assertTrue( !shown.body().contains( "user:" ) );
        final CommentView removed = views.reporterComment( new Comment( 2, 5, "bob", "secret", NOW, null, NOW, null ), "PLAT-5" );
        assertEquals( "", removed.body() );
        assertEquals( "", removed.bodyHtml() );
        assertEquals( "", views.reporterComment( new Comment( 3, 5, "bob", " ", NOW, null, null, null ), "PLAT-5" ).bodyHtml() );
        assertEquals( "", views.reporterComment( new Comment( 4, 5, "bob", null, NOW, null, null, null ), "PLAT-5" ).bodyHtml() );
    }

    // ---- filters

    @Test
    void anAssigneeFilterIsResolvedToALoginAndAnUnknownNameMatchesNobody() {
        final WorkScopes.Scope s = new WorkScopes.Scope( alice, plat, WorkRole.MEMBER, a -> { } );
        final ItemFilter none = new ItemFilter( null, null, null, null, null, null, null, null, 0, null, null, null, null, null );
        assertNull( views.pinned( alice, s, none ).assignee() );
        assertEquals( 1L, views.pinned( alice, s, none ).projectId() );
        assertNull( views.pinned( alice, s, none.with( null, "  " ) ).assignee() );
        assertEquals( "u-7f3a", views.pinned( alice, s, none.with( null, "Ana Lima" ) ).assignee() );
        assertEquals( "\n", views.pinned( alice, s, none.with( null, "Nobody" ) ).assignee(), "matches no stored login" );
        assertEquals( "\n", views.pinned( alice, s, none.with( null, "u-7f3a" ) ).assignee(), "a member cannot name a login" );
        assertEquals( 1L, views.pinned( alice, s, none.with( 99L, "Ana Lima" ) ).projectId(), "pinned to the project, whatever was asked" );
    }

    // ---- the item view

    @Test
    void anEpicShowsTheProductKeysOfItsDescendantsAndOtherLevelsShowNone() throws SQLException {
        when( store.products() ).thenReturn( List.of( product( 3, "web", null ), product( 4, "api", "ApiCluster" ) ) );
        when( store.productIdsUnder( 10 ) ).thenReturn( new LinkedHashSet<>( List.of( 3L, 4L, 99L ) ) );
        when( store.productIdsUnder( 11 ) ).thenReturn( Set.of( 3L ) );
        final WorkScopes.Scope s = new WorkScopes.Scope( alice, plat, WorkRole.MEMBER, a -> { } );
        assertEquals( List.of( "web", "api" ), views.view( s, item( 10, WorkLevel.EPIC, null, null ) ).derivedProductKeys(),
            "an id no product has is left out" );
        assertEquals( List.of(), views.view( s, item( 11, WorkLevel.STORY, null, null ) ).derivedProductKeys() );
        verify( store, never() ).productIdsUnder( 11 );
    }

    @Test
    void anItemShowsItsProductKeyParentAndTheProductsWikiCluster() throws SQLException {
        when( store.products() ).thenReturn( List.of( product( 3, "web", "WebCluster" ), product( 4, "api", "  " ),
            product( 5, "cli", null ) ) );
        final Item parent = item( 20, WorkLevel.EPIC, null, null );
        when( store.itemById( 20 ) ).thenReturn( Optional.of( parent ) );
        final WorkScopes.Scope s = new WorkScopes.Scope( alice, plat, WorkRole.LEAD, a -> { } );
        final Item child = new Item( 21, 1, "PLAT-21", WorkLevel.STORY, 20L, 3L, WorkKind.SOFTWARE, "t", null,
            WorkStatus.READY, 3, 1, null, null, "alice", null, null, null, 1, NOW, NOW, null, null, null, null, null,
            null, null, null, null, null );
        final WorkService.ItemView v = views.view( s, child );
        assertEquals( "web", v.productKey() );
        assertEquals( "PLAT-20", v.parentKey() );
        assertEquals( "WebCluster", v.wikiCluster() );
        assertEquals( WorkRole.LEAD, v.viewerRole() );
        assertEquals( "PLAT", v.projectKey() );
        assertNull( viewOf( s, 4L ).wikiCluster(), "a blank cluster is none" );
        assertNull( viewOf( s, 5L ).wikiCluster(), "no cluster is none" );
        assertNull( viewOf( s, 77L ).wikiCluster(), "a product that is not in the catalogue" );
        assertNull( viewOf( s, null ).wikiCluster() );
        assertNull( viewOf( s, null ).productKey() );
        assertNull( views.view( s, item( 22, WorkLevel.TASK, null, null ) ).parentKey() );
    }

    private WorkService.ItemView viewOf( final WorkScopes.Scope s, final Long productId ) throws SQLException {
        return views.view( s, item( 23, WorkLevel.TASK, null, productId ) );
    }

    @Test
    void aReporterIsNeverShownTheFullView() {
        final WorkScopes.Scope s = new WorkScopes.Scope( alice, plat, WorkRole.REPORTER, a -> { } );
        org.junit.jupiter.api.Assertions.assertThrows( IllegalStateException.class,
            () -> views.view( s, item( 1, WorkLevel.TASK, null, null ) ) );
    }

    // ---- summaries and boards

    @Test
    void aBoardCarriesEveryCardsExtrasInTheColumnsOwnOrder() throws SQLException {
        final Item a = item( 1, WorkLevel.TASK, null, null );
        final Item b = item( 2, WorkLevel.TASK, null, null );
        final Item c = item( 3, WorkLevel.TASK, null, null );
        when( store.itemExtras( org.mockito.ArgumentMatchers.eq( 1L ), org.mockito.ArgumentMatchers.anyCollection() ) ).thenReturn( Map.of(
            1L, new WorkStore.ItemExtras( 2, true, "web", "PLAT-9" ), 3L, new WorkStore.ItemExtras( 5, false, null, null ) ) );
        final List< WorkService.SummaryColumn > cols = views.summaryColumns( 1, List.of(
            new BoardColumn( WorkStatus.READY, 9, List.of( c, a ) ), new BoardColumn( WorkStatus.DONE, 1, List.of( b ) ) ) );
        assertEquals( 2, cols.size() );
        assertEquals( WorkStatus.READY, cols.get( 0 ).status() );
        assertEquals( 9, cols.get( 0 ).total() );
        assertEquals( List.of( 3L, 1L ), cols.get( 0 ).items().stream().map( i -> i.item().id() ).toList() );
        assertEquals( 5, cols.get( 0 ).items().get( 0 ).commentCount() );
        assertEquals( 2, cols.get( 0 ).items().get( 1 ).commentCount() );
        assertTrue( cols.get( 0 ).items().get( 1 ).blocked() );
        assertEquals( "web", cols.get( 0 ).items().get( 1 ).productKey() );
        assertEquals( "PLAT-9", cols.get( 0 ).items().get( 1 ).parentKey() );
        assertEquals( 0, cols.get( 1 ).items().get( 0 ).commentCount(), "no extras recorded: none shown" );
        assertEquals( List.of( 2L ), cols.get( 1 ).items().stream().map( i -> i.item().id() ).toList() );
        verify( store, org.mockito.Mockito.times( 1 ) ).itemExtras( anyLong(), org.mockito.ArgumentMatchers.anyCollection() );
    }

    @Test
    void summariesAcrossProjectsKeepTheInputOrderWithOneLookupPerProject() throws SQLException {
        final Item p1a = item( 1, WorkLevel.TASK, null, null );
        final Item p2 = new Item( 9, 2, "OPS-9", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", null, WorkStatus.READY,
            3, 1, null, null, "alice", null, null, null, 1, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
        final Item p1b = item( 2, WorkLevel.TASK, null, null );
        when( store.itemExtras( org.mockito.ArgumentMatchers.eq( 1L ), org.mockito.ArgumentMatchers.anyCollection() ) )
            .thenReturn( Map.of( 2L, new WorkStore.ItemExtras( 7, false, null, null ) ) );
        when( store.itemExtras( org.mockito.ArgumentMatchers.eq( 2L ), org.mockito.ArgumentMatchers.anyCollection() ) )
            .thenReturn( Map.of( 9L, new WorkStore.ItemExtras( 4, true, null, null ) ) );
        final List< WorkService.ItemSummary > out = views.summariseAcross( List.of( p1a, p2, p1b ) );
        assertEquals( List.of( 1L, 9L, 2L ), out.stream().map( s -> s.item().id() ).toList() );
        assertEquals( List.of( 0, 4, 7 ), out.stream().map( WorkService.ItemSummary::commentCount ).toList() );
        verify( store, org.mockito.Mockito.times( 2 ) ).itemExtras( anyLong(), org.mockito.ArgumentMatchers.anyCollection() );
    }
}
