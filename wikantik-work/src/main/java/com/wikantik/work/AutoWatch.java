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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Who starts watching an item because of an event: a pure function. An agent never watches for itself. */
final class AutoWatch {

    /** One watch change: {@code subscribe} overrides a mute, otherwise a mute stays. */
    record Change( String login, boolean subscribe ) {
    }

    private AutoWatch() {
    }

    static List< Change > of( final WorkEvent e, final String reporter, final Set< String > mentioned,
                              final boolean commentRemoved ) {
        final List< Change > out = new ArrayList<>();
        final boolean person = e.actorKind() == Actor.Kind.HUMAN;
        switch ( e.type() ) {
            case WorkEvent.CREATED -> {
                if ( person && reporter != null ) {
                    out.add( new Change( reporter, false ) );
                }
            }
            case WorkEvent.ASSIGNED -> subscribePerson( out, e.toValue() );
            case WorkEvent.HANDOFF -> {
                final String to = e.toValue();
                final int bar = to == null ? -1 : to.indexOf( '|' );
                subscribePerson( out, bar < 0 ? null : to.substring( bar + 1 ) );
            }
            case WorkEvent.COMMENTED -> {
                if ( person ) {
                    out.add( new Change( e.actor(), false ) );
                }
                subscribeMentioned( out, mentioned, commentRemoved );
            }
            case WorkEvent.COMMENT_EDITED -> subscribeMentioned( out, mentioned, commentRemoved );
            default -> { }
        }
        return out;
    }

    private static void subscribePerson( final List< Change > out, final String target ) {
        final String login = Recipients.personIn( target );
        if ( login != null ) {
            out.add( new Change( login, true ) );
        }
    }

    private static void subscribeMentioned( final List< Change > out, final Set< String > mentioned,
                                            final boolean commentRemoved ) {
        if ( !commentRemoved ) {
            for ( final String m : mentioned ) {
                out.add( new Change( m, true ) );
            }
        }
    }
}
