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
import com.wikantik.work.WorkValidationException;
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
 * The wire form of the work API: one Gson configuration, people shown by display name, agents and queues by their
 * names, and the error body.
 *
 * <p><b>People on the wire.</b> Every field that holds a user's login ({@code assignee}, {@code reporter},
 * {@code author}, {@code actor}, {@code editedBy}, a user member's {@code principal}, the people in assigned, handoff
 * and member events) comes with a {@code …Name} twin holding the display name. For a caller who is not a wiki admin
 * the field itself holds the display name too (the two are equal), so no login reaches them; a wiki admin sees the
 * login in the field. Where the UI needs to know "is this me", the server says so: {@code mine} on a comment,
 * {@code self} on a member row. Group names are not logins and are shown as they are.
 *
 * <p><b>Agents and queues.</b> An agent is shown by its name, a queue by its name: neither is confidential. An agent
 * is identified by its id, never by its name: a row written by agent 5 carries {@code …Name} resolved from id 5 now
 * (so a renamed agent shows its current name), falling back to the text stored at write time when the agent cannot be
 * resolved. Agent names never go through the people directory. Event values {@code queue:<key>} and
 * {@code agent:<id>} keep their raw value and gain a {@code …Name} twin. Only {@code mine} and {@code self} say
 * who the viewer is, and for an agent viewer they compare ids.
 */
final class WorkJson {

    private WorkJson() {
    }

    private static final Set< String > NAME_KEYS = Set.of( "assignee", "reporter", "author", "actor", "editedBy" );

    static final String UNKNOWN_AGENT = "Unknown agent";

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

    /** A 400 naming the field: {@link #errorBody} plus {@code field}. */
    static void writeValidation( final HttpServletResponse response, final WorkValidationException e )
            throws IOException {
        final JsonObject body = errorBody( HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        body.addProperty( "field", e.field() );
        write( response, HttpServletResponse.SC_BAD_REQUEST, body );
    }

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

    static JsonElement enrich( final JsonElement e, final Function< String, String > names, final WorkViewer viewer ) {
        return enrich( e, names, viewer, WorkLookup.NONE );
    }

    static JsonElement enrich( final JsonElement e, final Function< String, String > names, final WorkViewer viewer,
                               final WorkLookup lookup ) {
        if ( e.isJsonArray() ) {
            for ( final JsonElement child : e.getAsJsonArray() ) {
                enrich( child, names, viewer, lookup );
            }
        } else if ( e.isJsonObject() ) {
            final JsonObject o = e.getAsJsonObject();
            final Map< String, JsonElement > changes = new LinkedHashMap<>();
            for ( final Map.Entry< String, JsonElement > entry : o.entrySet() ) {
                final JsonElement v = entry.getValue();
                if ( NAME_KEYS.contains( entry.getKey() ) && v.isJsonPrimitive() ) {
                    final Long agentId = WorkNames.agentIdBehind( o, entry.getKey() );
                    if ( agentId == null ) {
                        WorkNames.person( changes, entry.getKey(), v.getAsString(), names, viewer );
                    } else {
                        WorkNames.agent( changes, entry.getKey(), v.getAsString(), agentId, lookup, viewer );
                    }
                }
                enrich( v, names, viewer, lookup );
            }
            WorkNames.memberNames( o, changes, names, viewer, lookup );
            WorkEventNames.add( o, changes, names, viewer, lookup );
            WorkNames.claimAndQueueNames( o, changes, lookup );
            changes.forEach( o::add );
        }
        return e;
    }

    static String str( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    static Long number( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsLong() : null;
    }
}
