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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.config.Config;

import java.util.List;
import java.util.Optional;

/**
 * Pins the pac4j {@link Clients} selection contract that
 * {@link SSORedirectServlet} relies on: {@code findClient(name)} returns the
 * named client, and {@code findAllClients().stream().findFirst()} returns the
 * first registered client when no {@code client_name} param is present.
 *
 * <p>These tests exercise the pac4j library directly — no servlet, no engine,
 * no Mockito — so any breaking pac4j API change will surface as a test failure
 * before it silently breaks the login flow.
 */
class SSORedirectServletClientSelectionTest {

    @Test
    void explicitClientNameSelectsThatClient() {
        final Config cfg = configWithTwoClients();
        final Optional< Client > selected = cfg.getClients().findClient( "SAML2Client" );
        Assertions.assertTrue( selected.isPresent() );
        Assertions.assertEquals( "SAML2Client", selected.get().getName() );
    }

    @Test
    void noClientNameFallsBackToFirst() {
        final Config cfg = configWithTwoClients();
        final Optional< Client > first = cfg.getClients().findAllClients().stream().findFirst();
        Assertions.assertTrue( first.isPresent() );
        Assertions.assertEquals( "OidcClient", first.get().getName() );
    }

    private static Config configWithTwoClients() {
        final Clients clients = new Clients( "http://localhost/sso/callback" );
        clients.setClients( List.of( namedClient( "OidcClient" ), namedClient( "SAML2Client" ) ) );
        return new Config( clients );
    }

    /**
     * Minimal named pac4j client stub for selection-logic assertions.
     * Only {@code internalInit} is abstract in pac4j 6.5.1's
     * {@link IndirectClient} hierarchy; a no-op body is sufficient.
     */
    private static Client namedClient( final String name ) {
        final IndirectClient c = new IndirectClient()
        {
            @Override
            protected void internalInit( final boolean forceReinit ) { /* no-op stub */ }
        };
        c.setName( name );
        c.setCallbackUrl( "http://localhost/sso/callback" );
        return c;
    }
}
