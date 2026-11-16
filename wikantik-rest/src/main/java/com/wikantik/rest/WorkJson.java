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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The wire form of the work API: one Gson configuration, people shown by display name, and the error body.
 *
 * <p><b>People on the wire.</b> Every field that holds a user's login ({@code assignee}, {@code reporter},
 * {@code author}, {@code actor}, {@code editedBy}, a user member's {@code principal}, the people in assigned, handoff
 * and member events) comes with a {@code …Name} twin holding the display name. For a caller who is not a wiki admin
 * the field itself holds the display name too (the two are equal), so no login reaches them; a wiki admin sees the
 * login in the field. Where the UI needs to know "is this me", the server says so: {@code mine} on a comment,
 * {@code self} on a member row. Group names are not logins and are shown as they are.
 */
final class WorkJson {

    private WorkJson() {
    }

    private static final Set< String > NAME_KEYS = Set.of( "assignee", "reporter", "author", "actor", "editedBy" );

    /** Who the response is for: their login (for {@code mine}/{@code self}) and whether they may see logins. */
    record Viewer( String login, boolean admin ) {
    }

    /** Serialises enums by their {@code wire()} value (or lower-cased name), dates and instants as ISO strings. */
    private static final class WireEnumFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings( { "unchecked", "rawtypes" } )
        public < T > TypeAdapter< T > create( final Gson gson, final TypeToken< T > type ) {
            final Class< ? super T > raw = type.getRawType();
            if ( !Enum.class.isAssignableFrom( raw ) ) {
                return null;
            }
            final Class< ? > enumType = raw.isEnum() ? raw : raw.getSuperclass();
            // Looked up without an exception: an enum with no wire() is an expected case, not an error.
            final Method wireMethod = Arrays.stream( enumType.getMethods() )
                .filter( m -> "wire".equals( m.getName() ) && m.getParameterCount() == 0 )
                .findFirst().orElse( null );
            return new TypeAdapter< T >() {
                @Override
                public void write( final JsonWriter out, final T value ) throws IOException {
                    if ( value == null ) {
                        out.nullValue();
                        return;
                    }
                    try {
                        out.value( wireMethod != null ? String.valueOf( wireMethod.invoke( value ) )
                            : ( ( Enum ) value ).name().toLowerCase( Locale.ROOT ) );
                    } catch ( final ReflectiveOperationException e ) {
                        throw new IOException( "Cannot serialise " + enumType.getName(), e );
                    }
                }

                @Override
                public T read( final JsonReader in ) {
                    throw new UnsupportedOperationException( "Work enums are parsed by hand, not by Gson." );
                }
            };
        }
    }

    static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .registerTypeAdapterFactory( new WireEnumFactory() )
        .registerTypeAdapter( Instant.class, ( com.google.gson.JsonSerializer< Instant > )
            ( src, t, ctx ) -> new JsonPrimitive( src.toString() ) )
        .registerTypeAdapter( LocalDate.class, ( com.google.gson.JsonSerializer< LocalDate > )
            ( src, t, ctx ) -> new JsonPrimitive( src.toString() ) )
        .create();

    static JsonObject errorBody( final int status, final String message ) {
        final JsonObject body = new JsonObject();
        body.addProperty( "error", true );
        body.addProperty( "status", status );
        body.addProperty( "message", message );
        return body;
    }

    static void write( final HttpServletResponse response, final int status, final JsonElement tree )
            throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write( GSON.toJson( tree ) );
    }

    static JsonElement enrich( final JsonElement e, final Function< String, String > names, final Viewer viewer ) {
        if ( e.isJsonArray() ) {
            for ( final JsonElement child : e.getAsJsonArray() ) {
                enrich( child, names, viewer );
            }
        } else if ( e.isJsonObject() ) {
            final JsonObject o = e.getAsJsonObject();
            final Map< String, JsonElement > changes = new LinkedHashMap<>();
            for ( final Map.Entry< String, JsonElement > entry : o.entrySet() ) {
                final JsonElement v = entry.getValue();
                if ( NAME_KEYS.contains( entry.getKey() ) && v.isJsonPrimitive() ) {
                    person( changes, entry.getKey(), v.getAsString(), names, viewer );
                }
                enrich( v, names, viewer );
            }
            memberNames( o, changes, names, viewer );
            eventNames( o, changes, names, viewer );
            changes.forEach( o::add );
        }
        return e;
    }

    /** {@code key} holds a login: add {@code keyName}, and for a non-admin replace the login with the name. */
    private static void person( final Map< String, JsonElement > changes, final String key, final String login,
                                final Function< String, String > names, final Viewer viewer ) {
        final JsonPrimitive name = new JsonPrimitive( names.apply( login ) );
        changes.put( key + "Name", name );
        if ( "author".equals( key ) ) {
            changes.put( "mine", new JsonPrimitive( login.equals( viewer.login() ) ) );
        }
        if ( !viewer.admin() ) {
            changes.put( key, name );
        }
    }

    private static void memberNames( final JsonObject o, final Map< String, JsonElement > changes,
                                     final Function< String, String > names, final Viewer viewer ) {
        final String principal = str( o, "principal" );
        if ( principal == null || !o.has( "principalKind" ) ) {
            return;
        }
        final boolean user = "user".equals( str( o, "principalKind" ) );
        final String name = user ? names.apply( principal ) : principal;
        changes.put( "displayName", new JsonPrimitive( name ) );
        changes.put( "self", new JsonPrimitive( user && principal.equals( viewer.login() ) ) );
        if ( user && !viewer.admin() ) {
            changes.put( "principal", new JsonPrimitive( name ) );
        }
    }

    /** The people an assigned, handoff or member event names, by display name (and only so, for a non-admin). */
    private static void eventNames( final JsonObject o, final Map< String, JsonElement > changes,
                                    final Function< String, String > names, final Viewer viewer ) {
        final String type = str( o, "type" );
        if ( !"assigned".equals( type ) && !"handoff".equals( type ) && !"member".equals( type ) ) {
            return;
        }
        for ( final String side : new String[] { "fromValue", "toValue" } ) {
            final String value = str( o, side );
            if ( value == null || value.isEmpty() ) {
                continue;
            }
            final String[] parts = EventPeople.split( type, value );
            if ( parts == null ) {
                continue;
            }
            final boolean login = parts[ 2 ] == null || "user".equals( parts[ 2 ] );   // a group name is shown as is
            final String name = parts[ 1 ].isEmpty() ? "" : login ? names.apply( parts[ 1 ] ) : parts[ 1 ];
            if ( !name.isEmpty() ) {
                changes.put( side + "Name", new JsonPrimitive( name ) );
            }
            if ( !viewer.admin() ) {
                changes.put( side, new JsonPrimitive( EventPeople.join( type, value, name ) ) );
            }
        }
    }

    private static String str( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
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
