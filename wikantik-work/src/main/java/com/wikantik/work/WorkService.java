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
import java.time.Clock;
import java.time.Instant;
import com.wikantik.work.WorkScopes.ItemScope;
import com.wikantik.work.WorkScopes.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The single entry point to work management: every caller (REST, later MCP) goes through here, so project
 * visibility, validation and policy are applied in exactly one place.
 *
 * <p>Visibility rule: whoever is neither a wiki admin nor a project member gets {@link WorkNotFoundException}
 * for the project and for everything in it, with one message for "unknown" and "hidden" alike. Only a caller
 * who can see the project can be told {@link WorkDeniedException}.
 *
 * <p>Every public method is classified as a read or a write by {@code WorkServiceVisibilityPostgresTest}; add a
 * method and that test fails until it is classified.
 */
public class WorkService {

    private static final Pattern ITEM_KEY = Pattern.compile( "^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,9})$" );
    private static final String NOT_FOUND = WorkScopes.NOT_FOUND;

    /** An item together with everything a reader needs to show it. */
    public record ItemView( Item item, String bodyHtml, String projectKey, String parentKey, String productKey,
                            List< String > derivedProductKeys, List< Comment > comments, List< Link > links,
                            boolean blocked, WorkRole viewerRole ) {
    }

    /** An item with what its card shows: comment count, blocked flag, product key and parent key. */
    public record ItemSummary( Item item, int commentCount, boolean blocked, String productKey, String parentKey ) {
    }

    /** A board column whose items are summaries. */
    public record SummaryColumn( WorkStatus status, int total, List< ItemSummary > items ) {
    }

    private final WorkStore store;
    private final WorkScopes scopes;
    private final ItemViews views;
    private final WorkDirectory directory;
    private final Clock clock;
    private final ItemPatchApplier applier;

    public WorkService( final WorkStore store, final WorkRenderer renderer, final WorkDirectory directory,
                        final Clock clock ) {
        this( store, renderer, directory, clock, action -> { } );
    }

    /**
     * @param denialListener called with the attempted action whenever this service throws
     *                       {@link WorkDeniedException}; null means no listener
     */
    public WorkService( final WorkStore store, final WorkRenderer renderer, final WorkDirectory directory,
                        final Clock clock, final Consumer< Action > denialListener ) {
        this.store = store;
        this.scopes = new WorkScopes( store, directory, denialListener );
        this.views = new ItemViews( store, renderer );
        this.directory = directory;
        this.clock = clock;
        this.applier = new ItemPatchApplier( store, scopes::isMember );
    }

    // ---- projects

    public List< Project > listProjects( final Actor actor ) throws SQLException {
        return store.visibleProjects( actor );
    }

