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
import java.util.regex.Pattern;

/**
 * Resolves who is asking and what they can see: the project, item or comment a request names, through project
 * visibility. Everything a {@link WorkService} method starts from. Unknown and hidden are one
 * {@link WorkNotFoundException} with one message.
 */
final class WorkScopes {

    static final String NOT_FOUND = "No such project, item or comment.";
    private static final Pattern ITEM_KEY = Pattern.compile( "^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,9})$" );

    /** A resolved project and the actor's standing in it; the one thing every method starts from. */
    record Scope( Actor actor, Project project, WorkRole role, Consumer< Action > onDenied ) {

        /**
         * The role to show: a person who is a wiki admin counts as lead whatever their member row says (they may do
         * everything a lead may); an agent shows its own row (it is denied management actions regardless).
         */
        WorkRole effectiveRole() {
            if ( actor.admin() && actor.kind() == Actor.Kind.HUMAN ) {
                return WorkRole.LEAD;
            }
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
                throw new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED );
            }
        }

        /** The filter pinned to the project, so a caller-supplied id can never reach another project's rows. */
        ItemFilter pin( final ItemFilter f ) {
            if ( f == null ) {
                return new ItemFilter( project.id(), null, null, null, null, null, null, null, 0, null, null );
            }
            return f.with( project.id(), f.assignee() );
        }
    }

    record ItemScope( Scope scope, Item item ) {
    }

    private final WorkStore store;
    private final WorkDirectory directory;
    private final Consumer< Action > denialListener;

    WorkScopes( final WorkStore store, final WorkDirectory directory, final Consumer< Action > denialListener ) {
        this.store = store;
        this.directory = directory;
        this.denialListener = denialListener == null ? action -> { } : denialListener;
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
        final WorkRole role = store.roleFor( project.id(), actor ).orElse( null );
        if ( role == null && !actor.instanceAdmin() ) {
            throw new WorkNotFoundException( NOT_FOUND );
        }
        return new Scope( actor, project, role, this::denied );
    }

    /** Resolves an item key through its project's visibility; the item is only looked up once visible. */
    ItemScope itemScope( final Actor actor, final String itemKey ) throws SQLException {
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

    /** The groups the login belongs to; never null. */
    Set< String > groupsOf( final String login ) {
        final Set< String > groups = directory.groupsOf( login );
        return groups == null ? Set.of() : groups;
    }

    boolean isMember( final long projectId, final String login ) throws SQLException {
        final Set< String > groups = directory.groupsOf( login );
        return store.roleFor( projectId, Actor.human( login, groups == null ? Set.of() : groups, false ) )
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

    /** Denies, and records the denial, unless {@code actor} wrote {@code comment}. */
    void requireAuthor( final Actor actor, final Comment comment, final String message ) {
        if ( !comment.author().equals( actor.login() ) ) {
            denied( Action.COMMENT );
            throw new WorkDeniedException( message );
        }
    }
}
