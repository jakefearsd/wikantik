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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.CodeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.DateTimeException;

/**
 * Safe readers for a code host's JSON, shared by the adapters: a wrong type, a missing field, an over-long string or an
 * absurd time is a null, never an exception. Every string taken from a delivery is bounded here.
 */
final class HookJson {

    private static final Logger LOG = LogManager.getLogger( HookJson.class );

    static final int SHA = 64;
    static final int NAME = 256;
    static final int BRANCH = 255;
    static final int URL = 2048;
    static final int TITLE = 1024;
    static final int MESSAGE = 10_000;
    static final int MAX_COMMITS = 100;
    static final int MAX_EVENTS = 100;
    private static final int FIRST_YEAR = 1970;
    private static final int LAST_YEAR = 9999;

    private HookJson() {
    }

    /** The object under {@code key}, or null. */
    static JsonObject obj( final JsonObject o, final String key ) {
        final JsonElement v = o == null ? null : o.get( key );
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    /** The array under {@code key}, or null. */
    static JsonArray arr( final JsonObject o, final String key ) {
        final JsonElement v = o == null ? null : o.get( key );
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : null;
    }

    /** The non-blank string under {@code key}, cut to {@code max} characters; null for anything else (numbers too). */
    static String str( final JsonObject o, final String key, final int max ) {
        final JsonElement v = o == null ? null : o.get( key );
        if ( v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() ) {
            return null;
        }
        final String s = v.getAsString();
        if ( s.isBlank() ) {
            return null;
        }
        return s.length() > max ? s.substring( 0, max ) : s;
    }

    /** The boolean under {@code key}, or {@code fallback} when absent or not a JSON boolean. */
    static boolean bool( final JsonObject o, final String key, final boolean fallback ) {
        final JsonElement v = o == null ? null : o.get( key );
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : fallback;
    }

    /** True when {@code key} is the JSON boolean {@code true}. */
    static boolean isTrue( final JsonObject o, final String key ) {
        return bool( o, key, false );
    }

    /** A pull request number from a JSON integer or a string of digits (at most nine), as text; else null. */
    static String number( final JsonObject o, final String key ) {
        final JsonElement v = o == null ? null : o.get( key );
        if ( v == null || !v.isJsonPrimitive() ) {
            return null;
        }
        final JsonPrimitive p = v.getAsJsonPrimitive();
        final String text = p.isNumber() ? p.getAsBigDecimal().stripTrailingZeros().toPlainString() : p.getAsString();
        return text.matches( "[1-9][0-9]{0,8}" ) ? text : null;
    }

    /**
     * An ISO-8601 time with an offset (or Z) between 1970 and 9999; anything else is null. An event whose own time is
     * null is a rejected delivery ({@link HookIntake}); a commit's null time is "not given". A text that parses to an
     * instant beyond the year range of a date ({@code +1000000000-12-31T23:59:59Z}) is handled here too: its year
     * cannot even be computed ({@link DateTimeException}), so it is null like any other unusable time.
     */
    static Instant time( final JsonObject o, final String key ) {
        final String text = str( o, key, NAME );
        if ( text == null ) {
            return null;
        }
        try {
            final Instant at = text.endsWith( "Z" ) ? Instant.parse( text ) : OffsetDateTime.parse( text ).toInstant();
            final int year = at.atOffset( ZoneOffset.UTC ).getYear();
            return year < FIRST_YEAR || year > LAST_YEAR ? null : at;
        } catch ( final DateTimeException e ) {
            LOG.warn( "hook delivery field {} is not a usable time ({})", key, e.getClass().getSimpleName() );
            return null;
        }
    }

    /** A person from an object holding the three names, or null when it holds none of them. */
    static CodeEvent.Person person( final JsonObject o, final String usernameKey ) {
        if ( o == null ) {
            return null;
        }
        final String username = str( o, usernameKey, NAME );
        final String name = str( o, "name", NAME );
        final String email = str( o, "email", NAME );
        return username == null && name == null && email == null ? null : new CodeEvent.Person( username, name, email );
    }

    /** A person known by login only. */
    static CodeEvent.Person login( final String login ) {
        return login == null ? null : new CodeEvent.Person( login, null, null );
    }

    /** A kind name safe to log: one of the known ones, else "other". */
    static String loggable( final String kind, final java.util.Set< String > known ) {
        return kind != null && known.contains( kind ) ? kind : "other";
    }
}
