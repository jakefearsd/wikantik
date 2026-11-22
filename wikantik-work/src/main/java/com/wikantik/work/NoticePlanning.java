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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides, for one dispatch call, who is told about each event: the {@link Recipients} rules, then the standing
 * check. A login is kept only when the directory still has the account and the account stands in the event's project
 * (viewer or above, or a wiki admin), or, for a person who only reports, when the event is about the very item they
 * filed and {@link ReporterEvents} lets them hear of it (they are then told as its watcher). A real account always
 * wins: a name with no account, such as an agent's, is never kept, and an account that shares its name with an agent
 * is still a person. The same check guards the auto-watch rows. Person, project and item answers are cached for the
 * call; a reporter's answer is cached per item, never per project, so their standing on one item is never reused for
 * another. A directory failure ({@link WorkUnavailableException}) propagates and rolls the batch back.
 */
final class NoticePlanning implements WorkNotificationStore.NoticePlanner {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final GuardedListener listener;
    private final Map< String, Optional< WorkDirectory.Person > > persons = new HashMap<>();
    /** {@code projectId/login}: standing in the project (viewer or above, or admin). */
    private final Map< String, Boolean > standing = new HashMap<>();
    /** {@code projectId/login/itemId}: follows that one item as its reporter and nothing more. */
    private final Map< String, Boolean > reports = new HashMap<>();
    private final Map< Long, Optional< Item > > items = new HashMap<>();

    NoticePlanning( final WorkStore store, final WorkScopes scopes, final WorkPeople people,
                    final GuardedListener listener ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
        this.listener = listener;
    }

    @Override
    public void skipped( final WorkEvent e, final Throwable cause ) {
        listener.notifySkipped();
    }

    @Override
    public Map< String, NoticeReason > plan( final WorkEvent e, final EventFacts facts ) throws SQLException {
        final Map< String, NoticeReason > wanted = Recipients.of( e, withLeadGroups( facts ) );
        final Set< String > asReporters = Recipients.forReporters( e, facts );
        final Map< String, NoticeReason > kept = new TreeMap<>();
        for ( final Map.Entry< String, NoticeReason > row : wanted.entrySet() ) {
            final NoticeReason reason = keep( row.getKey(), row.getValue(), e, asReporters );
            if ( reason != null ) {
                kept.put( row.getKey(), reason );
                listener.notified( "inbox", "delivered" );
            } else {
                listener.notified( "inbox", "dropped" );
            }
        }
        for ( final String login : asReporters ) {
            if ( !wanted.containsKey( login ) && keep( login, null, e, asReporters ) != null ) {
                kept.put( login, NoticeReason.WATCHING );
                listener.notified( "inbox", "delivered" );
            }
        }
        return kept;
    }

    @Override
    public boolean mayWatch( final WorkEvent e, final String login ) throws SQLException {
        final Optional< Actor > person = person( login );
        if ( person.isEmpty() ) {
            return false;
        }
        return seesProject( e.projectId(), person.get() )
            || ReporterEvents.reshape( e ).isPresent() && followsAsReporter( person.get(), e );
    }

    /**
     * The reason the login is told, or null: a person with standing keeps the rule's reason (a login that is only a
     * reporter candidate gets nothing from that path); a person who only reports is told, as a watcher, only when the
     * reporter rule picked them and the item is theirs.
     */
    private NoticeReason keep( final String login, final NoticeReason ruleReason, final WorkEvent e,
                               final Set< String > asReporters ) throws SQLException {
        final Optional< Actor > person = person( login );
        if ( person.isEmpty() ) {
            return null;
        }
        if ( seesProject( e.projectId(), person.get() ) ) {
            return ruleReason;
        }
        return asReporters.contains( login ) && followsAsReporter( person.get(), e ) ? NoticeReason.WATCHING : null;
    }

    private EventFacts withLeadGroups( final EventFacts facts ) {
        if ( facts.leadGroups().isEmpty() ) {
            return facts;
        }
        final Set< String > members = new LinkedHashSet<>();
        for ( final String group : facts.leadGroups() ) {
            members.addAll( people.groupMembers( group ) );
        }
        return facts.withGroupLeads( members );
    }

    /**
     * The directory decides who a name is: an agent has no account, so it is never kept; a person who happens to share
     * a name with an agent still is one.
     */
    private Optional< Actor > person( final String login ) {
        return persons.computeIfAbsent( login, scopes::person )
            .map( p -> Actor.human( p.login(), p.groups(), p.admin() ) );
    }

    private boolean seesProject( final long projectId, final Actor person ) throws SQLException {
        final String key = projectId + "/" + person.login();
        final Boolean known = standing.get( key );
        if ( known != null ) {
            return known;
        }
        final boolean sees = scopes.standing().seesProject( projectId, person );
        standing.put( key, sees );
        return sees;
    }

    /** Asked only of a person without standing in the event's project; keyed by project, login and item. */
    private boolean followsAsReporter( final Actor person, final WorkEvent e ) throws SQLException {
        final Long itemId = e.itemId();
        if ( itemId == null ) {
            return false;
        }
        final String key = e.projectId() + "/" + person.login() + "/" + itemId;
        final Boolean known = reports.get( key );
        if ( known != null ) {
            return known;
        }
        final Optional< Item > item = item( itemId );
        final boolean follows = item.isPresent() && item.get().projectId() == e.projectId()
            && scopes.standing().followsAsReporter( person, item.get() );
        reports.put( key, follows );
        return follows;
    }

    private Optional< Item > item( final long itemId ) throws SQLException {
        if ( !items.containsKey( itemId ) ) {
            items.put( itemId, store.itemById( itemId ) );
        }
        return items.get( itemId );
    }
}