    public Project project( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.VIEW, null );
        return s.project();
    }

    public Project createProject( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireProjectKey( key );
        WorkInputs.requireName( name );
        if ( store.project( key ).isPresent() ) {
            throw new WorkConflictException( "Project " + key + " already exists." );
        }
        return store.createProject( key, name.trim(), description == null ? "" : description );
    }

    public void updateProject( final Actor actor, final String key, final String name, final String description,
                               final Autonomy defaultAutonomy ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        if ( defaultAutonomy != null && defaultAutonomy != s.project().defaultAutonomy() ) {
            s.require( Action.SET_POLICY, null );
        }
        s.requireWritable();
        WorkInputs.requireName( name );
        store.updateProject( s.project().id(), name.trim(), description == null ? "" : description,
            defaultAutonomy == null ? s.project().defaultAutonomy() : defaultAutonomy );
    }

    public void archiveProject( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_INSTANCE, null );
        s.requireWritable();
        store.archiveProject( s.project().id(), clock.instant() );
    }

    public List< Member > members( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.VIEW, null );
        return store.members( s.project().id() );
    }

    public void putMember( final Actor actor, final String key, final String principal, final String principalKind,
                           final WorkRole role ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        WorkInputs.requireMember( principal, principalKind, role );
        if ( "user".equals( principalKind ) && !directory.exists( principal ) ) {
            throw new WorkValidationException( "principal", "No such user." );
        }
        store.putMember( s.project().id(), principal, principalKind, role );
    }

    public void removeMember( final Actor actor, final String key, final String principal,
                              final String principalKind ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        store.removeMember( s.project().id(), principal, principalKind );
    }

    public WorkRole roleIn( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.VIEW, null );
        return s.effectiveRole();
    }

    // ---- products

    /** Products are visible to a wiki admin and to anyone who belongs to at least one project. */
    public List< Product > listProducts( final Actor actor ) throws SQLException {
        if ( !actor.admin() && store.visibleProjects( actor ).isEmpty() ) {
            return List.of();
        }
        return store.products();
    }

    public Product createProduct( final Actor actor, final Product draft ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product product = WorkInputs.newProduct( draft );
        if ( store.product( draft.key() ).isPresent() ) {
            throw new WorkConflictException( "Product " + draft.key() + " already exists." );
        }
        return store.createProduct( product );
    }

    public void updateProduct( final Actor actor, final Product product ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( product.key() )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + product.key() ) );
        store.updateProduct( WorkInputs.updatedProduct( existing, product ) );
    }

    public void archiveProduct( final Actor actor, final String key ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( key )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + key ) );
        store.archiveProduct( existing.id(), clock.instant() );
    }

    // ---- items

    public ItemView createItem( final Actor actor, final ItemDraft draft ) throws SQLException {
        final Scope s = scopes.scope( actor, draft.projectKey() );
        s.require( Action.CREATE_ITEM, null );
        s.requireWritable();
        final Item next = applier.forCreate( s.project(), draft );
        return views.view( s, store.createItem( s.project(), next, actor.login(), actor.kind() ) );
    }

    public ItemView item( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return views.view( is.scope(), is.item() );
    }

    public ItemView updateItem( final Actor actor, final String key, final ItemPatch patch ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        final Scope s = is.scope();
        final Map< String, Object > changes = patch.changes() == null ? Map.of() : patch.changes();
        if ( changes.isEmpty() ) {
            throw new WorkValidationException( "changes", "Nothing to change." );
        }
        s.require( ItemPatchApplier.MOVE_KEYS.containsAll( changes.keySet() ) ? Action.MOVE : Action.EDIT_ITEM,
            is.item() );
        if ( changes.keySet().stream().anyMatch( ItemPatchApplier.POLICY_KEYS::contains ) ) {
            s.require( Action.SET_POLICY, is.item() );
        }
        s.requireWritable();
        final Item next = applier.apply( s.project(), is.item(), changes, patch.note() );
        final Item stamped = WorkInputs.withVersion( is.item(), patch.expectedVersion() );
        return views.view( s, store.updateItem( stamped, next, actor.login(), actor.kind(), patch.note() ) );
    }

    public List< Item > items( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.items( s.pin( filter ) );
    }

    public List< BoardColumn > board( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.board( s.project().id(), s.pin( filter ) );
    }

    /** {@link #items} with card extras, computed for the whole result in one batch. */
    public List< ItemSummary > itemSummaries( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return views.summarise( s.project().id(), store.items( s.pin( filter ) ) );
    }

    /** {@link #board} with card extras, computed for every column's items in one batch. */
    public List< SummaryColumn > boardSummaries( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return views.summaryColumns( s.project().id(), store.board( s.project().id(), s.pin( filter ) ) );
    }

    public List< ItemRevision > revisions( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return store.revisions( is.item().id() );
    }

    public List< WorkEvent > events( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return store.itemEvents( is.item().id() );
    }

    public List< WorkEvent > activity( final Actor actor, final String projectKey, final Instant before,
                                       final int limit ) throws SQLException {
        return activity( actor, projectKey, before, null, limit );
    }

    /** As above; {@code beforeId} (with {@code before}) makes the cursor the compound {@code (at, id)}. */
    public List< WorkEvent > activity( final Actor actor, final String projectKey, final Instant before,
                                       final Long beforeId, final int limit ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.projectEvents( s.project().id(), before, beforeId, WorkInputs.activityLimit( limit ) );
    }

    // ---- discussion

    public Comment addComment( final Actor actor, final String itemKey, final String body ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, itemKey );
        is.scope().require( Action.COMMENT, is.item() );
        is.scope().requireWritable();
        WorkInputs.requireBody( body );
        final long projectId = is.scope().project().id();
        return store.addComment( is.item(), actor.login(), actor.kind(), body,
            WorkInputs.ofMembers( WorkInputs.mentions( body ), login -> scopes.isMember( projectId, login ) ) );
    }

    public Comment editComment( final Actor actor, final long commentId, final String body ) throws SQLException {
        final ItemScope is = scopes.commentScope( actor, commentId );
        final Comment c = scopes.comment( commentId );
        is.scope().require( Action.COMMENT, is.item() );
        scopes.requireAuthor( actor, c, "Only the author may edit a comment." );
        is.scope().requireWritable();
        WorkInputs.requireBody( body );
        return store.editComment( commentId, body, actor.login() );
    }

    public void removeComment( final Actor actor, final long commentId ) throws SQLException {
        final ItemScope is = scopes.commentScope( actor, commentId );
        final Comment c = scopes.comment( commentId );
        is.scope().require( Action.COMMENT, is.item() );
        if ( !WorkPolicy.may( actor, is.scope().role(), Action.MANAGE_PROJECT, is.item() ).allowed() ) {
            scopes.requireAuthor( actor, c, "Only the author or a project lead may remove a comment." );
        }
        is.scope().requireWritable();
        store.removeComment( commentId, actor.login() );
    }

    public Link addLink( final Actor actor, final String itemKey, final String kind, final String target )
            throws SQLException {
        final ItemScope is = scopes.itemScope( actor, itemKey );
        is.scope().require( Action.LINK, is.item() );
        is.scope().requireWritable();
        final String stored = WorkInputs.linkTarget( kind, target );
        return store.addLink( is.item(), kind, "blocked_by".equals( kind ) ? sameProjectBlocker( is, stored ) : stored,
            actor.login() );
    }

    /**
     * A blocker must be another item of the same project. Malformed, unknown, foreign-project and self targets
     * all get the one message, so nothing about other projects can be probed through a link.
     */
    private String sameProjectBlocker( final ItemScope is, final String target ) throws SQLException {
        final String canonical = target.toUpperCase( java.util.Locale.ROOT );
        final Item blocker = ITEM_KEY.matcher( canonical ).matches() ? store.item( canonical ).orElse( null ) : null;
        if ( blocker == null || blocker.projectId() != is.item().projectId() || blocker.id() == is.item().id() ) {
            throw new WorkValidationException( "target", "A blocker must be another item in this project." );
        }
        return blocker.key().toUpperCase( java.util.Locale.ROOT );
    }

    public void removeLink( final Actor actor, final long linkId ) throws SQLException {
        final Link link = store.link( linkId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        final ItemScope is = scopes.itemScopeById( actor, link.itemId() );
        is.scope().require( Action.LINK, is.item() );
        is.scope().requireWritable();
        store.removeLink( linkId, actor.login() );
    }

    // ---- me

    /** Open items assigned to the actor plus items that mention them, across visible projects only. */
    public List< Item > waitingOn( final Actor actor ) throws SQLException {
        final Set< Long > ids = store.visibleProjects( actor ).stream().map( Project::id )
            .collect( Collectors.toCollection( LinkedHashSet::new ) );
        if ( ids.isEmpty() ) {
            return List.of();
        }
        final Map< Long, Item > byId = new LinkedHashMap<>();
        for ( final Item i : store.assignedOpen( actor.login(), ids ) ) {
            byId.putIfAbsent( i.id(), i );
        }
        for ( final Item i : store.mentioning( actor.login(), ids ) ) {
            byId.putIfAbsent( i.id(), i );
        }
        final List< Item > out = new ArrayList<>( byId.values() );
        out.sort( Comparator.comparing( Item::updatedAt, Comparator.nullsLast( Comparator.reverseOrder() ) ) );
        return out;
    }
}
