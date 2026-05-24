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
import org.pac4j.core.http.callback.NoParameterCallbackUrlResolver;

import java.util.Properties;

/**
 * Guards the OAuth/SAML callback URL shape. pac4j's default
 * {@code QueryParameterCallbackUrlResolver} appends {@code ?client_name=<name>}
 * to the callback URL, which becomes the OAuth {@code redirect_uri}. Strict
 * IdPs such as Google require the {@code redirect_uri} to match a registered
 * value exactly and reject query strings, so a single-client deployment must
 * use a clean, parameter-free callback URL.
 *
 * <p>Without the fix in {@link SSOConfig}, the single-client assertion below
 * fails because the Clients holder keeps the query-parameter resolver.
 */
class SSOConfigCallbackUrlTest {

    private static Properties oidcProps() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        p.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI,
            "https://accounts.google.com/.well-known/openid-configuration" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "test-client-id" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "test-client-secret" );
        return p;
    }

    @Test
    void singleClientUsesParameterFreeCallbackUrl() {
        final SSOConfig cfg = new SSOConfig( oidcProps(), "https://wiki.wikantik.com/sso/callback" );

        Assertions.assertEquals( 1, cfg.getPac4jConfig().getClients().findAllClients().size(),
            "Precondition: exactly one client should be configured for oidc-only." );
        Assertions.assertInstanceOf( NoParameterCallbackUrlResolver.class,
            cfg.getPac4jConfig().getClients().getCallbackUrlResolver(),
            "A single-client deployment must use a clean callback URL so the OAuth "
            + "redirect_uri has no ?client_name= query string (Google rejects it)." );
    }

    @Test
    void bothModeKeepsQueryParameterResolverForDisambiguation() throws Exception {
        final Properties p = oidcProps();
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        final java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory( "sso-both" );
        p.setProperty( SSOConfig.PROP_SAML_IDP_METADATA, tmp.resolve( "idp-metadata.xml" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_SP_ENTITY_ID, "wikantik-sp" );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PATH, tmp.resolve( "sp-keystore.jks" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PASSWORD, "changeit" );
        p.setProperty( SSOConfig.PROP_SAML_PRIVATE_KEY_PASSWORD, "changeit" );

        final SSOConfig cfg = new SSOConfig( p, "https://wiki.wikantik.com/sso/callback" );

        // With two clients sharing one callback endpoint, the query-parameter
        // resolver is needed to tell them apart — so we must NOT have swapped in
        // the parameter-free resolver.
        Assertions.assertFalse(
            cfg.getPac4jConfig().getClients().getCallbackUrlResolver() instanceof NoParameterCallbackUrlResolver,
            "Multi-client mode must retain the query-parameter resolver for client disambiguation." );
    }
}
