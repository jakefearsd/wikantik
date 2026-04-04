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
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.wikantik.api.knowledge.Provenance;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared utilities for knowledge MCP tool implementations.
 */
final class KnowledgeMcpUtils {

    /** Gson instance configured to serialize {@link Instant} as ISO-8601 strings. */
    static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter( Instant.class, new InstantAdapter() )
            .create();

    private KnowledgeMcpUtils() {
    }

    /** Gson type adapter that writes/reads {@link Instant} as ISO-8601 strings. */
    private static final class InstantAdapter extends TypeAdapter< Instant > {
        @Override
        public void write( final JsonWriter out, final Instant value ) throws IOException {
            if ( value == null ) {
                out.nullValue();
            } else {
                out.value( value.toString() );
            }
        }

        @Override
        public Instant read( final JsonReader in ) throws IOException {
            if ( in.peek() == JsonToken.NULL ) {
                in.nextNull();
                return null;
            }
            return Instant.parse( in.nextString() );
        }
    }

    /**
     * Parses a {@code provenance_filter} argument (a list of strings like
     * {@code ["human-authored", "ai-reviewed"]}) into a {@code Set<Provenance>}.
     * Returns {@code null} if the argument is absent or empty, which signals
     * the service to use its default filter.
     */
    @SuppressWarnings( "unchecked" )
    static Set< Provenance > parseProvenanceFilter( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "provenance_filter" );
        if ( raw == null ) {
            return null;
        }
        final List< String > values;
        if ( raw instanceof List ) {
            values = ( List< String > ) raw;
        } else {
            return null;
        }
        if ( values.isEmpty() ) {
            return null;
        }
        final Set< Provenance > result = new HashSet<>();
        for ( final String v : values ) {
            result.add( Provenance.fromValue( v ) );
        }
        return result;
    }
}
