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

    private static final String NOT_FOUND = WorkScopes.NOT_FOUND;
    private static final int PRODUCT_HISTORY_LIMIT = 100;

    /**
     * An item together with everything a reader needs to show it; {@code archived}: its project is read-only;
     * {@code wikiCluster}: the wiki cluster of the item's product, null when it has none.
     */
    public record ItemView( Item item, String bodyHtml, String projectKey, String parentKey, String productKey,
                            List< String > derivedProductKeys, List< CommentView > comments, List< Link > links,
                            boolean blocked, WorkRole viewerRole, boolean archived, String wikiCluster ) {
    }

    /** A visible project and the caller's role in it. */
    public record ProjectRole( Project project, WorkRole role ) {
    }

    /** An item with what its card shows: comment count, blocked flag, product key and parent key. */
    public record ItemSummary( Item item, int commentCount, boolean blocked, String productKey, String parentKey ) {
    }

    /** A board column whose items are summaries. */
    public record SummaryColumn( WorkStatus status, int total, List< ItemSummary > items ) {
    }

    private final WorkStore store;
    private final BoardOrder boardOrder;
    private final WorkScopes scopes;
    private final ItemViews views;
    private final WorkPeople people;
    private final Clock clock;
    private final ItemPatchApplier applier;
    private final WorkRegistryService registry;
    private final WorkClaimsService claims;
    private final GuardedListener listener;
    private final Acknowledgements acknowledgements;
    private final WorkChangesService changes;
    private final WorkDeploymentsService deployments;
    private final WorkFeatures features;

    /** A newly registered or rotated agent and its API token, which is shown once and never stored or logged. */
    public record AgentWithToken( Agent agent, String token ) {
        @Override
        public String toString() {
            return "AgentWithToken[agent=" + agent.name() + ", token=***]";
        }
    }

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
        this( store, renderer, directory, clock, WorkSettings.DEFAULTS, new WorkListener() {
            @Override
            public void denied( final Action a ) {
                if ( denialListener != null ) {
                    denialListener.accept( a );
                }
            }
        } );
    }

    /**
     * @param settings claim tunables (used by the claim methods)
     * @param listener observer of denials and claim outcomes; null means none
     */
    public WorkService( final WorkStore store, final WorkRenderer renderer, final WorkDirectory directory,
                        final Clock clock, final WorkSettings settings, final WorkListener listener ) {
        this( store, renderer, directory, clock, settings, listener, WorkAudit.NONE );
    }

    /** @param audit the admin audit trail registry changes are written to */
    public WorkService( final WorkStore store, final WorkRenderer renderer, final WorkDirectory directory,
                        final Clock clock, final WorkSettings settings, final WorkListener listener,
                        final WorkAudit audit ) {
        final GuardedListener observer = new GuardedListener( listener );
        this.store = store;
        this.boardOrder = new BoardOrder( store );
        this.scopes = new WorkScopes( store, directory, observer::denied, observer::gate );
        this.scopes.onDeployment( observer::deployment );
        this.people = new WorkPeople( directory );
        this.applier = new ItemPatchApplier( store, scopes::isMember );
        this.registry = new WorkRegistryService( store, scopes, people, clock, observer,
            audit == null ? WorkAudit.NONE : audit );
        this.views = new ItemViews( store, renderer, people );
        this.acknowledgements = new Acknowledgements( store, scopes, views );
        this.changes = new WorkChangesService( store, scopes );
        this.deployments = new WorkDeploymentsService( store, scopes );
        this.features = new WorkFeatures( store, scopes, views, clock );
        this.claims = new WorkClaimsService( store, scopes, people, views, applier, settings, observer );
        this.clock = clock;
        this.listener = observer;
    }

    // ---- projects

    public List< Project > listProjects( final Actor actor ) throws SQLException {
        return scopes.standing().visibleProjects( actor );
    }

    /** {@link #listProjects} with the caller's role in each, resolved in the same pass. */
    public List< ProjectRole > listProjectsWithRoles( final Actor actor ) throws SQLException {
        final List< ProjectRole > out = new ArrayList<>();
        for ( final Project p : scopes.standing().visibleProjects( actor ) ) {
            final WorkRole role = scopes.standing().roleIn( p.id(), actor ).orElse( null );
            if ( role != null || actor.instanceAdmin() ) {
                out.add( new ProjectRole( p, new Scope( actor, p, role, scopes::denied ).effectiveRole() ) );
            }
        }
        return out;
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
        WorkInputs.requireUnreservedKey( key );
        WorkInputs.requireName( name );
        if ( store.project( key ).isPresent() ) {
            throw new WorkConflictException( "Project " + key + " already exists.", WorkConflictException.EXISTS );
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
            defaultAutonomy == null ? s.project().defaultAutonomy() : defaultAutonomy, actor );
    }

    public void archiveProject( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_INSTANCE, null );
        s.requireWritable();
        listener.released( store.archiveProject( s.project().id(), clock.instant(), actor ) );
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
        if ( "agent".equals( principalKind ) ) {
            WorkInputs.requireAgentRole( role );
        }
        listener.released( store.putMember( s.project().id(),
            registry.memberPrincipal( actor, principal, principalKind, true ), principalKind, role, actor,
            claims.sponsorsHoldingClaimsIn( s.project().id() ) ) );
    }

    public void removeMember( final Actor actor, final String key, final String principal,
                              final String principalKind ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        WorkInputs.requireMemberRef( principal, principalKind );
        listener.released( store.removeMember( s.project().id(),
            registry.memberPrincipal( actor, principal, principalKind, false ), principalKind, actor,
            claims.sponsorsHoldingClaimsIn( s.project().id() ) ) );
    }

    public WorkRole roleIn( final Actor actor, final String key ) throws SQLException {
        final Scope s = scopes.scope( actor, key );
        s.require( Action.VIEW, null );
        return s.effectiveRole();
    }

    // ---- products

    /**
     * The full catalogue for a wiki admin. Anyone who belongs to at least one project sees each product's key, name
     * and archived state only (and its id, which never leaves the server); everyone else sees nothing.
     */
    public List< Product > listProducts( final Actor actor ) throws SQLException {
        if ( actor.instanceAdmin() ) {
            return store.products();
        }
        if ( scopes.standing().visibleProjects( actor ).isEmpty() ) {
            return List.of();
        }
        return store.products().stream().map( p -> new Product( p.id(), p.key(), p.name(), null, null, null, null,
            null, p.archivedAt() ) ).toList();
    }

    public Product createProduct( final Actor actor, final Product draft ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product product = WorkInputs.newProduct( draft );
        if ( store.product( draft.key() ).isPresent() ) {
            throw new WorkConflictException( "Product " + draft.key() + " already exists.", WorkConflictException.EXISTS );
        }
        return store.createProduct( product );
    }

    public void updateProduct( final Actor actor, final Product product ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( product.key() )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + product.key() ) );
        store.updateProduct( existing, WorkInputs.updatedProduct( existing, product ), actor );
    }

    public void archiveProduct( final Actor actor, final String key ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( key )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + key ) );
        store.archiveProduct( existing, clock.instant(), actor );
    }

    /** The product's recorded setting changes, newest first. Wiki admins only; an unknown key is "not found". */
    public List< ProductEvent > productHistory( final Actor actor, final String key ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Product existing = store.product( key )
            .orElseThrow( () -> new WorkNotFoundException( "No such product: " + key ) );
        return store.productEvents( existing.id(), PRODUCT_HISTORY_LIMIT );
    }

    // ---- queues, agents and identity

    /** Queues, agents and the identity an API key stands for. */
    public WorkRegistryService registry() {
        return registry;
    }

    /** Claims, release, hand-off and the claimable lists. */
    public WorkClaimsService claims() {
        return claims;
    }

    /** Deployments: a product version going to an environment, carrying items. */
    public WorkDeploymentsService deployments() {
        return deployments;
    }

    /** Change records: branches of work from open to merged. */
    public WorkChangesService changes() {
        return changes;
    }

    /** Reads beyond the core item calls: a project's labels. */
    public WorkFeatures features() {
        return features;
    }

    // ---- items

    public ItemView createItem( final Actor actor, final ItemDraft draft ) throws SQLException {
        final Scope s = scopes.scope( actor, draft.projectKey() );
        s.require( Action.CREATE_ITEM, null );
        s.requireWritable();
        final Item next = applier.forCreate( s.project(), draft.assignee() == null || draft.assignee().isBlank()
            ? draft : new ItemDraft( draft.projectKey(), draft.level(), draft.parentKey(), draft.productKey(),
                draft.kind(), draft.title(), draft.body(), draft.priority(), draft.dueOn(),
                people.assigneeLogin( actor, draft.assignee() ), draft.queue(), draft.type(),
                draft.labels() ),
            WorkPolicy.startStatus( actor, draft.level() ) );
        return views.view( s, store.createItem( s.project(), next, actor ) );
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
        final Action action = ItemPatchApplier.MOVE_KEYS.containsAll( changes.keySet() ) ? Action.MOVE
            : Action.EDIT_ITEM;
        s.require( action, is.item() );
        scopes.gates().requireFieldGates( s, is.item(), changes );
        s.requireWritable();
        final CloseRules rules = changes.containsKey( "status" )
            ? scopes.gates().requireStatusChange( s, is.item(), WorkPolicy.namedStatus( changes.get( "status" ) ) )
            : CloseRules.NONE;
        final Item next = applier.apply( s.project(), is.item(), people.withAssigneeLogin( actor, changes ), patch.note() );
        scopes.gates().requireNoLoosening( s, is.item(), next );
        final Long beforeId = boardOrder.beforeCard( next, changes );
        final Item stamped = WorkInputs.withVersion( is.item(), patch.expectedVersion() );
        Item saved = claims.write( stamped, next, actor, patch.note(), false, action, rules );
        saved = boardOrder.settle( saved, changes, beforeId );
        return views.view( s, saved );
    }

    /** The item's effective autonomy policy and whether it waits for a person's acknowledgement. */
    public ItemPolicyView policyOf( final Actor actor, final String key ) throws SQLException {
        return acknowledgements.policyOf( actor, key );
    }

    /** A person acknowledges an item an agent closed with no person in its path; a no-op when none is owed. */
    public ItemView acknowledge( final Actor actor, final String key ) throws SQLException {
        return acknowledgements.acknowledge( actor, key );
    }

    public List< Item > items( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.items( views.pinned( actor, s, filter ) );
    }

    public List< BoardColumn > board( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.board( s.project().id(), views.pinned( actor, s, filter ) );
    }

    /** {@link #items} with card extras, computed for the whole result in one batch. */
    public List< ItemSummary > itemSummaries( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return views.summarise( s.project().id(), store.items( views.pinned( actor, s, filter ) ) );
    }

    /** {@link #board} with card extras, computed for every column's items in one batch. */
    public List< SummaryColumn > boardSummaries( final Actor actor, final String projectKey, final ItemFilter filter )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return views.summaryColumns( s.project().id(), store.board( s.project().id(), views.pinned( actor, s, filter ) ) );
    }

    public List< ItemRevision > revisions( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return store.revisions( is.item().id() );
    }

    public List< WorkEvent > events( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        return EventKeys.resolve( store, is.item().projectId(), store.itemEvents( is.item().id() ) );
    }

    public List< WorkEvent > activity( final Actor actor, final String projectKey, final Instant before,
                                       final int limit ) throws SQLException {
        return activity( actor, projectKey, before, null, null, null, limit );
    }

    /**
     * As above; {@code beforeId} (with {@code before}) makes the cursor the compound {@code (at, id)},
     * {@code itemKey}, when set, keeps only that item's events (an item outside the project is not found), and
     * {@code types}, when not null or empty, only events of those types. An unknown type is refused before any
     * lookup, so the refusal is the same for a project that does not exist or one the caller cannot see.
     */
    public List< WorkEvent > activity( final Actor actor, final String projectKey, final Instant before,
                                       final Long beforeId, final String itemKey, final Set< String > types,
                                       final int limit ) throws SQLException {
        WorkInputs.requireEventTypes( types, WorkEvent.ALL_TYPES );
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        Long itemId = null;
        if ( itemKey != null && !itemKey.isBlank() ) {
            final ItemScope is = scopes.itemScope( actor, itemKey );
            if ( is.item().projectId() != s.project().id() ) {
                throw new WorkNotFoundException( NOT_FOUND );
            }
            itemId = is.item().id();
        }
        return EventKeys.resolve( store, s.project().id(), store.projectEvents( s.project().id(), before, beforeId,
            itemId, types, WorkInputs.activityLimit( limit ) ) );
    }

    /**
     * The assignment log: assignments, hand-offs, status changes, claims, renewals, releases, expiries and agent
     * membership, newest first, over exactly the projects {@link #listProjectsWithRoles} returns for the caller
     * (archived ones included, their rows marked). A caller who can see no project gets an empty list, not an
     * error. A {@code projectKey} the caller cannot see is as unknown as one that does not exist.
     */
    public List< AssignmentLogEvent > assignmentLog( final Actor actor, final AssignmentLogQuery query )
            throws SQLException {
        AssignmentLogs.validate( query );
        return AssignmentLogs.read( store, query, listProjectsWithRoles( actor ).stream().map( ProjectRole::project ).toList() );
    }

    // ---- discussion

    /** Mentions resolve to members only; see {@link WorkPeople} for the token and the rewriting. */
    public CommentView addComment( final Actor actor, final String itemKey, final String body ) throws SQLException {
        final ItemScope is = scopes.itemScope( actor, itemKey );
        is.scope().require( Action.COMMENT, is.item() );
        is.scope().requireWritable();
        WorkInputs.requireBody( body );
        final WorkPeople.Mentions m = people.mentions( actor, body );
        final long projectId = is.scope().project().id();
        return views.comment( store.addComment( is.item(), actor, m.body(),
            WorkInputs.ofMembers( m.logins(), login -> scopes.isMember( projectId, login ) ) ) );
    }

    /** The mentions are worked out again from the new text: added names wait on it, removed ones stop. */
    public CommentView editComment( final Actor actor, final long commentId, final String body ) throws SQLException {
        final ItemScope is = scopes.commentScope( actor, commentId );
        final Comment c = scopes.comment( commentId );
        is.scope().require( Action.COMMENT, is.item() );
        scopes.requireAuthor( actor, c, "Only the author may edit a comment." );
        is.scope().requireWritable();
        WorkInputs.requireBody( body );
        final WorkPeople.Mentions m = people.mentions( actor, body );
        final long projectId = is.scope().project().id();
        return views.comment( store.editComment( commentId, m.body(), actor,
            WorkInputs.ofMembers( m.logins(), login -> scopes.isMember( projectId, login ) ) ) );
    }

    public void removeComment( final Actor actor, final long commentId ) throws SQLException {
        final ItemScope is = scopes.commentScope( actor, commentId );
        final Comment c = scopes.comment( commentId );
        is.scope().require( Action.COMMENT, is.item() );
        if ( !WorkPolicy.may( actor, is.scope().role(), Action.MANAGE_PROJECT, is.item() ).allowed() ) {
            scopes.requireAuthor( actor, c, "Only the author or a project lead may remove a comment." );
        }
        is.scope().requireWritable();
        store.removeComment( commentId, actor );
    }

    public Link addLink( final Actor actor, final String itemKey, final String kind, final String target )
            throws SQLException {
        final ItemScope is = scopes.itemScope( actor, itemKey );
        is.scope().require( Action.LINK, is.item() );
        is.scope().requireWritable();
        final String stored = WorkInputs.linkTarget( kind, target );
        return store.addLink( is.item(), kind, "blocked_by".equals( kind ) ? applier.sameProjectBlocker( is, stored ) : stored,
            actor );
    }

    public void removeLink( final Actor actor, final long linkId ) throws SQLException {
        final Link link = store.link( linkId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        final ItemScope is = scopes.itemScopeById( actor, link.itemId() );
        is.scope().require( Action.LINK, is.item() );
        is.scope().requireWritable();
        store.removeLink( linkId, actor );
    }

    // ---- me

    /**
     * What waits on the actor: open items assigned to a person (each with the hand-off that put it there) and items
     * that mention them, across visible projects only. An agent has no such lane: it works from its queues and
     * claims, never from a name that may be shared with a person.
     */
    public List< WaitingItem > waitingOn( final Actor actor ) throws SQLException {
        if ( actor.kind() == Actor.Kind.AGENT ) {
            return List.of();
        }
        final List< Project > visible = scopes.standing().visibleProjects( actor );
        final Set< Long > ids = visible.stream().map( Project::id )
            .collect( Collectors.toCollection( LinkedHashSet::new ) );
        if ( ids.isEmpty() ) {
            return List.of();
        }
        final Map< Long, Item > byId = new LinkedHashMap<>();
        for ( final Item i : store.assignedOpen( actor.login(), ids ) ) {
            byId.putIfAbsent( i.id(), i );
        }
        final Set< Long > assigned = new LinkedHashSet<>( byId.keySet() );
        for ( final Item i : store.mentioning( actor.login(), ids ) ) {
            byId.putIfAbsent( i.id(), i );
        }
        final List< Item > ordered = new ArrayList<>( byId.values() );
        ordered.sort( Comparator.comparing( Item::updatedAt, Comparator.nullsLast( Comparator.reverseOrder() ) ) );
        final Map< Long, WorkEvent > handoffs = store.lastHandoffs( assigned );
        final List< WaitingItem > waiting = new ArrayList<>( ordered.stream().map( i -> assigned.contains( i.id() )
            ? new WaitingItem( i, WaitingItem.ASSIGNED, handoffs.get( i.id() ) )
            : new WaitingItem( i, WaitingItem.MENTIONED, null ) ).toList() );
        waiting.addAll( acknowledgements.awaiting( actor, visible ) );
        return waiting;
    }
}
