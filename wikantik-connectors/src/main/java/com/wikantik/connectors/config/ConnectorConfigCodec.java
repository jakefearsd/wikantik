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
package com.wikantik.connectors.config;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation + JSON-to-typed-config translation for the admin-UI-creatable connector types.
 * Bridges the admin UI's per-connector JSON blob (persisted as {@code connector_configs.config})
 * and the six typed config records the connectors themselves consume.
 *
 * <p>{@code filesystem} is deliberately excluded from {@link #UI_TYPES} and therefore always
 * fails {@link #validate} with an "unknown type" error: filesystem connectors read arbitrary
 * server paths, so they stay operator-only (properties-only), never creatable from the admin UI
 * (design decision D9, {@code docs/superpowers/specs/2026-07-15-connector-admin-ui-design.md}).
 *
 * <p>This class is a thin dispatcher over an immutable registry of {@link ConnectorTypeCodec}s
 * (see {@link #codecFor}): each connector type's validation, JSON-to-typed-config
 * building, and typed-config-to-JSON logic lives in a package-private per-type codec
 * ({@link WebCrawlerCodec}, {@link SitemapCodec}, {@link FeedCodec}, {@link DriveCodec},
 * {@link GithubCodec}, {@link ConfluenceCodec}), with JSON-field-reading helpers shared via
 * {@link JsonFieldSupport}.
 */
public final class ConnectorConfigCodec {

    private static final Map< String, ConnectorTypeCodec< ? > > CODECS = registry(
        WebCrawlerCodec.INSTANCE, SitemapCodec.INSTANCE, FeedCodec.INSTANCE,
        DriveCodec.INSTANCE, GithubCodec.INSTANCE, ConfluenceCodec.INSTANCE );

    /** Connector types the admin UI may create: the registered codecs' types, in registration order.
     *  {@code filesystem} is not one of them (D9). */
    public static final Set< String > UI_TYPES = CODECS.keySet();

    private static final Pattern ID_PATTERN = Pattern.compile( "[a-z0-9-]{1,64}" );

    private ConnectorConfigCodec() {}

    /** Field-keyed validation errors; empty ⇒ {@link #ok()}. The map is defensively immutable. */
    public record Validation( Map< String, String > errors ) {
        public Validation {
            errors = Map.copyOf( errors );
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Validates a connector id: {@code [a-z0-9-]{1,64}}, else an error under key {@code "connector_id"}. */
    public static Validation validateId( final String id ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        if ( id == null || !ID_PATTERN.matcher( id ).matches() ) {
            errors.put( "connector_id", "must match [a-z0-9-]{1,64}" );
        }
        return new Validation( errors );
    }

    /** The codec registered for {@code type}; empty for a null or unrecognized type, including {@code filesystem} (D9). */
    public static Optional< ConnectorTypeCodec< ? > > codecFor( final String type ) {
        return Optional.ofNullable( type ).map( CODECS::get );
    }

    /** Field-keyed validation of {@code config} against {@code type}'s rules. An unrecognized
     *  {@code type} (including {@code filesystem}, D9) yields a single {@code "connector_type"} error. */
    public static Validation validate( final String type, final JsonObject config ) {
        return codecFor( type )
            .map( codec -> codec.validate( config ) )
            .orElseGet( () -> new Validation( Map.of( "connector_type", "unknown type: " + type ) ) );
    }

    /** Builds the typed config record for {@code type} with defaults applied. Callers must call
     *  {@link #validate} first: throws {@link IllegalArgumentException} if validation would fail. */
    public static Object toConfig( final String type, final JsonObject config ) {
        return build( type, config, false );
    }

    /** Same as {@link #toConfig}, but with dry-run-safe caps clamped: {@code max_pages}/{@code max_files}/
     *  {@code max_items} → {@code min(value, 3)}, {@code max_depth} → {@code 1}, {@code delay_ms} → {@code 0}. */
    public static Object toConfigForTest( final String type, final JsonObject config ) {
        return build( type, config, true );
    }

    private static Object build( final String type, final JsonObject config, final boolean forTest ) {
        final Validation v = validate( type, config );
        if ( !v.ok() ) {
            throw new IllegalArgumentException( "invalid " + type + " config: " + v.errors() );
        }
        return codecFor( type ).orElseThrow().build( config, forTest );
    }

    // ---- typed config → JSON (the admin-UI "import" route reconstructs the config JSON
    // for a properties-defined connector from its typed record, one round trip through toConfig) ---

    /** Serializes a typed config record for {@code type} back to the codec's JSON field names —
     *  the inverse of {@link #toConfig}. {@code gdrive}'s {@code client_secret} is intentionally
     *  never emitted (it is not part of the admin-UI config JSON; it lives in the
     *  {@code CredentialStore}, mirroring {@code DriveCodec}'s validate/build). Throws
     *  {@link IllegalArgumentException} for an unrecognized {@code type} or a {@code config} that
     *  is not the record {@code type} builds (a caller bug, not a data-shape issue). */
    public static JsonObject toJson( final String type, final Object config ) {
        final ConnectorTypeCodec< ? > codec = codecFor( type )
            .orElseThrow( () -> new IllegalArgumentException( "unknown type: " + type ) );
        return toJson( codec, config );
    }

    private static < C > JsonObject toJson( final ConnectorTypeCodec< C > codec, final Object config ) {
        if ( !codec.configClass().isInstance( config ) ) {
            throw new IllegalArgumentException(
                "config for " + codec.type() + " must be " + codec.configClass().getSimpleName() );
        }
        return codec.toJson( codec.configClass().cast( config ) );
    }

    private static Map< String, ConnectorTypeCodec< ? > > registry( final ConnectorTypeCodec< ? >... codecs ) {
        final Map< String, ConnectorTypeCodec< ? > > byType = new LinkedHashMap<>();
        for ( final ConnectorTypeCodec< ? > codec : codecs ) {
            byType.put( codec.type(), codec );
        }
        return Collections.unmodifiableMap( byType );
    }
}
