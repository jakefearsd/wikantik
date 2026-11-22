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

import com.wikantik.work.WorkScopes.Scope;
import com.wikantik.work.WorkService.ItemSummary;
import com.wikantik.work.WorkService.ItemView;
import com.wikantik.work.WorkService.SummaryColumn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds what a reader is shown of an item or a board: the full view, and the card summaries. */
final class ItemViews {

    private final WorkStore store;
    private final WorkRenderer renderer;
    private final WorkPeople people;

    ItemViews( final WorkStore store, final WorkRenderer renderer, final WorkPeople people ) {
        this.store = store;
        this.renderer = renderer;
        this.people = people;
    }

    /** Matches no stored login (logins never hold a line break): an assignee filter that names nobody. */
    private static final String NOBODY = "\n";

    /** The filter pinned to the project, its assignee reference resolved to a login (or to nobody). */
    ItemFilter pinned( final Actor actor, final Scope s, final ItemFilter filter ) {
        final ItemFilter f = s.pin( filter );
        if ( f.assignee() == null || f.assignee().isBlank() ) {
            return f.with( f.projectId(), null );
        }
        return f.with( f.projectId(), people.login( actor, f.assignee() ).orElse( NOBODY ) );
    }

    /** A comment as shown: its text rendered like an item's, with mentions as display names. */
    CommentView comment( final Comment c, final String itemKey ) {
        if ( c.removedAt() != null || c.body() == null || c.body().isBlank() ) {
            return new CommentView( c.id(), c.itemId(), c.author(), c.removedAt() != null ? "" : c.body(),
                c.createdAt(), c.editedAt(), c.removedAt(), "", List.of(), c.authorAgentId() );
        }
        final WorkPeople.Display d = people.display( c.body() );
        return new CommentView( c.id(), c.itemId(), c.author(), c.body(), c.createdAt(), c.editedAt(), c.removedAt(),
            renderer.toHtml( d.markdown(), itemKey ), d.names(), c.authorAgentId() );
    }

    /** The item's body rendered like any item text (links as typed, no lookups); empty for no body. */
    String bodyHtml( final Item item ) {
        return item.body() == null || item.body().isBlank() ? "" : renderer.toHtml( item.body(), item.key() );
    }

    ItemView view( final Scope s, final Item item ) throws SQLException {
        if ( s.reporterOnly() ) {
            throw new IllegalStateException( "A reporter is shown ReportedItemView, never the full view." );
        }
        final String html = bodyHtml( item );
        final List< Product > products = store.products();
        final Item parent = item.parentId() == null ? null : store.itemById( item.parentId() ).orElse( null );
        final String productKey = item.productId() == null ? null : productKey( products, item.productId() );
        return new ItemView( item, html, s.project().key(), parent == null ? null : parent.key(), productKey,
            derivedProductKeys( item, products ), store.comments( item.id() ).stream().map( c -> comment( c, item.key() ) ).toList(),
            store.links( item.id() ), store.isBlocked( item.id() ), s.effectiveRole(), s.project().archivedAt() != null,
            wikiCluster( products, item.productId() ) );
    }

    /** The product keys an epic's descendants carry; empty for any other level. */
    private List< String > derivedProductKeys( final Item item, final List< Product > products ) throws SQLException {
        final List< String > derived = new ArrayList<>();
        if ( item.level() == WorkLevel.EPIC ) {
            for ( final long productId : store.productIdsUnder( item.id() ) ) {
                final String k = productKey( products, productId );
                if ( k != null ) {
                    derived.add( k );
                }
            }
        }
        return derived;
    }

    List< ItemSummary > summarise( final long projectId, final List< Item > items ) throws SQLException {
        final Map< Long, WorkStore.ItemExtras > extras = store.itemExtras( projectId,
            items.stream().map( Item::id ).toList() );
        final WorkStore.ItemExtras none = new WorkStore.ItemExtras( 0, false, null, null );
        return items.stream().map( i -> {
            final WorkStore.ItemExtras e = extras.getOrDefault( i.id(), none );
            return new ItemSummary( i, e.commentCount(), e.blocked(), e.productKey(), e.parentKey() );
        } ).toList();
    }

    /** Summaries of items from several projects, in the input order; one extras lookup per project. */
    List< ItemSummary > summariseAcross( final List< Item > items ) throws SQLException {
        final Map< Long, List< Item > > byProject = new java.util.LinkedHashMap<>();
        for ( final Item i : items ) {
            byProject.computeIfAbsent( i.projectId(), k -> new ArrayList<>() ).add( i );
        }
        final Map< Long, ItemSummary > byId = new HashMap<>();
        for ( final Map.Entry< Long, List< Item > > e : byProject.entrySet() ) {
            summarise( e.getKey(), e.getValue() ).forEach( x -> byId.put( x.item().id(), x ) );
        }
        return items.stream().map( i -> byId.get( i.id() ) ).toList();
    }

    /** The board's columns with card extras, computed for every column's items in one batch. */
    List< SummaryColumn > summaryColumns( final long projectId, final List< BoardColumn > columns )
            throws SQLException {
        final List< Item > all = columns.stream().flatMap( c -> c.items().stream() ).toList();
        final Map< Long, ItemSummary > byId = new HashMap<>();
        summarise( projectId, all ).forEach( x -> byId.put( x.item().id(), x ) );
        return columns.stream().map( c -> new SummaryColumn( c.status(), c.total(),
            c.items().stream().map( i -> byId.get( i.id() ) ).toList() ) ).toList();
    }

    /** The product's wiki cluster; null when the item has no product or the product names none. */
    private static String wikiCluster( final List< Product > products, final Long productId ) {
        return productId == null ? null : products.stream().filter( x -> x.id() == productId ).findFirst()
            .map( Product::wikiCluster ).filter( c -> !c.isBlank() ).orElse( null );
    }

    private static String productKey( final List< Product > products, final long id ) {
        final Optional< Product > p = products.stream().filter( x -> x.id() == id ).findFirst();
        return p.map( Product::key ).orElse( null );
    }
}
