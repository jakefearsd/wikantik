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
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Resolves who is asking and what they can see: the project, item or comment a request names, through project
 * visibility. Everything a {@link WorkService} method starts from. Unknown and hidden are one
 * {@link WorkNotFoundException} with one message.
 */
final class WorkScopes {

    static final String NOT_FOUND = "No such project, item or comment.";
    /** A resolved project and the actor's standing in it; the one thing every method starts from. */
    record Scope( Actor actor, Project project, WorkRole role, Consumer< Action > onDenied ) {

        /**
         * The role to show: a person who is a wiki admin counts as lead whatever their member row says (they may do
         * everything a lead may); an agent shows its own row (it is denied management actions regardless).
         */
        WorkRole effectiveRole() {
            if ( actor.instanceAdmin() ) {
                return WorkRole.LEAD;
            }
            return role;
        }

        /**
         * True for a person whose only standing here is the reporter row (and who is not a wiki admin): they act through
         * intake alone. Set only by {@link WorkScopes#intakeScope} and {@link WorkScopes#ownItemScope}.
         */
        boolean reporterOnly() {
            return role == WorkRole.REPORTER && !actor.instanceAdmin();
        }

        void require( final Action action, final Item item ) {
            final Decision d = WorkPolicy.may( actor, role, action, item );
            if ( !d.allowed() ) {
                onDenied.accept( action );
                throw new WorkDeniedException( d.reason() );
            }
        }

        /** Records a refusal and throws it. */
        void refuse( final Action action, final String reason ) {
            onDenied.accept( action );
            throw new WorkDeniedException( reason );
        }

        void requireWritable() {
            if ( project.archivedAt() != null ) {
                throw new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED );
            }
        }

