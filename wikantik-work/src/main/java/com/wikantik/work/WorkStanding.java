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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An actor's standing: its effective role in a project and the projects it can see. The one place the sponsor
 * ceiling is applied, to reading as much as to acting.
 *
 * <p>A person's standing is their own member rows (direct or through a group). An agent's is the lower of two
 * things: what its own member row gives it, and what its sponsor, acting as themselves right now, would have. The
 * sponsor is a ceiling and never a source: an agent with no member row has nothing, whoever sponsors it; an agent
 * with no sponsor has nothing, whatever its rows say. A sponsor who is a wiki admin may do everything, so limits
 * nothing. Nothing here is stored or cached: the sponsor on the actor was resolved for this request.
 *
 * <p>A {@link WorkRole#REPORTER} row is no standing: {@link #roleIn}, {@link #seesProject} and
 * {@link #visibleProjects} never return or count one. A reporter is answered only by the separately named
 * {@link #mayFile}, {@link #canSee}, {@link #reportsOnly}, {@link #reportableProjects} and
 * {@link #outsideReporters}, which the three above never call; each of those refuses anything that is not a person
 * (an agent named like a reporter, or sponsored by one, gets nothing).
 *
 * <p>No other class of the module asks a store for a role or for the visible projects
 * ({@code WorkStandingSourceRuleTest}).
 */
final class WorkStanding {

    private final WorkStore store;

    WorkStanding( final WorkStore store ) {
        this.store = store;
    }

    /**
     * The actor's effective role in the project: viewer or above, empty when it has none (the project is then hidden
     * from it). A reporter row is no standing.
     */
    Optional< WorkRole > roleIn( final long projectId, final Actor actor ) throws SQLException {
        if ( actor.kind() == Actor.Kind.SYSTEM ) {
            return Optional.empty();
        }
        if ( actor.kind() != Actor.Kind.AGENT ) {
            return standingRole( projectId, actor );
        }
        final Actor sponsor = actor.sponsor();
        if ( sponsor == null ) {
            return Optional.empty();
        }
        final Optional< WorkRole > own = standingRole( projectId, actor );
        if ( own.isEmpty() || sponsor.instanceAdmin() ) {
            return own;
        }
        final Optional< WorkRole > ceiling = standingRole( projectId, sponsor );
        if ( ceiling.isEmpty() ) {
            return Optional.empty();
        }
        return ceiling.get().atLeast( own.get() ) ? own : ceiling;
    }

    /** Whether the actor stands in the project: a wiki admin, or viewer or above. */
    boolean seesProject( final long projectId, final Actor actor ) throws SQLException {
        return actor.instanceAdmin()
            || roleIn( projectId, actor ).filter( WorkRole.STANDING::contains ).isPresent();
    }

    /** The projects the actor can see: for an agent, those it is a member of that its sponsor can see too. */
    List< Project > visibleProjects( final Actor actor ) throws SQLException {
        if ( actor.kind() == Actor.Kind.SYSTEM ) {
            return List.of();
        }
        if ( actor.kind() != Actor.Kind.AGENT ) {
            return store.visibleProjects( actor );
        }
        final Actor sponsor = actor.sponsor();
        if ( sponsor == null ) {
            return List.of();
        }
        final List< Project > own = store.visibleProjects( actor );
        if ( own.isEmpty() || sponsor.instanceAdmin() ) {
            return own;
        }
        final Set< Long > sponsors = new HashSet<>();
        for ( final Project p : store.visibleProjects( sponsor ) ) {
            sponsors.add( p.id() );
        }
        return own.stream().filter( p -> sponsors.contains( p.id() ) ).toList();
    }

    /** The stored role, as standing: viewer or above. */
    private Optional< WorkRole > standingRole( final long projectId, final Actor actor ) throws SQLException {
        return store.roleFor( projectId, actor ).filter( r -> r.atLeast( WorkRole.VIEWER ) && WorkRole.STANDING.contains( r ) );
    }

    /**
     * The role a person's own rows give, reporter included: their {@code user} row and the {@code group} rows of
     * their groups, never an agent row. Empty for anything that is not a person.
     */
    private Optional< WorkRole > rawRole( final long projectId, final Actor actor ) throws SQLException {
        return actor.kind() == Actor.Kind.HUMAN ? store.roleFor( projectId, actor ) : Optional.empty();
    }

    /** Whether the person may file into the project: a wiki admin, or any row of theirs (reporter included). Never an agent. */
    boolean mayFile( final long projectId, final Actor actor ) throws SQLException {
        return actor.kind() == Actor.Kind.HUMAN && ( actor.instanceAdmin() || rawRole( projectId, actor ).isPresent() );
    }

    /**
     * Whether the person may see this item: a wiki admin, anyone with standing in its project, or a person with any
     * row in the project who reported it (their own report only; an agent's report under the same name is not theirs).
     * Never true for an agent.
     */
    boolean canSee( final Actor actor, final Item item ) throws SQLException {
        if ( actor.kind() != Actor.Kind.HUMAN ) {
            return false;
        }
        if ( actor.instanceAdmin() || roleIn( item.projectId(), actor ).isPresent() ) {
            return true;
        }
        return item.reporterAgentId() == null && actor.login() != null && actor.login().equals( item.reporter() )
            && rawRole( item.projectId(), actor ).isPresent();
    }

    /** Whether the person holds nothing in the project but the reporter role (and is not a wiki admin). */
    boolean reportsOnly( final long projectId, final Actor person ) throws SQLException {
        return person.kind() == Actor.Kind.HUMAN && !person.instanceAdmin()
            && rawRole( projectId, person ).filter( r -> r == WorkRole.REPORTER ).isPresent();
    }

    /**
     * Whether the person follows this item as its reporter and as nothing more: they hold only the reporter role in
     * its project and filed it themselves (an agent's report under their name is not theirs). False for anyone with
     * standing (they follow the item as members do), for a wiki admin and for anything that is not a person. The
     * notification paths ask it per item, never per project.
     */
    boolean followsAsReporter( final Actor person, final Item item ) throws SQLException {
        return person.kind() == Actor.Kind.HUMAN && item.reporterAgentId() == null && person.login() != null
            && person.login().equals( item.reporter() ) && reportsOnly( item.projectId(), person );
    }

    /**
     * The projects, not archived, where the person holds nothing but the reporter role: those they may file into and
     * cannot see. {@code visibleIds} must be the ids of {@link #visibleProjects} of the same person, just read (the
     * caller needs them too; this saves reading them twice on every unread poll). Empty for an agent and for a wiki
     * admin (who sees every project).
     */
    Set< Long > reportOnlyProjects( final Actor person, final Set< Long > visibleIds ) throws SQLException {
        final Set< Long > out = new LinkedHashSet<>();
        if ( person.kind() != Actor.Kind.HUMAN || person.instanceAdmin() ) {
            return out;
        }
        for ( final Project p : reportableProjects( person ) ) {
            if ( !visibleIds.contains( p.id() ) ) {
                out.add( p.id() );
            }
        }
        return out;
    }

    /** The projects the person may file into (see {@link WorkIntakeStore#reportableProjects}); empty for an agent. */
    List< Project > reportableProjects( final Actor actor ) throws SQLException {
        return store.notifyStores().intake().reportableProjects( actor );
    }

    /**
     * Of the given logins (the reporters named in a response), those who are people holding only the reporter role in
     * the project. Bounded by the logins given: nothing is read when the project has no reporter row, and an
     * unresolved login is not outside.
     */
    Set< String > outsideReporters( final long projectId, final Collection< String > logins,
                                    final Function< String, Optional< WorkDirectory.Person > > people )
            throws SQLException {
        final Set< String > outside = new LinkedHashSet<>();
        if ( logins.isEmpty() || !store.notifyStores().intake().hasReporters( projectId ) ) {
            return outside;
        }
        for ( final String login : new LinkedHashSet<>( logins ) ) {
            final Optional< WorkDirectory.Person > person = login == null ? Optional.empty() : people.apply( login );
            if ( person.isPresent() && reportsOnly( projectId,
                    Actor.human( person.get().login(), person.get().groups(), person.get().admin() ) ) ) {
                outside.add( login );
            }
        }
        return outside;
    }
}
