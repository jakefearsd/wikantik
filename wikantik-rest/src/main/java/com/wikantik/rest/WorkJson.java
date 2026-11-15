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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** The wire form of the work API: one Gson configuration, display names beside logins, and the error body. */
final class WorkJson {

    private static final Logger LOG = LogManager.getLogger( WorkJson.class );

    private WorkJson() {
    }

    private static final Set< String > NAME_KEYS = Set.of( "assignee", "reporter", "author", "actor", "editedBy" );

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
            Method wire = null;
            try {
                wire = enumType.getMethod( "wire" );
            } catch ( final NoSuchMethodException e ) {
                LOG.debug( "{} has no wire(); using its lower-cased name", enumType.getName() );
            }
            final Method wireMethod = wire;
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

    static JsonElement enrich( final JsonElement e, final Function< String, String > names ) {
        if ( e.isJsonArray() ) {
            for ( final JsonElement child : e.getAsJsonArray() ) {
                enrich( child, names );
            }
        } else if ( e.isJsonObject() ) {
            final JsonObject o = e.getAsJsonObject();
            final Map< String, JsonElement > additions = new LinkedHashMap<>();
            for ( final Map.Entry< String, JsonElement > entry : o.entrySet() ) {
                final JsonElement v = entry.getValue();
                if ( NAME_KEYS.contains( entry.getKey() ) && v.isJsonPrimitive() ) {
                    additions.put( entry.getKey() + "Name", new JsonPrimitive( names.apply( v.getAsString() ) ) );
                }
                enrich( v, names );
            }
            addMemberAndEventNames( o, additions, names );
            additions.forEach( o::add );
        }
        return e;
    }

    private static void addMemberAndEventNames( final JsonObject o, final Map< String, JsonElement > add,
                                                final Function< String, String > names ) {
        final String principal = str( o, "principal" );
        if ( principal != null && o.has( "principalKind" ) ) {
            add.put( "displayName", new JsonPrimitive( "user".equals( str( o, "principalKind" ) )
                ? names.apply( principal ) : principal ) );
        }
        final String type = str( o, "type" );
        if ( "assigned".equals( type ) || "handoff".equals( type ) ) {
            for ( final String side : new String[] { "fromValue", "toValue" } ) {
                final String login = eventLogin( str( o, side ), type );
                if ( login != null && !login.isEmpty() ) {
                    add.put( side + "Name", new JsonPrimitive( names.apply( login ) ) );
                }
            }
        }
    }

    /** The login an event value names: the value itself, or for a handoff the part after the {@code |}. */
    private static String eventLogin( final String value, final String type ) {
        if ( value == null || !"handoff".equals( type ) ) {
            return value;
        }
        final int bar = value.indexOf( '|' );
        return bar < 0 ? null : value.substring( bar + 1 );
    }

    private static String str( final JsonObject o, final String key ) {
        final JsonElement v = o.get( key );
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }
}
