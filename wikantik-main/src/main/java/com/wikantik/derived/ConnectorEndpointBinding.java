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
package com.wikantik.derived;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Binds a connector's stored credentials to the endpoint host they were issued against.
 *
 * <p>A stored secret (e.g. a Confluence {@code api_token}) is transmitted as HTTP Basic to
 * whatever host the connector's {@code base_url} names. Left unchecked, a scoped admin could
 * repoint {@code base_url} at a host they control and harvest the token, so moving a
 * credential-bearing connector to a different host is refused: the operator must delete the
 * stored credentials first and re-enter them against the new host.</p>
 *
 * <p>Connectors with no caller-controlled endpoint are unaffected — {@code github} always
 * targets {@code api.github.com} and {@code gdrive} always targets Google, so neither carries
 * an {@value #ENDPOINT_URL_KEY} key for this rule to compare.</p>
 *
 * <p>Extracted from {@code ConnectorConfigService} so the rule is independently testable and
 * the service stays inside the complexity ratchet.</p>
 */
final class ConnectorEndpointBinding {

    private static final Logger LOG = LogManager.getLogger( ConnectorEndpointBinding.class );

    /** Config key naming the endpoint a stored credential is transmitted to. */
    static final String ENDPOINT_URL_KEY = "base_url";

    static final String MESSAGE =
        "cannot change the endpoint host while credentials are stored for this connector; "
      + "delete the stored credentials first, then re-enter them for the new host";

    private ConnectorEndpointBinding() { }

    /**
     * Validation errors for an update that would move a credential-bearing connector to a
     * different endpoint host; empty when the change is allowed.
     *
     * @param storedConfigJson     the connector's currently-persisted config JSON
     * @param incoming             the proposed replacement config
     * @param hasStoredCredentials consulted ONLY when the host actually changes, so an ordinary
     *                             update does not pay for a credential lookup
     */
    static Map< String, String > hostChangeErrors( final String storedConfigJson,
            final JsonObject incoming, final BooleanSupplier hasStoredCredentials ) {
        final String oldHost = hostOf( parse( storedConfigJson ) );
        final String newHost = hostOf( incoming );
        if ( oldHost == null || newHost == null || oldHost.equals( newHost ) ) {
            return Map.of();
        }
        if ( !hasStoredCredentials.getAsBoolean() ) {
            return Map.of();
        }
        return Map.of( ENDPOINT_URL_KEY, MESSAGE );
    }

    private static JsonObject parse( final String json ) {
        try {
            return JsonParser.parseString( json ).getAsJsonObject();
        } catch ( final RuntimeException e ) {
            LOG.warn( "stored connector config JSON unparseable during endpoint-host check: {}",
                e.getMessage() );
            return null;
        }
    }

    /** Lowercased host of the config's endpoint URL, or null when absent/blank/unparseable. */
    private static String hostOf( final JsonObject config ) {
        if ( config == null ) {
            return null;
        }
        final JsonElement element = config.get( ENDPOINT_URL_KEY );
        if ( element == null || element.isJsonNull() ) {
            return null;
        }
        try {
            final String raw = element.getAsString().trim();
            if ( raw.isEmpty() ) {
                return null;
            }
            final String host = URI.create( raw ).getHost();
            return host == null ? null : host.toLowerCase( Locale.ROOT );
        } catch ( final RuntimeException e ) {
            LOG.warn( "connector {} unparseable during endpoint-host check: {}",
                ENDPOINT_URL_KEY, e.getMessage() );
            return null;
        }
    }
}
