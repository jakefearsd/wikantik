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
package com.wikantik.collab.model;

import java.util.Objects;

/**
 * One other participant in a pull's presence map (A12, ruling P7): its public display name, its colour (from a fixed
 * palette by join order within the session, never derived from the login, section 8.3) and its last selection. Every
 * other live participant is in the map; one that never sent a selection has all four selection fields null.
 */
public record PresenceEntry( String displayName, String color, Integer anchor, Integer head, Integer version,
                             Integer unconfirmed ) {
    public PresenceEntry {
        Objects.requireNonNull( displayName, "displayName" );
        Objects.requireNonNull( color, "color" );
        if( ( anchor == null ) != ( head == null ) || ( anchor == null ) != ( version == null )
                || ( anchor == null ) != ( unconfirmed == null ) ) {
            throw new IllegalArgumentException( "the selection fields are all null or all set" );
        }
    }

    /** {@code s} may be null: no selection yet. */
    public static PresenceEntry of( final String displayName, final String color, final Selection s ) {
        return s == null ? new PresenceEntry( displayName, color, null, null, null, null )
            : new PresenceEntry( displayName, color, s.anchor(), s.head(), s.version(), s.unconfirmed() );
    }

    /** The selection, or null when the participant has not sent one. */
    public Selection selection() {
        return anchor == null ? null : new Selection( anchor, head, version, unconfirmed );
    }
}
