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
 * palette by join order within the session, never derived from the login, section 8.3) and its last selection.
 */
public record PresenceEntry( String displayName, String color, int anchor, int head, int version, int unconfirmed ) {
    public PresenceEntry {
        Objects.requireNonNull( displayName, "displayName" );
        Objects.requireNonNull( color, "color" );
    }

    public static PresenceEntry of( final String displayName, final String color, final Selection s ) {
        return new PresenceEntry( displayName, color, s.anchor(), s.head(), s.version(), s.unconfirmed() );
    }

    public Selection selection() {
        return new Selection( anchor, head, version, unconfirmed );
    }
}
