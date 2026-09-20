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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the credential/endpoint-host binding rule. */
class ConnectorEndpointBindingTest {

    private static final String ACME =
        "{\"base_url\":\"https://acme.atlassian.net\",\"space_key\":\"ENG\"}";

    private static JsonObject json( final String raw ) {
        return JsonParser.parseString( raw ).getAsJsonObject();
    }

    @Test
    void hostChangeWithStoredCredentialsIsRefused() {
        final Map< String, String > errors = ConnectorEndpointBinding.hostChangeErrors(
            ACME, json( "{\"base_url\":\"https://evil.example.com\"}" ), () -> true );

        assertEquals( 1, errors.size() );
        assertTrue( errors.containsKey( ConnectorEndpointBinding.ENDPOINT_URL_KEY ) );
        assertEquals( ConnectorEndpointBinding.MESSAGE,
            errors.get( ConnectorEndpointBinding.ENDPOINT_URL_KEY ) );
    }

    @Test
    void hostChangeWithoutStoredCredentialsIsAllowed() {
        assertTrue( ConnectorEndpointBinding.hostChangeErrors(
            ACME, json( "{\"base_url\":\"https://other.atlassian.net\"}" ), () -> false ).isEmpty() );
    }

    @Test
    void sameHostIsAllowedAndNeverConsultsTheCredentialStore() {
        // The supplier is the DB lookup; an ordinary edit must not pay for it.
        final AtomicInteger consulted = new AtomicInteger();
        final Map< String, String > errors = ConnectorEndpointBinding.hostChangeErrors(
            ACME, json( "{\"base_url\":\"https://acme.atlassian.net/wiki\",\"space_key\":\"OPS\"}" ),
            () -> { consulted.incrementAndGet(); return true; } );

        assertTrue( errors.isEmpty(), errors.toString() );
        assertEquals( 0, consulted.get(), "credential lookup must be skipped when the host is unchanged" );
    }

    @Test
    void hostComparisonIgnoresCase() {
        assertTrue( ConnectorEndpointBinding.hostChangeErrors(
            ACME, json( "{\"base_url\":\"https://ACME.Atlassian.NET\"}" ), () -> true ).isEmpty() );
    }

    @Test
    void connectorWithoutAnEndpointKeyIsUnaffected() {
        // github/gdrive carry no base_url — the rule must not fire for them.
        assertTrue( ConnectorEndpointBinding.hostChangeErrors(
            "{\"repo\":\"jake/notes\"}", json( "{\"repo\":\"jake/other\"}" ), () -> true ).isEmpty() );
    }

    @Test
    void unparseableStoredConfigDegradesToAllowing() {
        assertTrue( ConnectorEndpointBinding.hostChangeErrors(
            "not json at all", json( "{\"base_url\":\"https://evil.example.com\"}" ), () -> true ).isEmpty() );
    }

    @Test
    void blankEndpointUrlIsTreatedAsAbsent() {
        assertTrue( ConnectorEndpointBinding.hostChangeErrors(
            "{\"base_url\":\"   \"}", json( "{\"base_url\":\"https://evil.example.com\"}" ), () -> true ).isEmpty() );
    }
}
