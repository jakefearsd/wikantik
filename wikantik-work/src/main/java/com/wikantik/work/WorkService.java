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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
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
    private static final Pattern PROJECT_KEY = Pattern.compile( "^[A-Z][A-Z0-9]{1,9}$" );
    private static final Pattern PRODUCT_KEY = Pattern.compile( "^[a-z][a-z0-9-]{1,39}$" );
    private static final Pattern MENTION = Pattern.compile( "(?<![\\w@.])@([A-Za-z0-9][A-Za-z0-9._-]*)" );
    private static final Set< String > LINK_KINDS = Set.of( "page", "issue", "url", "blocked_by", "commit" );
    private static final Set< String > PRINCIPAL_KINDS = Set.of( "user", "group", "agent" );
    private static final String NOT_FOUND = "No such project, item or comment.";
    private static final int DEFAULT_ACTIVITY = 50;
    private static final int MAX_ACTIVITY = 200;
    private static final int MAX_LINK_TARGET = 2000;

    /** An item together with everything a reader needs to show it. */
    public record ItemView( Item item, String bodyHtml, String projectKey, String parentKey, String productKey,
                            List< String > derivedProductKeys, List< Comment > comments, List< Link > links,
                            boolean blocked, WorkRole viewerRole ) {
    }

    /** A resolved project and the actor's standing in it; the one thing every method starts from. */
    private record Scope( Actor actor, Project project, WorkRole role, Consumer< Action > onDenied ) {

        /** The role to show: a wiki admin without a member row counts as lead. */
        WorkRole effectiveRole() {
            return role != null ? role : WorkRole.LEAD;
        }

        void require( final Action action, final Item item ) {
            final Decision d = WorkPolicy.may( actor, role, action, item );
            if ( !d.allowed() ) {
                onDenied.accept( action );
                throw new WorkDeniedException( d.reason() );
            }
        }

        void requireWritable() {
            if ( project.archivedAt() != null ) {
                throw new WorkConflictException( "Project " + project.key() + " is archived." );
            }
        }
    }

    private final WorkStore store;
    private final WorkRenderer renderer;
    private final WorkDirectory directory;
    private final Clock clock;
    private final ItemPatchApplier applier;
    private final Consumer< Action > denialListener;

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
        this.denialListener = denialListener == null ? action -> { } : denialListener;
        this.store = store;
        this.renderer = renderer;
        this.directory = directory;
        this.clock = clock;
        this.applier = new ItemPatchApplier( store, this::isMember );
    }

    private void denied( final Action action ) {
        denialListener.accept( action );
    }

    // ---- scope resolution

    /** Resolves the project and the actor's role; unknown and hidden are the same {@link WorkNotFoundException}. */
    private Scope scope( final Actor actor, final String projectKey ) throws SQLException {
        final Project project = projectKey == null ? null
            : store.project( projectKey.trim().toUpperCase( java.util.Locale.ROOT ) ).orElse( null );
        if ( project == null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final WorkRole role = store.roleFor( project.id(), actor ).orElse( null );
        if ( role == null && !actor.admin() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new Scope( actor, project, role, this::denied );
    }

    private record ItemScope( Scope scope, Item item ) {
    }

    /** Resolves an item key through its project's visibility; the item is only looked up once visible. */
    private ItemScope itemScope( final Actor actor, final String itemKey ) throws SQLException {
        final Matcher m = itemKey == null ? null : ITEM_KEY.matcher( itemKey.trim() );
        if ( m == null || !m.matches() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final Scope scope = scope( actor, m.group( 1 ) );
        final Item item = store.item( itemKey ).orElse( null );
        if ( item == null || item.projectId() != scope.project().id() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new ItemScope( scope, item );
    }

    /** Resolves an item by numeric id (via a comment or link), again through project visibility. */
    private ItemScope itemScopeById( final Actor actor, final long itemId ) throws SQLException {
        final Item item = store.itemById( itemId ).orElse( null );
        if ( item == null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return itemScope( actor, item.key() );
    }

    private boolean isMember( final long projectId, final String login ) throws SQLException {
        final Set< String > groups = directory.groupsOf( login );
        return store.roleFor( projectId, Actor.human( login, groups == null ? Set.of() : groups, false ) )
            .isPresent();
    }

    // ---- projects

    public List< Project > listProjects( final Actor actor ) throws SQLException {
        return store.visibleProjects( actor );
    }

    public Project project( final Actor actor, final String key ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.VIEW, null );
        return s.project();
    }

    public Project createProject( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        require( actor, Action.MANAGE_INSTANCE );
        if ( key == null || !PROJECT_KEY.matcher( key ).matches() ) {
            throw new WorkValidationException( "key", "A project key is 2 to 10 capital letters or digits, "
                + "starting with a letter." );
        }
        requireName( name );
        if ( store.project( key ).isPresent() ) {
            throw new WorkConflictException( "Project " + key + " already exists." );
        }
        return store.createProject( key, name.trim(), description == null ? "" : description );
    }

    public void updateProject( final Actor actor, final String key, final String name, final String description,
                               final Autonomy defaultAutonomy ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        if ( defaultAutonomy != null && defaultAutonomy != s.project().defaultAutonomy() ) {
            s.require( Action.SET_POLICY, null );
        }
        s.requireWritable();
        requireName( name );
        store.updateProject( s.project().id(), name.trim(), description == null ? "" : description,
            defaultAutonomy == null ? s.project().defaultAutonomy() : defaultAutonomy );
    }

    public void archiveProject( final Actor actor, final String key ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.MANAGE_INSTANCE, null );
        s.requireWritable();
        store.archiveProject( s.project().id(), clock.instant() );
    }

    public List< Member > members( final Actor actor, final String key ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.VIEW, null );
        return store.members( s.project().id() );
    }

    public void putMember( final Actor actor, final String key, final String principal, final String principalKind,
                           final WorkRole role ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        if ( principal == null || principal.isBlank() ) {
            throw new WorkValidationException( "principal", "A member needs a name." );
        }
        if ( principalKind == null || !PRINCIPAL_KINDS.contains( principalKind ) ) {
            throw new WorkValidationException( "principalKind", "principalKind must be user, group or agent." );
        }
        if ( role == null ) {
            throw new WorkValidationException( "role", "A member needs a role." );
        }
        if ( "user".equals( principalKind ) && !directory.exists( principal ) ) {
            throw new WorkValidationException( "principal", "No such user." );
        }
        store.putMember( s.project().id(), principal, principalKind, role );
    }

    public void removeMember( final Actor actor, final String key, final String principal,
                              final String principalKind ) throws SQLException {
        final Scope s = scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        store.removeMember( s.project().id(), principal, principalKind );
    }

    public WorkRole roleIn( final Actor actor, final String key ) throws SQLException {
        final Scope s = scope( actor, key );
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
        require( actor, Action.MANAGE_INSTANCE );
        if ( draft.key() == null || !PRODUCT_KEY.matcher( draft.key() ).matches() ) {
            throw new WorkValidationException( "key", "A product key is lowercase letters, digits and dashes." );
        }
        requireName( draft.name() );
        if ( store.product( draft.key() ).isPresent() ) {
            throw new WorkConflictException( "Product " + draft.key() + " already exists." );
        }
        return store.createProduct( new Product( 0, draft.key(), draft.name().trim(), draft.repoUrl(),
            draft.defaultBranch(), draft.wikiCluster(),
            draft.autonomyCeiling() == null ? Autonomy.DELEGATED : draft.autonomyCeiling(),
            draft.deployGate() == null ? DeployGate.HUMAN : draft.deployGate(), null ) );
    }

    public void updateProduct( final Actor actor, final Product product ) throws SQLException {
        require( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( product.key() )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + product.key() ) );
        requireName( product.name() );
        store.updateProduct( new Product( existing.id(), existing.key(), product.name().trim(), product.repoUrl(),
            product.defaultBranch(), product.wikiCluster(),
            product.autonomyCeiling() == null ? existing.autonomyCeiling() : product.autonomyCeiling(),
            product.deployGate() == null ? existing.deployGate() : product.deployGate(), existing.archivedAt() ) );
    }

    public void archiveProduct( final Actor actor, final String key ) throws SQLException {
        require( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( key )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + key ) );
        store.archiveProduct( existing.id(), clock.instant() );
    }

    // ---- items

    public ItemView createItem( final Actor actor, final ItemDraft draft ) throws SQLException {
        final Scope s = scope( actor, draft.projectKey() );
        s.require( Action.CREATE_ITEM, null );
        s.requireWritable();
        final Item next = applier.forCreate( s.project(), draft );
        return view( s, store.createItem( s.project(), next, actor.login(), actor.kind() ) );
    }

    public ItemView item( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return view( is.scope(), is.item() );
    }

    public ItemView updateItem( final Actor actor, final String key, final ItemPatch patch ) throws SQLException {
        final ItemScope is = itemScope( actor, key );
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
        final Item stamped = withVersion( is.item(), patch.expectedVersion() );
        return view( s, store.updateItem( stamped, next, actor.login(), actor.kind(), patch.note() ) );
    }

    public List< Item > items( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.items( scoped( s, filter ) );
    }

    public List< BoardColumn > board( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.board( s.project().id(), scoped( s, filter ) );
    }

    public List< ItemRevision > revisions( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return store.revisions( is.item().id() );
    }

    public List< WorkEvent > events( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return store.itemEvents( is.item().id() );
    }

    public List< WorkEvent > activity( final Actor actor, final String projectKey, final Instant before,
                                       final int limit ) throws SQLException {
        final Scope s = scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.projectEvents( s.project().id(), before,
            limit <= 0 ? DEFAULT_ACTIVITY : Math.min( limit, MAX_ACTIVITY ) );
    }

    // ---- discussion

    public Comment addComment( final Actor actor, final String itemKey, final String body ) throws SQLException {
        final ItemScope is = itemScope( actor, itemKey );
        is.scope().require( Action.COMMENT, is.item() );
        is.scope().requireWritable();
        if ( body == null || body.isBlank() ) {
            throw new WorkValidationException( "body", "A comment cannot be empty." );
        }
        return store.addComment( is.item(), actor.login(), actor.kind(), body,
            mentionedMembers( is.scope().project().id(), body ) );
    }

    public Comment editComment( final Actor actor, final long commentId, final String body ) throws SQLException {
        final ItemScope is = commentScope( actor, commentId );
        final Comment c = store.comment( commentId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        is.scope().require( Action.COMMENT, is.item() );
        if ( !c.author().equals( actor.login() ) ) {
            denied( Action.COMMENT );
            throw new WorkDeniedException( "Only the author may edit a comment." );
        }
        is.scope().requireWritable();
        if ( body == null || body.isBlank() ) {
            throw new WorkValidationException( "body", "A comment cannot be empty." );
        }
        return store.editComment( commentId, body, actor.login() );
    }

    public void removeComment( final Actor actor, final long commentId ) throws SQLException {
        final ItemScope is = commentScope( actor, commentId );
        final Comment c = store.comment( commentId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        is.scope().require( Action.COMMENT, is.item() );
        final boolean lead = WorkPolicy.may( actor, is.scope().role(), Action.MANAGE_PROJECT, is.item() ).allowed();
        if ( !c.author().equals( actor.login() ) && !lead ) {
            denied( Action.COMMENT );
            throw new WorkDeniedException( "Only the author or a project lead may remove a comment." );
        }
        is.scope().requireWritable();
        store.removeComment( commentId, actor.login() );
    }

    public Link addLink( final Actor actor, final String itemKey, final String kind, final String target )
            throws SQLException {
        final ItemScope is = itemScope( actor, itemKey );
        is.scope().require( Action.LINK, is.item() );
        is.scope().requireWritable();
        if ( kind == null || !LINK_KINDS.contains( kind ) ) {
            throw new WorkValidationException( "kind", "kind must be one of " + new java.util.TreeSet<>( LINK_KINDS ) );
        }
        if ( target == null || target.isBlank() || target.length() > MAX_LINK_TARGET
                || "url".equals( kind ) && !target.matches( "(?i)^https?://\\S+$" ) ) {
            throw new WorkValidationException( "target", "target is missing, too long, or not an http(s) address." );
        }
        String stored = target.trim();
        if ( "blocked_by".equals( kind ) ) {
            stored = sameProjectBlocker( is, stored );
        }
        return store.addLink( is.item(), kind, stored, actor.login() );
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
        final ItemScope is = itemScopeById( actor, link.itemId() );
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

    // ---- helpers

    private void require( final Actor actor, final Action action ) {
        final Decision d = WorkPolicy.may( actor, null, action, null );
        if ( !d.allowed() ) {
            denied( action );
            throw new WorkDeniedException( d.reason() );
        }
    }

    private static void requireName( final String name ) {
        if ( name == null || name.isBlank() ) {
            throw new WorkValidationException( "name", "A name is required." );
        }
    }

    /** A comment's item scope; a removed comment is as good as absent. */
    private ItemScope commentScope( final Actor actor, final long commentId ) throws SQLException {
        final Comment c = store.comment( commentId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        final ItemScope is = itemScopeById( actor, c.itemId() );
        if ( c.removedAt() != null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return is;
    }

    /** The filter pinned to the project, so a caller-supplied id can never reach another project's rows. */
    private static ItemFilter scoped( final Scope s, final ItemFilter f ) {
        if ( f == null ) {
            return new ItemFilter( s.project().id(), null, null, null, null, null, null, null, 0 );
        }
        return new ItemFilter( s.project().id(), f.statuses(), f.assignee(), f.productId(), f.level(),
            f.parentId(), f.text(), f.dueBefore(), f.limit() );
    }

    private static Item withVersion( final Item i, final int version ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), version, i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    /** {@code @login} tokens in the body, de-duplicated, kept only when the login is a project member. */
    private List< String > mentionedMembers( final long projectId, final String body ) throws SQLException {
        final Set< String > logins = new LinkedHashSet<>();
        final Matcher m = MENTION.matcher( body );
        while ( m.find() ) {
            logins.add( m.group( 1 ).replaceAll( "[.\\-_]+$", "" ) );
        }
        final List< String > kept = new ArrayList<>();
        for ( final String login : logins ) {
            if ( !login.isEmpty() && isMember( projectId, login ) ) {
                kept.add( login );
            }
        }
        return kept;
    }

    private ItemView view( final Scope s, final Item item ) throws SQLException {
        final String html = item.body() == null || item.body().isBlank() ? "" : renderer.toHtml( item.body() );
        final List< Product > products = store.products();
        final Item parent = item.parentId() == null ? null : store.itemById( item.parentId() ).orElse( null );
        final String productKey = item.productId() == null ? null : productKey( products, item.productId() );
        final List< String > derived = new ArrayList<>();
        if ( item.level() == WorkLevel.EPIC ) {
            for ( final long productId : store.productIdsUnder( item.id() ) ) {
                final String k = productKey( products, productId );
                if ( k != null ) {
                    derived.add( k );
                }
            }
        }
        return new ItemView( item, html, s.project().key(), parent == null ? null : parent.key(), productKey,
            derived, store.comments( item.id() ), store.links( item.id() ), store.isBlocked( item.id() ),
            s.effectiveRole() );
    }

    private static String productKey( final List< Product > products, final long id ) {
        final Optional< Product > p = products.stream().filter( x -> x.id() == id ).findFirst();
        return p.map( Product::key ).orElse( null );
    }
}
