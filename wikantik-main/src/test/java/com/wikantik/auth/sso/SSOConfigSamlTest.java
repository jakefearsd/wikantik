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
import org.junit.jupiter.api.io.TempDir;
import org.pac4j.core.client.Client;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Pins the SAML config degradation contract: missing required SAML props must
 * produce zero clients (visible misconfig), and in "both" mode a SAML
 * construction failure at startup (e.g. missing IdP metadata file) must not
 * prevent the OIDC client from being registered — SAML degrades gracefully
 * while OIDC login remains available as the first (and possibly only) client.
 */
class SSOConfigSamlTest {

    @Test
    void samlMissingRequiredPropsProducesNoClient() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        // idpMetadata and spEntityId are intentionally absent
        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );
        Assertions.assertTrue( cfg.getPac4jConfig().getClients().findAllClients().isEmpty(),
            "SAML with missing required props must yield zero clients (visible misconfig)." );
    }

    @Test
    void bothModeRegistersOidcWhenSamlDegrades( @TempDir final Path tmp ) {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        p.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "http://localhost:8088/default/.well-known/openid-configuration" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "id" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "secret" );
        // Point SAML props at paths under tmp; the files won't exist, so SAML
        // construction will fail at startup. buildSamlClient's try/catch absorbs
        // that failure — SAML degrades gracefully and OidcClient is still registered.
        p.setProperty( SSOConfig.PROP_SAML_IDP_METADATA, tmp.resolve( "idp-metadata.xml" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_SP_ENTITY_ID, "wikantik-sp" );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PATH, tmp.resolve( "sp-keystore.jks" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PASSWORD, "changeit" );
        p.setProperty( SSOConfig.PROP_SAML_PRIVATE_KEY_PASSWORD, "changeit" );

        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );
        final List< Client > clients = cfg.getPac4jConfig().getClients().findAllClients();

        // At minimum the OidcClient must be present (construction is lazy — no network call).
        final boolean oidcPresent = clients.stream().anyMatch( c -> "OidcClient".equals( c.getName() ) );
        Assertions.assertTrue( oidcPresent,
            "OidcClient must be registered in 'both' mode when OIDC props are present." );

        // When SAML degrades, OidcClient must occupy the first slot so that the
        // no-client_name fallback in SSORedirectServlet picks it (OIDC is the primary flow).
        final int oidcIndex = clients.indexOf(
            clients.stream().filter( c -> "OidcClient".equals( c.getName() ) ).findFirst().orElseThrow() );
        Assertions.assertEquals( 0, oidcIndex,
            "OIDC client must be first — SAML degrades without displacing the OIDC login slot." );
    }
}
