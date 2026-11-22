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

import com.wikantik.work.WorkScopes.ItemScope;
import com.wikantik.work.WorkScopes.Scope;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Intake (Part 8): a person who is not a member of a project may file an item into it and follow the items they
 * filed. {@code WorkService.features().intake()}.
 *
 * <p>A reporter has no standing in the project ({@link WorkStanding#roleIn} never returns one), so none of the
 * ordinary calls answer them; everything they may do is here, through {@link WorkScopes#intakeScope} and
 * {@link WorkScopes#ownItemScope}, and what they read back is a {@link ReportedItemView}, never the full item view.
 * A person with a role in the project may file and comment here too; an agent files nothing.
 */
public final class WorkIntakeService {

    private static final int REPORTED_LIMIT = 200;
    /** The longest item body or comment the intake paths store, in characters (no other work text has a cap). */
    static final int MAX_TEXT = 20_000;

    private final WorkStore store;
    private final WorkScopes scopes;
    private final ItemViews views;
    private final WorkPeople people;
    private final ItemPatchApplier applier;
    private final WorkSettings settings;

    WorkIntakeService( final WorkStore store, final WorkScopes scopes, final ItemViews views,
                       final WorkPeople people, final ItemPatchApplier applier, final WorkSettings settings ) {
        this.store = store;
        this.scopes = scopes;
        this.views = views;
        this.people = people;
        this.applier = applier;
        this.settings = settings;
    }

    /**
     * The projects the caller may file into, each as its key and name with the caller's role in it (reporter when that
     * is all they hold): nothing else of the project (P8-16).
     */
    public List< ReportableProject > reportableProjects( final Actor actor ) throws SQLException {
        final List< ReportableProject > out = new ArrayList<>();
        for ( final Project p : scopes.standing().reportableProjects( actor ) ) {
            final WorkRole role = actor.instanceAdmin() ? WorkRole.LEAD
                : scopes.standing().roleIn( p.id(), actor ).orElse( WorkRole.REPORTER );
            out.add( new ReportableProject( p.key(), p.name(), role ) );
        }
        return out;
    }

    /**
     * Files an item: a draft, unassigned, unqueued, reported by the caller, created through the same rules as any
     * item a member creates. An unknown project, or one the caller has no row in, is not found.
     */
    public ReportedItemView submit( final Actor actor, final IntakeDraft draft ) throws SQLException {
        final Scope s = scopes.intakeScope( actor, draft.projectKey() );
        s.require( Action.FILE_ITEM, null );
        s.requireWritable();
        final WorkLevel level = draft.level() == null ? WorkLevel.STORY : draft.level();
        if ( level == WorkLevel.EPIC ) {
            throw new WorkValidationException( "level", "Intake files a story or a task." );
        }
        requireLength( draft.body() );
        final Item next = applier.forCreate( s.project(), new ItemDraft( draft.projectKey(), level, null, null, null,
            draft.title(), draft.body(), null, null, null, null,
            draft.type() == null ? WorkType.FEATURE : draft.type(), null ), WorkPolicy.startStatus( actor, level, true ) );
        // A person who only reports may file wikantik.work.intake.maxPerDay requests in a rolling 24 hours (0: no cap;
        // refusal = conflict intake_limit, REST 429); the count and the insert are one transaction under an advisory
        // lock on the person, so a parallel burst cannot pass it. People with a role in the project are not capped.
        final int cap = settings.intakeMaxPerDay();
        return reportedView( s, cap > 0 && s.reporterOnly()
            ? store.notifyStores().intake().createWithinDailyCap( s.project(), next, actor, cap )
            : store.createItem( s.project(), next, actor ) );
    }

    /** An item the caller filed (or any item, for a person with a role in its project), as a reporter is shown it. */
    public ReportedItemView item( final Actor actor, final String key ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( actor, key );
        return reportedView( is.scope(), is.item() );
    }

    /**
     * The items the caller filed in projects where a reporter row is all they hold, newest activity first, at most 200.
     * A person with a role in a project follows its items through the ordinary calls, so those are not listed here.
     */
    public List< ReportedItem > reported( final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.HUMAN ) {
            return List.of();
        }
        final Map< Long, Project > projects = new HashMap<>();
        for ( final Project p : scopes.standing().reportableProjects( actor ) ) {
            if ( scopes.standing().reportsOnly( p.id(), actor ) ) {
                projects.put( p.id(), p );
            }
        }
        if ( projects.isEmpty() ) {
            return List.of();
        }
        final List< Item > items = store.notifyStores().intake().reportedBy( actor.login(), projects.keySet(), REPORTED_LIMIT );
        final Map< Long, List< Long > > idsByProject = new LinkedHashMap<>();
        for ( final Item i : items ) {
            idsByProject.computeIfAbsent( i.projectId(), k -> new ArrayList<>() ).add( i.id() );
        }
        final Map< Long, WorkStore.ItemExtras > extras = new HashMap<>();
        for ( final Map.Entry< Long, List< Long > > e : idsByProject.entrySet() ) {
            extras.putAll( store.itemExtras( e.getKey(), e.getValue() ) );
        }
        final List< ReportedItem > out = new ArrayList<>();
        for ( final Item i : items ) {
            final WorkStore.ItemExtras e = extras.get( i.id() );
            final Project p = projects.get( i.projectId() );
            out.add( new ReportedItem( i.key(), p.key(), p.name(), i.level(), i.type(), i.status(), i.title(), i.createdAt(),
                i.updatedAt(), e == null ? 0 : e.commentCount() ) );
        }
        return out;
    }

    /**
     * A comment on an item the caller may follow. A reporter's comment mentions nobody: it is stored as typed, with no
     * mention resolved and none recorded, so what they write cannot probe who is on the team.
     */
    public ReportedComment comment( final Actor actor, final String key, final String body ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( actor, key );
        final Scope s = is.scope();
        if ( !s.reporterOnly() ) {
            s.require( Action.COMMENT, is.item() );
        }
        s.requireWritable();
        WorkInputs.requireBody( body );
        requireLength( body );
        if ( s.reporterOnly() ) {
            return reported( views.reporterComment( store.addComment( is.item(), actor, body, List.of() ), is.item().key() ) );
        }
        final WorkPeople.Mentions m = people.mentions( actor, body );
        final long projectId = s.project().id();
        return reported( views.reporterComment( store.addComment( is.item(), actor, m.body(),
            WorkInputs.ofMembers( m.logins(), login -> scopes.isMember( projectId, login ) ) ), is.item().key() ) );
    }

    /**
     * Of the reporters named in a response (the distinct logins of the items shown), those who are outside the
     * project: people holding only the reporter role. For a caller who may see the project; bounded by the logins given.
     */
    public Set< String > outsideReporters( final Actor actor, final String projectKey,
                                           final Collection< String > reporterLogins ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return scopes.standing().outsideReporters( s.project().id(), reporterLogins, scopes::person );
    }

    /** A body or comment longer than {@link #MAX_TEXT} is refused on the field {@code body}. */
    private static void requireLength( final String text ) {
        if ( text != null && text.length() > MAX_TEXT ) {
            throw new WorkValidationException( "body", "The text may be at most " + MAX_TEXT + " characters." );
        }
    }

    /**
     * The reporter's view: every person by their public name, never a login; the timeline reshaped by
     * {@link ReporterEvents} (the same function the inbox and email use), and an {@code attached} event only while its
     * file is still attached (a file a member removed is not named).
     */
    private ReportedItemView reportedView( final Scope s, final Item item ) throws SQLException {
        final List< ReportedComment > comments = new ArrayList<>();
        for ( final Comment c : store.comments( item.id() ) ) {
            comments.add( reported( views.reporterComment( c, item.key() ) ) );
        }
        final Set< String > liveFiles = new java.util.HashSet<>();
        for ( final Attachment a : store.notifyStores().attachments().forItem( item.id() ) ) {
            liveFiles.add( "attachment:" + a.id() );
        }
        final List< ReportedEvent > events = new ArrayList<>();
        for ( final WorkEvent raw : store.itemEvents( item.id() ) ) {
            final WorkEvent e = ReporterEvents.reshape( raw ).orElse( null );
            if ( e == null || WorkEvent.ATTACHED.equals( e.type() ) && !liveFiles.contains( e.fromValue() ) ) {
                continue;
            }
            events.add( new ReportedEvent( e.id(), e.type(), e.at(), shown( e.actorKind(), e.actor() ), e.actorKind(),
                e.fromValue(), e.toValue() ) );
        }
        // Both the text and its HTML come from the reshaped Markdown, never from the stored body.
        final String shownBody = item.body() == null ? null : views.reporterBody( item ).markdown();
        return new ReportedItemView( item.key(), s.project().key(), s.project().name(), item.level(), item.type(),
            item.status(), item.title(), shownBody, views.htmlOf( shownBody, item.key() ),
            item.reporter() == null ? null : shown( item.reporterAgentId() == null ? Actor.Kind.HUMAN : Actor.Kind.AGENT,
                item.reporter() ),
            item.createdAt(), item.updatedAt(), comments, events, s.project().archivedAt() != null );
    }

    /** A comment as a reporter is shown it: the author by public name, no login, item id or agent id. */
    private ReportedComment reported( final CommentView v ) {
        final Actor.Kind kind = v.authorAgentId() != null ? Actor.Kind.AGENT : Actor.Kind.HUMAN;
        return new ReportedComment( v.id(), shown( kind, v.author() ), kind, v.body(),
            v.bodyHtml(), v.mentions(),
            v.createdAt(), v.editedAt(), v.removedAt() );
    }

    /** A stored actor's public name: a person's display name, an agent's name, a code host's label. */
    private String shown( final Actor.Kind kind, final String stored ) {
        if ( stored == null && kind == Actor.Kind.SYSTEM ) {
            return null;   // a lapsed claim has no actor (ReporterEvents)
        }
        return Actor.shown( kind == null ? Actor.Kind.HUMAN : kind, stored, scopes::displayName );
    }
}
