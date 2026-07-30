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
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the SCIM discovery payloads served by {@link ScimDiscoveryResource}.
 *
 * <p>The load-bearing case here is {@code documentationUri}. RFC 7643 §5 makes the
 * field OPTIONAL, and an empty string is not a valid URI reference. Emitting
 * {@code "documentationUri": ""} is accepted by lenient clients but *rejected* by
 * strict ones: Authentik parses ServiceProviderConfig through a Pydantic model whose
 * {@code documentationUri} is a URL, so an empty value fails validation with
 * "failed to get ServiceProviderConfig", after which it never provisions a single
 * user. That defect is invisible to our own wire tests (which do not URL-validate)
 * and was only caught by the Authentik full-loop IT.</p>
 */
class ScimDiscoveryResourceTest {

    private static JsonObject get( final String pathInfo ) throws Exception {
        final ScimDiscoveryResource servlet = new ScimDiscoveryResource();
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        // The servlet dispatches on getServletPath() (see ScimDiscoveryResource.doGet).
        when( req.getServletPath() ).thenReturn( "/scim/v2" + ( pathInfo == null ? "" : pathInfo ) );

        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );

        servlet.doGet( req, resp );
        final String raw = body.toString();
        assertFalse( raw.isBlank(), "discovery payload for " + pathInfo + " must not be empty" );
        return JsonParser.parseString( raw ).getAsJsonObject();
    }

    // ------------------------------------------------------------------ documentationUri

    @Test
    void serviceProviderConfigDoesNotEmitAnEmptyDocumentationUri() throws Exception {
        final JsonObject cfg = get( "/ServiceProviderConfig" );

        if ( !cfg.has( "documentationUri" ) ) {
            return; // omitting an optional field is the correct choice.
        }
        final String uri = cfg.get( "documentationUri" ).getAsString();
        assertFalse( uri.isEmpty(),
            "documentationUri must be omitted rather than emitted as \"\" — a strict SCIM client "
                + "(Authentik) fails ServiceProviderConfig validation on an empty URL and then "
                + "silently provisions nothing" );
        assertDoesNotThrow( () -> URI.create( uri ).toURL(),
            "documentationUri, when present, must be an absolute URL; got: " + uri );
    }

    @Test
    void noStringFieldInServiceProviderConfigNamedUriIsEmpty() throws Exception {
        final JsonObject cfg = get( "/ServiceProviderConfig" );
        assertNoEmptyUriFields( cfg, "ServiceProviderConfig" );
    }

    /** Recursively asserts that no *Uri/*Url-named string anywhere in the payload is blank. */
    private static void assertNoEmptyUriFields( final JsonObject obj, final String path ) {
        for ( final String key : obj.keySet() ) {
            final var value = obj.get( key );
            final String where = path + "." + key;
            final String lower = key.toLowerCase( java.util.Locale.ROOT );
            if ( value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && ( lower.endsWith( "uri" ) || lower.endsWith( "url" ) ) ) {
                assertFalse( value.getAsString().isBlank(),
                    where + " is a blank URI — omit the field instead" );
            } else if ( value.isJsonObject() ) {
                assertNoEmptyUriFields( value.getAsJsonObject(), where );
            } else if ( value.isJsonArray() ) {
                for ( int i = 0; i < value.getAsJsonArray().size(); i++ ) {
                    final var el = value.getAsJsonArray().get( i );
                    if ( el.isJsonObject() ) {
                        assertNoEmptyUriFields( el.getAsJsonObject(), where + "[" + i + "]" );
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ shape sanity

    @Test
    void serviceProviderConfigDeclaresItsSchemaAndCapabilities() throws Exception {
        final JsonObject cfg = get( "/ServiceProviderConfig" );

        assertEquals( "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig",
            cfg.getAsJsonArray( "schemas" ).get( 0 ).getAsString() );
        assertTrue( cfg.getAsJsonObject( "patch" ).get( "supported" ).getAsBoolean(),
            "PATCH is how an IdP flips is_active; it must be advertised" );
        assertTrue( cfg.getAsJsonObject( "filter" ).get( "supported" ).getAsBoolean(),
            "filtering is how an IdP looks a user up by userName before creating it" );
        assertFalse( cfg.getAsJsonArray( "authenticationSchemes" ).isEmpty(),
            "at least one authentication scheme must be advertised" );
    }
}
