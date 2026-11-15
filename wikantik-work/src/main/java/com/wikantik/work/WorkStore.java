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
 * interface carries projects, membership and products; work items are added alongside.
 */
public interface WorkStore {

    // ---- projects

    /** Creates a project; the key must match {@code ^[A-Z][A-Z0-9]{1,9}$} and be unused. */
    Project createProject( String key, String name, String description ) throws SQLException;

    Optional< Project > project( String key ) throws SQLException;

    /** Every project, archived included. Visibility filtering is {@link #visibleProjects}. */
    List< Project > projects() throws SQLException;

    void updateProject( long id, String name, String description, Autonomy defaultAutonomy ) throws SQLException;

    void archiveProject( long id, Instant at ) throws SQLException;

    // ---- membership

    /** Adds the member or changes the role of an existing one. */
    void putMember( long projectId, String principal, String principalKind, WorkRole role ) throws SQLException;

    void removeMember( long projectId, String principal, String principalKind ) throws SQLException;

    List< Member > members( long projectId ) throws SQLException;

    /**
     * The highest role the actor holds: its {@code user} row, any {@code group} row for one of its groups,
     * and, for an agent, the {@code agent} row whose principal is its agent id. Empty when none; a Wiki
     * Admin is not given a role here, see {@link #visibleProjects}.
     */
    Optional< WorkRole > roleFor( long projectId, Actor actor ) throws SQLException;

    /** All projects for an admin, otherwise those where the actor has a role. */
    List< Project > visibleProjects( Actor actor ) throws SQLException;

    // ---- products

    Product createProduct( Product draft ) throws SQLException;

    Optional< Product > product( String key ) throws SQLException;

    List< Product > products() throws SQLException;

    void updateProduct( Product p ) throws SQLException;

    void archiveProduct( long id, Instant at ) throws SQLException;

    // ---- items

    /**
     * Creates an item in one transaction: allocates the next key, inserts the row (rank = last in its status),
     * revision 1 and a {@code created} event. The draft's id, key, version, rank, timestamps and closedAt are
     * ignored; the reporter is {@code actor}.
     */
    Item createItem( Project project, Item draft, String actor, Actor.Kind kind ) throws SQLException;

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
    Item updateItem( Item current, Item next, String actor, Actor.Kind kind, String note ) throws SQLException;

    List< Item > items( ItemFilter filter ) throws SQLException;

    /** Every status in order; {@code done} and {@code cancelled} show the 50 most recently closed. */
    List< BoardColumn > board( long projectId, ItemFilter filter ) throws SQLException;

    List< ItemRevision > revisions( long itemId ) throws SQLException;

    List< WorkEvent > itemEvents( long itemId ) throws SQLException;

    /** Newest first; {@code before} null means now. */
    List< WorkEvent > projectEvents( long projectId, Instant before, int limit ) throws SQLException;

    /** Distinct products of all descendants of the epic. */
    Set< Long > productIdsUnder( long epicId ) throws SQLException;

    // ---- discussion: comments, mentions, links

    /** Inserts the comment, one mention row per login and a {@code commented} event, in one transaction. */
    Comment addComment( Item item, String author, Actor.Kind kind, String body, List< String > mentioned )
        throws SQLException;

    /** Replaces the body, sets {@code edited_at} and writes {@code comment_edited}. */
    Comment editComment( long commentId, String body, String actor ) throws SQLException;

    /** Sets {@code removed_at} (the stored body is kept) and writes {@code comment_removed}. */
    void removeComment( long commentId, String actor ) throws SQLException;

    Optional< Comment > comment( long id ) throws SQLException;

    /** Looks a link up by id; empty when unknown. */
    Optional< Link > link( long id ) throws SQLException;

    /** Oldest first; a removed comment is returned with an empty body. */
    List< Comment > comments( long itemId ) throws SQLException;

    Link addLink( Item item, String kind, String target, String actor ) throws SQLException;

    /** Deletes the row; the {@code unlinked} event is the record. */
    void removeLink( long linkId, String actor ) throws SQLException;

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
