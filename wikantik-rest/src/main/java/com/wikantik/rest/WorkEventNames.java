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
package com.wikantik.rest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.function.Function;

/**
 * The names an event's values stand for, added as {@code fromValueName} / {@code toValueName} twins by
 * {@link WorkJson#enrich}: the people in assigned, hand-off and member events, the queue in {@code queue:<key>}, and
 * the agent in a {@code released} event's {@code agent:<id>} or an agent member's {@code agent:<id>:<role>}.
 *
 * <p>A person's value is also rewritten to the display name for a non-admin; a queue or agent value keeps its raw
 * form (neither is a login), and only gains the twin.
 */
final class WorkEventNames {

    private static final String QUEUE_PREFIX = "queue:";
    private static final String AGENT_PREFIX = "agent:";

    private WorkEventNames() {
    }

    /** Adds the twins (and, for a non-admin, the person rewrites) of the event in {@code o}, if it is one. */
    static void add( final JsonObject o, final Map< String, JsonElement > changes,
                     final Function< String, String > names, final WorkViewer viewer,
                     final WorkLookup lookup ) {
        final String type = WorkJson.str( o, "type" );
        final boolean person = "assigned".equals( type ) || "handoff".equals( type ) || "member".equals( type );
        if ( !person && !"released".equals( type ) ) {
            return;
        }
        for ( final String side : new String[] { "fromValue", "toValue" } ) {
            final String value = WorkJson.str( o, side );
            if ( value == null || value.isEmpty() ) {
                continue;
            }
            if ( person ) {
                personOrTarget( changes, side, type, value, names, viewer, lookup );
            } else if ( value.startsWith( AGENT_PREFIX ) ) {
                changes.put( side + "Name", new JsonPrimitive( agentLabel( lookup, value.substring( AGENT_PREFIX.length() ) ) ) );
            }
        }
    }

    private static void personOrTarget( final Map< String, JsonElement > changes, final String side, final String type,
                                        final String value, final Function< String, String > names,
                                        final WorkViewer viewer, final WorkLookup lookup ) {
        final String[] parts = EventPeople.split( type, value );
        if ( parts == null || parts[ 1 ].isEmpty() ) {
            return;
        }
        if ( "agent".equals( parts[ 2 ] ) ) {
            changes.put( side + "Name", new JsonPrimitive( agentLabel( lookup, parts[ 1 ] ) ) );
        } else if ( !"member".equals( type ) && parts[ 1 ].startsWith( QUEUE_PREFIX ) ) {
            changes.put( side + "Name", new JsonPrimitive( queueLabel( lookup, parts[ 1 ].substring( QUEUE_PREFIX.length() ) ) ) );
        } else {
            final boolean login = parts[ 2 ] == null || "user".equals( parts[ 2 ] );   // a group name is shown as is
            final String name = login ? names.apply( parts[ 1 ] ) : parts[ 1 ];
            changes.put( side + "Name", new JsonPrimitive( name ) );
            if ( !viewer.admin() ) {
                changes.put( side, new JsonPrimitive( EventPeople.join( type, value, name ) ) );
            }
        }
    }

    /** The agent's current name for the id text, or {@link WorkJson#UNKNOWN_AGENT}. */
    static String agentLabel( final WorkLookup lookup, final String idText ) {
        final Long id = parseId( idText );
        final String name = id == null ? null : lookup.agent( id );
        return name == null ? WorkJson.UNKNOWN_AGENT : name;
    }

    static Long parseId( final String idText ) {
        try {
            return Long.parseLong( idText.trim() );
        } catch ( final NumberFormatException e ) {
            return null;   // not an agent id: shown as unknown rather than guessed at
        }
    }

    /** The queue's name for the key, or the key itself when the queue cannot be resolved. */
    static String queueLabel( final WorkLookup lookup, final String key ) {
        final String name = lookup.queue( key );
        return name == null ? key : name;
    }

    /** Where the person sits in an event value: {@code login}, {@code status|login} or {@code kind:principal:role}. */
    static final class EventPeople {

        private EventPeople() {
        }

        /** {prefix, person, kind-or-null}; null when the value has no person in it. */
        static String[] split( final String type, final String value ) {
            switch ( type ) {
                case "handoff" -> {
                    final int bar = value.indexOf( '|' );
                    return bar < 0 ? null : new String[] { value.substring( 0, bar + 1 ), value.substring( bar + 1 ), null };
                }
                case "member" -> {
                    final int first = value.indexOf( ':' );
                    final int last = value.lastIndexOf( ':' );
                    return first < 0 || last <= first ? null
                        : new String[] { value.substring( 0, first ), value.substring( first + 1, last ),
                            value.substring( 0, first ) };
                }
                default -> {
                    return new String[] { "", value, null };
                }
            }
        }

        /** The value with its person replaced by {@code name}. */
        static String join( final String type, final String value, final String name ) {
            return switch ( type ) {
                case "handoff" -> value.substring( 0, value.indexOf( '|' ) + 1 ) + name;
                case "member" -> value.substring( 0, value.indexOf( ':' ) + 1 ) + name
                    + value.substring( value.lastIndexOf( ':' ) );
                default -> name;
            };
        }
    }
}
