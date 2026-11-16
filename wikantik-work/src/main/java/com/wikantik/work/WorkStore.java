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

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for work management. Every method throws {@link SQLException}; callers translate. This
 * interface carries items, discussion and metrics; projects, membership and products are in {@link WorkCatalogStore}, queues and agents in {@link WorkRegistryStore}.
 */
public interface WorkStore extends WorkCatalogStore, WorkRegistryStore {

    /** Claims, release and expiry (kept on their own interface to keep this one small). */
    WorkClaimStore claims();

    // ---- items

    /**
     * Creates an item in one transaction: allocates the next key, inserts the row (rank = last in its status),
     * revision 1 and a {@code created} event. The draft's id, key, version, rank, timestamps and closedAt are
     * ignored; the reporter is {@code by} (login and agent id).
     */
    Item createItem( Project project, Item draft, Actor by ) throws SQLException;

    /** Looks an item up by its numeric id; empty when unknown. */
    Optional< Item > itemById( long id ) throws SQLException;

    /** The highest rank among the project's items in the status, or 0 when there are none. */
    double maxRank( long projectId, WorkStatus status ) throws SQLException;

    /** Looks an item up by key such as {@code PLAT-15}, case-insensitively; empty for a malformed or unknown key. */
    Optional< Item > item( String key ) throws SQLException;

    /**
     * Applies {@code next} over {@code current} in one transaction, guarded by {@code current.version()}
     * (stale: {@link WorkConflictException}). Writes a revision when title or body changed and one event per
     * changed field.
     */
    Item updateItem( Item current, Item next, Actor by, String note ) throws SQLException;

    /**
     * Like {@link #updateItem} (same guard, same claim clearing) but always writes exactly one {@code handoff}
     * event, from {@code status|target} to {@code status|target}, where a queue target is {@code queue:<key>}.
     */
    Item handoff( Item current, Item next, Actor by, String note ) throws SQLException;

    /** The newest {@code handoff} event of each given item; items with none are absent. */
    Map< Long, WorkEvent > lastHandoffs( Collection< Long > itemIds ) throws SQLException;

    List< Item > items( ItemFilter filter ) throws SQLException;

    /** Every status in order; {@code done} and {@code cancelled} show the 50 most recently closed. */
    List< BoardColumn > board( long projectId, ItemFilter filter ) throws SQLException;

    List< ItemRevision > revisions( long itemId ) throws SQLException;

    List< WorkEvent > itemEvents( long itemId ) throws SQLException;

    /**
     * Newest first. With {@code beforeId} the cursor is the compound {@code (at, id)}: events strictly older in
     * {@code (at, id)} order, so a page boundary inside a group of events sharing one timestamp loses nothing.
     * Without it the cursor is the timestamp alone ({@code at < before}); {@code before} null means no cursor.
     * {@code itemId}, when set, keeps only that item's events.
     */
    List< WorkEvent > projectEvents( long projectId, Instant before, Long beforeId, Long itemId, int limit )
        throws SQLException;

    /** Distinct products of all descendants of the epic. */
    Set< Long > productIdsUnder( long epicId ) throws SQLException;

    // ---- discussion: comments, mentions, links

    /** Inserts the comment, one mention row per login and a {@code commented} event, in one transaction. */
    Comment addComment( Item item, Actor by, String body, List< String > mentioned )
        throws SQLException;

    /**
     * Replaces the body, sets {@code edited_at}, replaces the comment's mention rows with {@code mentioned} and
     * writes {@code comment_edited}, in one transaction.
     */
    Comment editComment( long commentId, String body, Actor by, List< String > mentioned )
        throws SQLException;

    /** Sets {@code removed_at} (the stored body is kept) and writes {@code comment_removed}. */
    void removeComment( long commentId, Actor by ) throws SQLException;

    Optional< Comment > comment( long id ) throws SQLException;

    /** Looks a link up by id; empty when unknown. */
    Optional< Link > link( long id ) throws SQLException;

    /** Oldest first; a removed comment is returned with an empty body. */
    List< Comment > comments( long itemId ) throws SQLException;

    Link addLink( Item item, String kind, String target, Actor by ) throws SQLException;

    /** Deletes the row; the {@code unlinked} event is the record. A link already gone writes nothing. */
    void removeLink( long linkId, Actor by ) throws SQLException;

    List< Link > links( long itemId ) throws SQLException;

    /** What a card shows beyond the item's own columns; see {@link #itemExtras}. */
    record ItemExtras( int commentCount, boolean blocked, String productKey, String parentKey ) {
    }

    /**
     * Comment count (removed comments excluded), blocked flag (same rule as {@link #isBlocked}, same project),
     * product key and parent key for the given items of one project, in a single query. Ids that are not in
     * the project are absent from the result.
     */
    Map< Long, ItemExtras > itemExtras( long projectId, Collection< Long > itemIds ) throws SQLException;

    /** True when a {@code blocked_by} link targets an item key whose status is not done or cancelled. */
    boolean isBlocked( long itemId ) throws SQLException;

    /** Open items in the given projects with a mention of the login, newest-updated first. */
    List< Item > mentioning( String login, Set< Long > projectIds ) throws SQLException;

    /** Open items in the given projects assigned to the login, newest-updated first. */
    List< Item > assignedOpen( String login, Set< Long > projectIds ) throws SQLException;

    // ---- metrics

    /** The number of items in one status, level and kind. */
    record ItemCount( WorkStatus status, WorkLevel level, WorkKind kind, long count ) {
    }

    /** Item counts grouped by status, level and kind, in one query; groups with no items are absent. */
    List< ItemCount > itemCounts() throws SQLException;
}
