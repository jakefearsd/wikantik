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
package com.wikantik.collab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one JSON normalisation of page meta (A14). Page meta arrives from SnakeYAML (Integer, Float, Date), from the wire
 * and from the store's JSONB (Long or Double): two values are compared only after both went through {@link #normalise},
 * so a YAML {@code 3} and a JSON {@code 3} are equal. {@link #GSON} is also the store adapter's codec.
 *
 * <p>Dates and times are written in UTC, never in the JVM's zone or locale (Gson's default {@code Date} form is a
 * locale string in the default zone, which turned {@code date: 2026-01-15} into "Jan 14, 2026, 4:00:00 PM" in
 * California): a {@code LocalDate}, or an instant at UTC midnight (SnakeYAML parses {@code 2026-01-15} to one), becomes
 * {@code yyyy-MM-dd}; any other instant an ISO-8601 instant ({@code 2026-01-15T10:30:00Z}). Covers {@code java.util.Date}
 * and its subclasses, {@code Instant}, {@code OffsetDateTime} and {@code ZonedDateTime}.</p>
 */
public final class MetaJson {

    /** Nulls kept (an empty YAML field), numbers read as Long when integral, else Double. */
    public static final Gson GSON = new GsonBuilder().serializeNulls()
        .setObjectToNumberStrategy( ToNumberPolicy.LONG_OR_DOUBLE )
        .registerTypeHierarchyAdapter( Date.class, (JsonSerializer< Date >) ( d, t, c ) -> utc( d.toInstant() ) )
        .registerTypeAdapter( Instant.class, (JsonSerializer< Instant >) ( i, t, c ) -> utc( i ) )
        .registerTypeAdapter( OffsetDateTime.class, (JsonSerializer< OffsetDateTime >) ( o, t, c ) -> utc( o.toInstant() ) )
        .registerTypeAdapter( ZonedDateTime.class, (JsonSerializer< ZonedDateTime >) ( z, t, c ) -> utc( z.toInstant() ) )
        .registerTypeAdapter( LocalDate.class, (JsonSerializer< LocalDate >) ( l, t, c ) -> new JsonPrimitive( l.toString() ) )
        .create();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

    private MetaJson() {
    }

    /** {@code yyyy-MM-dd} for a UTC midnight, else the ISO-8601 instant. */
    static JsonPrimitive utc( final Instant i ) {
        final java.time.LocalDateTime at = java.time.LocalDateTime.ofInstant( i, ZoneOffset.UTC );
        return new JsonPrimitive( at.toLocalTime().equals( java.time.LocalTime.MIDNIGHT ) ? at.toLocalDate().toString()
            : i.toString() );
    }

    /** {@code meta} after a JSON round trip, in its original key order; never null. */
    public static Map< String, Object > normalise( final Map< String, Object > meta ) {
        final Map< String, Object > out = GSON.fromJson( GSON.toJson( meta ), MAP_TYPE );
        return out == null ? new LinkedHashMap<>() : new LinkedHashMap<>( out );
    }

    /** One meta value after the same JSON round trip (null stays null). */
    public static Object normaliseValue( final Object value ) {
        final Map< String, Object > one = new LinkedHashMap<>();
        one.put( "v", value );
        return normalise( one ).get( "v" );
    }
}
