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
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.wikantik.work.CodeEvent;
import com.wikantik.work.HookDelivery;
import com.wikantik.work.WorkCodeHostService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * What {@link WorkHookResource} does with a delivery whose signature verified: parse, translate, apply and write the one
 * log line. It never logs the body, the signature or the secret, and strips anything that could forge a log line.
 */
final class HookIntake {

    private static final Logger LOG = LogManager.getLogger( WorkHookResource.class );
    private static final int NESTING_LIMIT = 64;
    static final int ID_LIMIT = 64;
    private static final String GITHUB = "github";

    private HookIntake() {
    }

    /** Parse, translate and apply a verified delivery; always applies (an empty list moves "last delivery"). */
    static void accept( final WorkCodeHostService host, final HookDelivery delivery, final String format, final byte[] body,
                         final HttpServletRequest request ) throws SQLException {
        final String eventHeader = GITHUB.equals( format ) ? request.getHeader( "X-GitHub-Event" ) : null;
        final JsonElement tree = parse( body );
        final boolean usable = usable( format, tree );
        if ( !usable ) {
            // malformed, empty, a bare word or number: a rejected delivery, never a quiet success (ruling 11); its one
            // delivery line below is a WARN
            host.rejected();
        }
        final List< CodeEvent > translated = usable ? translate( format, eventHeader, tree ) : List.of();
        final boolean accepted = usable && allTimed( host, delivery, format, translated );
        final List< CodeEvent > events = accepted ? translated : List.of();
        final WorkCodeHostService.Applied applied = host.apply( delivery, events );
        final String event = eventName( format, eventHeader, translated );
        LOG.log( accepted ? Level.INFO : Level.WARN,
            "work codehost delivery product={} format={} event={} delivery={} outcome={} applied={} duplicate={} ignored={}",
            delivery.productKey(), format, event, safe( request.getHeader( "X-GitHub-Delivery" ) ),
            outcome( accepted, events ), applied.applied(), applied.duplicate(), applied.ignored() );
    }

    /**
     * Whether every event carries the host's own time. One that does not makes the delivery rejected, never applied
     * in part (counted, and a WARN naming the event kind): stamping it with the time it arrived would make a replay a
     * new, later event (rule F).
     */
    private static boolean allTimed( final WorkCodeHostService host, final HookDelivery delivery, final String format,
                                     final List< CodeEvent > events ) {
        final CodeEvent untimed = events.stream().filter( e -> e.at() == null ).findFirst().orElse( null );
        if ( untimed == null ) {
            return true;
        }
        LOG.warn( "work codehost delivery product={} format={} rejected: a {} event has no usable time",
            delivery.productKey(), format, untimed.kind().name().toLowerCase( Locale.ROOT ) );
        host.rejected();
        return false;
    }

    /** A JSON object (GitHub), or an object or array (generic): anything else is a rejected delivery. */
    private static boolean usable( final String format, final JsonElement tree ) {
        return tree != null && ( tree.isJsonObject() || !GITHUB.equals( format ) && tree.isJsonArray() );
    }

    private static String eventName( final String format, final String eventHeader, final List< CodeEvent > events ) {
        if ( GITHUB.equals( format ) ) {
            return safe( eventHeader );
        }
        return events.isEmpty() ? "none" : events.get( 0 ).kind().name().toLowerCase( Locale.ROOT );
    }

    private static List< CodeEvent > translate( final String format, final String eventHeader, final JsonElement tree ) {
        return GITHUB.equals( format ) ? GithubHookAdapter.translate( eventHeader, tree.getAsJsonObject() )
            : GenericHookAdapter.translate( tree );
    }

    private static String outcome( final boolean accepted, final List< CodeEvent > events ) {
        if ( !accepted ) {
            return "rejected";
        }
        return events.isEmpty() ? "ignored" : "accepted";
    }

    /** The body as JSON, or null when it is not (too deep, malformed, empty): logged without the body. */
    private static JsonElement parse( final byte[] body ) {
        try ( JsonReader reader = new JsonReader( new StringReader( new String( body, StandardCharsets.UTF_8 ) ) ) ) {
            reader.setNestingLimit( NESTING_LIMIT );
            final JsonElement tree = JsonParser.parseReader( reader );
            return tree == null || tree.isJsonNull() ? null : tree;
        } catch ( final JsonParseException | IOException e ) {
            LOG.info( "work codehost delivery body is not usable JSON cause={}", e.getClass().getSimpleName() );
            return null;
        }
    }

    /** At most 64 characters of letters, digits and {@code _ . : -}; anything else is dropped (no control characters). */
    static String safe( final String raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return "-";
        }
        final StringBuilder out = new StringBuilder();
        for ( int i = 0; i < raw.length() && out.length() < ID_LIMIT; i++ ) {
            final char c = raw.charAt( i );
            if ( c < 128 && ( Character.isLetterOrDigit( c ) || c == '_' || c == '.' || c == ':' || c == '-' ) ) {
                out.append( c );
            }
        }
        return out.isEmpty() ? "-" : out.toString();
    }
}
