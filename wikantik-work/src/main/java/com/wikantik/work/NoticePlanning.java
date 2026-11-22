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
 * (viewer or above, or a wiki admin). A real account always wins: a name with no account, such as an agent's, is never kept, and an account that shares its name with an agent is still a person. The same check guards the auto-watch rows. Person and standing answers are
 * cached for the call; a directory failure ({@link WorkUnavailableException}) propagates and rolls the batch back.
 */
final class NoticePlanning implements WorkNotificationStore.NoticePlanner {

    private final WorkScopes scopes;
    private final WorkPeople people;
    private final GuardedListener listener;
    private final Map< String, Optional< WorkDirectory.Person > > persons = new HashMap<>();
    private final Map< String, Boolean > standing = new HashMap<>();

    NoticePlanning( final WorkScopes scopes, final WorkPeople people,
                    final GuardedListener listener ) {
        this.scopes = scopes;
        this.people = people;
        this.listener = listener;
    }

    @Override
    public Map< String, NoticeReason > plan( final WorkEvent e, final EventFacts facts ) throws SQLException {
        final Map< String, NoticeReason > wanted = Recipients.of( e, withLeadGroups( facts ) );
        final Map< String, NoticeReason > kept = new TreeMap<>();
        for ( final Map.Entry< String, NoticeReason > row : wanted.entrySet() ) {
            if ( stands( row.getKey(), e.projectId() ) ) {
                kept.put( row.getKey(), row.getValue() );
                listener.notified( "inbox", "delivered" );
            } else {
                listener.notified( "inbox", "dropped" );
            }
        }
        return kept;
    }

    @Override
    public boolean mayWatch( final WorkEvent e, final String login ) throws SQLException {
        return stands( login, e.projectId() );
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

    private boolean stands( final String login, final long projectId ) throws SQLException {
        final String key = projectId + "/" + login;
        final Boolean known = standing.get( key );
        if ( known != null ) {
            return known;
        }
        boolean sees = false;
        // The directory decides who a name is: an agent has no account, so it is never kept; a person who happens to
        // share a name with an agent still is one.
        final Optional< WorkDirectory.Person > person = persons.computeIfAbsent( login, scopes::person );
        if ( person.isPresent() ) {
            final WorkDirectory.Person p = person.get();
            sees = scopes.standing().seesProject( projectId, Actor.human( p.login(), p.groups(), p.admin() ) );
        }
        standing.put( key, sees );
        return sees;
    }
}