        /** The filter pinned to the project, so a caller-supplied id can never reach another project's rows. */
        ItemFilter pin( final ItemFilter f ) {
            if ( f == null ) {
                return new ItemFilter( project.id(), null, null, null, null, null, null, null, 0, null, null, null, null, null );
            }
            return f.with( project.id(), f.assignee() );
        }
    }

    record ItemScope( Scope scope, Item item ) {
    }

    private final WorkStore store;
    private final WorkStanding standing;
    private final ItemGates gates;
    private final java.util.function.BiConsumer< String, Boolean > gateListener;
    private Consumer< String > deploymentListener = state -> { };
    private final WorkDirectory directory;
    private final Consumer< Action > denialListener;

    WorkScopes( final WorkStore store, final WorkDirectory directory, final Consumer< Action > denialListener ) {
        this( store, directory, denialListener, null );
    }

    /** @param gateListener told of every gate an agent meets and how it went; null for none */
    WorkScopes( final WorkStore store, final WorkDirectory directory, final Consumer< Action > denialListener,
                final java.util.function.BiConsumer< String, Boolean > gateListener ) {
        this.gateListener = gateListener == null ? ( gate, allowed ) -> { } : gateListener;
        this.store = store;
        this.standing = new WorkStanding( store );
        this.gates = new ItemGates( store, this );
        this.directory = directory;
        this.denialListener = denialListener == null ? action -> { } : denialListener;
    }

    /**
     * Reports how an agent fared at a gate and refuses when it did not pass. A person's passage is not a gate
     * decision and is not reported.
     */
    void decide( final Scope s, final String gate, final Action action, final Decision d ) {
        decide( s, gate, d );
        if ( !d.allowed() ) {
            s.refuse( action, d.reason() );
        }
    }

    /** Reports that a deployment reached a state. */
    void deploymentReached( final DeploymentState state ) {
        deploymentListener.accept( state.wire() );
    }

    /** Reports how an agent fared at a gate that has no single project scope (a deployment spans several). */
    void gateDecided( final Actor actor, final String gate, final boolean allowed ) {
        if ( actor.kind() == Actor.Kind.AGENT ) {
            gateListener.accept( gate, allowed );
        }
    }

    /** Reports how an agent fared at a gate, for a caller that answers the refusal itself. */
    void decide( final Scope s, final String gate, final Decision d ) {
        if ( s.actor().kind() == Actor.Kind.AGENT ) {
            gateListener.accept( gate, d.allowed() );
        }
    }

    /** Sets who is told of deployment states reached; the service wires its guarded listener here. */
    void onDeployment( final Consumer< String > listener ) {
        this.deploymentListener = listener;
    }

    /** The autonomy checks on an item write. */
    ItemGates gates() {
        return gates;
    }

    /** The one resolver of roles and visible projects; it applies an agent's sponsor ceiling. */
    WorkStanding standing() {
        return standing;
    }

    void denied( final Action action ) {
        denialListener.accept( action );
    }

    /** Resolves the project and the actor's role; unknown and hidden are the same {@link WorkNotFoundException}. */
    Scope scope( final Actor actor, final String projectKey ) throws SQLException {
        final Project project = projectKey == null ? null
            : store.project( projectKey.trim().toUpperCase( Locale.ROOT ) ).orElse( null );
        if ( project == null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final WorkRole role = standing.roleIn( project.id(), actor ).orElse( null );
        if ( role == null && !actor.instanceAdmin() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new Scope( actor, project, role, this::denied );
    }

    /** Resolves an item key through its project's visibility; the item is only looked up once visible. */
    ItemScope itemScope( final Actor actor, final String itemKey ) throws SQLException {
        final Matcher m = itemKey == null ? null : ItemKeys.PATTERN.matcher( itemKey.trim() );
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

    /**
     * The project a person files into: the ordinary scope for anyone with standing (or a wiki admin), else, for a
     * person holding any row in the project (a reporter's included), a scope with the reporter role. Anything else,
     * an agent included, is not found.
     */
    Scope intakeScope( final Actor actor, final String projectKey ) throws SQLException {
        final Project project = projectKey == null ? null
            : store.project( projectKey.trim().toUpperCase( Locale.ROOT ) ).orElse( null );
        if ( project == null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final WorkRole role = standing.roleIn( project.id(), actor ).orElse( null );
        if ( role != null || actor.instanceAdmin() ) {
            return new Scope( actor, project, role, this::denied );
        }
        if ( !standing.mayFile( project.id(), actor ) ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new Scope( actor, project, WorkRole.REPORTER, this::denied );
    }

    /**
     * An item key resolved for someone who may follow it: the ordinary scope for anyone with standing (or a wiki
     * admin), else, for a person who filed it, a scope with the reporter role. Every miss is the one not-found. The
     * only way a reporter reaches an item. Never pass its scope to {@code ItemViews.view}.
     */
    ItemScope ownItemScope( final Actor actor, final String itemKey ) throws SQLException {
        final Matcher m = itemKey == null ? null : ItemKeys.PATTERN.matcher( itemKey.trim() );
        if ( m == null || !m.matches() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final Project project = store.project( m.group( 1 ).toUpperCase( Locale.ROOT ) ).orElse( null );
        final Item item = project == null ? null : store.item( itemKey.trim() ).orElse( null );
        if ( item == null || item.projectId() != project.id() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        final WorkRole role = standing.roleIn( project.id(), actor ).orElse( null );
        if ( role != null || actor.instanceAdmin() ) {
            return new ItemScope( new Scope( actor, project, role, this::denied ), item );
        }
        if ( !standing.canSee( actor, item ) ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new ItemScope( new Scope( actor, project, WorkRole.REPORTER, this::denied ), item );
    }

    /** Resolves an item by numeric id (via a comment or link), again through project visibility. */
    ItemScope itemScopeById( final Actor actor, final long itemId ) throws SQLException {
        final Item item = store.itemById( itemId ).orElse( null );
        if ( item == null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return itemScope( actor, item.key() );
    }

    /** A comment's item scope; a removed comment is as good as absent. */
    ItemScope commentScope( final Actor actor, final long commentId ) throws SQLException {
        final Comment c = comment( commentId );
        final ItemScope is = itemScopeById( actor, c.itemId() );
        if ( c.removedAt() != null ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return is;
    }

    Comment comment( final long commentId ) throws SQLException {
        return store.comment( commentId ).orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
    }

    /** The mail address on the login's profile; see {@link WorkDirectory#email}. */
    java.util.Optional< String > email( final String login ) {
        return directory.email( login );
    }

    /** The account behind a login as the sponsor rule needs it; see {@link WorkDirectory#person}. */
    java.util.Optional< WorkDirectory.Person > person( final String login ) {
        return directory.person( login );
    }

    /** A login that differs from the name at most in letter case; see {@link WorkDirectory#loginIgnoringCase}. */
    java.util.Optional< String > loginIgnoringCase( final String name ) {
        return directory.loginIgnoringCase( name );
    }

    /** The public display name of a login. */
    String displayName( final String login ) {
        return directory.displayName( login );
    }

    /** The groups the login belongs to; never null. */
    Set< String > groupsOf( final String login ) {
        final Set< String > groups = directory.groupsOf( login );
        return groups == null ? Set.of() : groups;
    }

    boolean isMember( final long projectId, final String login ) throws SQLException {
        final Set< String > groups = directory.groupsOf( login );
        return standing.roleIn( projectId, Actor.human( login, groups == null ? Set.of() : groups, false ) )
            .isPresent();
    }

    /** An instance-wide action, for which no project role applies. */
    void requireInstance( final Actor actor, final Action action ) {
        final Decision d = WorkPolicy.may( actor, null, action, null );
        if ( !d.allowed() ) {
            denied( action );
            throw new WorkDeniedException( d.reason() );
        }
    }

    /**
     * Denies, and records the denial, unless {@code actor} wrote {@code comment}. An agent wrote it when the comment
     * carries the agent's id; a person when it carries no agent id and their login. Names are never compared across
     * the two, so an agent and a person who share a name cannot touch each other's comments.
     */
    void requireAuthor( final Actor actor, final Comment comment, final String message ) {
        final boolean wrote = actor.kind() == Actor.Kind.AGENT
            ? actor.agentId() != null && actor.agentId().equals( comment.authorAgentId() )
            : comment.authorAgentId() == null && comment.author().equals( actor.login() );
        if ( !wrote ) {
            denied( Action.COMMENT );
            throw new WorkDeniedException( message );
        }
    }
}
