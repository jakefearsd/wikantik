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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What {@link Recipients} needs to know about an event beyond the event itself, read by the dispatcher. {@code reporter}
 * is null when an agent reported the item; {@code mentioned} is "mentioned by this event"; {@code leadGroups} are
 * groups holding the lead role, resolved to people by {@link #withGroupLeads}.
 */
public record EventFacts( String reporter, Set< String > watchers, Set< String > mentioned, Set< String > leads,
                          Set< String > leadGroups, boolean ackPending, boolean commentRemoved ) {

    /** Null sets become empty and every set is copied, so a fact cannot change after the dispatcher built it. */
    public EventFacts {
        watchers = copy( watchers );
        mentioned = copy( mentioned );
        leads = copy( leads );
        leadGroups = copy( leadGroups );
    }

    private static Set< String > copy( final Set< String > in ) {
        return in == null ? Set.of() : java.util.Collections.unmodifiableSet( new LinkedHashSet<>( in ) );
    }

    /** A copy whose {@code leads} also holds {@code members} and whose {@code leadGroups} is empty. */
    public EventFacts withGroupLeads( final Set< String > members ) {
        final Set< String > all = new LinkedHashSet<>( leads );
        all.addAll( members );
        return new EventFacts( reporter, watchers, mentioned, all, Set.of(), ackPending, commentRemoved );
    }
}
