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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.work.Item;
import com.wikantik.work.WorkService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code reportedFromOutside} marker members see on an item or card: the reporter holds only the {@code reporter}
 * role in the project (they filed through intake and are not on the team).
 *
 * <p>Bounded by the response: one service call per response, given only the distinct reporters of the items in it.
 * Only the single-project shapes are marked ({@code GET items/{key}}, {@code PATCH items/{key}},
 * {@code GET items?project=}, {@code GET projects/{key}/board}); the cross-project find shape carries no marker. The
 * marker is a boolean: a member sees the reporter's display name as before, never a login. A failure to work it out is
 * logged and the cards go unmarked, as it must not cost the member the list.
 */
final class WorkOutside {

    private static final Logger LOG = LogManager.getLogger( WorkOutside.class );
    static final String FLAG = "reportedFromOutside";

    private WorkOutside() {
    }

    /** The reporters of {@code items} who are outside the project {@code projectKey}. */
    static Set< String > of( final WorkResourceBase.Call c, final String projectKey, final Collection< Item > items ) {
        final Set< String > logins = new LinkedHashSet<>();
        for ( final Item i : items ) {
            if ( i.reporterAgentId() == null && i.reporter() != null ) {
                logins.add( i.reporter() );
            }
        }
        if ( logins.isEmpty() ) {
            return Set.of();
        }
        try {
            final Set< String > outside = c.svc.features().intake().outsideReporters( c.actor, projectKey, logins );
            return outside == null ? Set.of() : outside;
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "Could not work out which reporters of project {} are outside it; the items are shown unmarked: {}",
                projectKey, e.getMessage(), e );
            return Set.of();
        }
    }

    static boolean outside( final Item item, final Set< String > logins ) {
        return item.reporterAgentId() == null && item.reporter() != null && logins.contains( item.reporter() );
    }

    /** Marks one item view: the flag on the view and on its {@code item}. */
    static JsonObject markView( final JsonObject view, final Item item, final Set< String > logins ) {
        final boolean flag = outside( item, logins );
        view.addProperty( FLAG, flag );
        if ( view.get( "item" ) instanceof JsonObject inner ) {
            inner.addProperty( FLAG, flag );
        }
        return view;
    }

    /** Marks a card list built in the order of {@code summaries}. */
    static JsonArray markCards( final JsonArray cards, final List< WorkService.ItemSummary > summaries,
                                final Set< String > logins ) {
        for ( int i = 0; i < summaries.size() && i < cards.size(); i++ ) {
            cards.get( i ).getAsJsonObject().addProperty( FLAG, outside( summaries.get( i ).item(), logins ) );
        }
        return cards;
    }

    /** Marks the cards of every column of a board built in the order of {@code columns}. */
    static JsonArray markBoard( final JsonArray tree, final List< WorkService.SummaryColumn > columns,
                                final Set< String > logins ) {
        for ( int i = 0; i < columns.size() && i < tree.size(); i++ ) {
            markCards( tree.get( i ).getAsJsonObject().getAsJsonArray( "items" ), columns.get( i ).items(), logins );
        }
        return tree;
    }

    static List< Item > itemsOf( final List< WorkService.ItemSummary > summaries ) {
        return summaries.stream().map( WorkService.ItemSummary::item ).toList();
    }

    static List< Item > itemsOfBoard( final List< WorkService.SummaryColumn > columns ) {
        return columns.stream().flatMap( col -> col.items().stream() ).map( WorkService.ItemSummary::item ).toList();
    }
}
