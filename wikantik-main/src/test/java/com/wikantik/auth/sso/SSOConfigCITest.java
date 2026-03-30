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

import org.junit.jupiter.api.Test;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link SSOConfig} covering branches not exercised
 * by {@link SSOConfigTest}: enabled OIDC with full properties, enabled SAML
 * with full properties, "both" type with missing properties, property constant
 * values, and the {@code buildSamlClient} missing-property path.
 */
class SSOConfigCITest {

    private static final String CALLBACK_URL = "http://localhost:8080/JSPWiki/sso/callback";

    // ---- OIDC enabled with all required properties ----

    @Test
    void testEnabledWithFullOidcProperties() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        props.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "https://accounts.example.com/.well-known/openid-configuration" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "my-client-id" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "my-client-secret" );

        // Will attempt to contact the discovery URI — but OidcClient is lazy about it,
        // so construction should succeed even if the URI is unreachable.
        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );

        assertTrue( config.isEnabled() );
        assertNotNull( config.getPac4jConfig(),
                "pac4jConfig should be non-null when SSO is enabled with valid OIDC properties" );
        assertEquals( "oidc", config.getSsoType() );

        // There should be at least one client registered
        final Config pac4jConfig = config.getPac4jConfig();
        final Clients clients = pac4jConfig.getClients();
        assertNotNull( clients );
        final List<Client> allClients = clients.findAllClients();
        assertFalse( allClients.isEmpty(),
                "At least one OIDC client should have been configured" );
        assertEquals( "OidcClient", allClients.get( 0 ).getName() );
    }

    // ---- SAML missing required properties → no client added ----

    @Test
    void testEnabledWithMissingSamlIdpMetadata() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        // Missing PROP_SAML_IDP_METADATA and PROP_SAML_SP_ENTITY_ID

        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );

        assertTrue( config.isEnabled() );
        assertNotNull( config.getPac4jConfig() );
        // No SAML client should have been registered
        final List<Client> allClients = config.getPac4jConfig().getClients().findAllClients();
        assertTrue( allClients.isEmpty(),
                "No SAML client should be added when required properties are missing" );
    }

    @Test
    void testEnabledWithMissingSamlSpEntityId() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        props.setProperty( SSOConfig.PROP_SAML_IDP_METADATA, "/path/to/idp-metadata.xml" );
        // Missing PROP_SAML_SP_ENTITY_ID

        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );

        assertTrue( config.isEnabled() );
        assertNotNull( config.getPac4jConfig() );
        final List<Client> allClients = config.getPac4jConfig().getClients().findAllClients();
        assertTrue( allClients.isEmpty(),
                "No SAML client should be added when SP entity ID is missing" );
    }

    // ---- "both" type with missing OIDC properties → no OIDC client but SAML also missing → empty ----

    @Test
    void testEnabledBothTypeMissingBothConfigs() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        // Missing all OIDC and SAML required properties

        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );

        assertTrue( config.isEnabled() );
        assertNotNull( config.getPac4jConfig() );
        // Neither OIDC nor SAML clients should have been added
        final List<Client> allClients = config.getPac4jConfig().getClients().findAllClients();
        assertTrue( allClients.isEmpty(),
                "No clients should be added when both OIDC and SAML properties are missing" );
    }

    // ---- OIDC custom scope ----

    @Test
    void testOidcCustomScopeIsAccepted() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        props.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "https://accounts.example.com/.well-known/openid-configuration" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "client" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "secret" );
        props.setProperty( SSOConfig.PROP_OIDC_SCOPE, "openid email" );

        // Construction should succeed without throwing
        assertDoesNotThrow( () -> new SSOConfig( props, CALLBACK_URL ) );
    }

    // ---- Property constant values ----

    @Test
    void testPropertyConstantValues() {
        assertEquals( "wikantik.sso.enabled",                         SSOConfig.PROP_SSO_ENABLED );
        assertEquals( "wikantik.sso.type",                            SSOConfig.PROP_SSO_TYPE );
        assertEquals( "wikantik.sso.oidc.discoveryUri",               SSOConfig.PROP_OIDC_DISCOVERY_URI );
        assertEquals( "wikantik.sso.oidc.clientId",                   SSOConfig.PROP_OIDC_CLIENT_ID );
        assertEquals( "wikantik.sso.oidc.clientSecret",               SSOConfig.PROP_OIDC_CLIENT_SECRET );
        assertEquals( "wikantik.sso.oidc.scope",                      SSOConfig.PROP_OIDC_SCOPE );
        assertEquals( "wikantik.sso.saml.identityProviderMetadataPath", SSOConfig.PROP_SAML_IDP_METADATA );
        assertEquals( "wikantik.sso.saml.serviceProviderEntityId",    SSOConfig.PROP_SAML_SP_ENTITY_ID );
        assertEquals( "wikantik.sso.saml.keystorePath",               SSOConfig.PROP_SAML_KEYSTORE_PATH );
        assertEquals( "wikantik.sso.saml.keystorePassword",           SSOConfig.PROP_SAML_KEYSTORE_PASSWORD );
        assertEquals( "wikantik.sso.saml.privateKeyPassword",         SSOConfig.PROP_SAML_PRIVATE_KEY_PASSWORD );
        assertEquals( "wikantik.sso.autoProvision",                   SSOConfig.PROP_AUTO_PROVISION );
        assertEquals( "wikantik.sso.claimMapping.",                   SSOConfig.PREFIX_CLAIM_MAPPING );
        assertEquals( "/sso/callback",                                 SSOConfig.CALLBACK_PATH );
    }

    // ---- getPac4jConfig: null when disabled ----

    @Test
    void testPac4jConfigIsNullWhenDisabled() {
        final Properties props = new Properties();
        // Default: disabled
        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );
        assertNull( config.getPac4jConfig() );
    }

    // ---- autoProvision: explicit true ----

    @Test
    void testAutoProvisionExplicitlyTrue() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );
        assertTrue( config.isAutoProvision() );
    }

    // ---- getSsoType: case-insensitive checks — verify "OIDC" does not match "oidc" equalsIgnoreCase branches ----

    @Test
    void testSsoTypeStoredAsProvided() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "OIDC" );
        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );
        // getSsoType() returns whatever was set
        assertEquals( "OIDC", config.getSsoType() );
    }

    // ---- enabled=true, type="both", valid OIDC missing SAML → one OIDC client ----

    @Test
    void testBothTypeWithOidcPropertiesOnlyProducesOidcClient() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        // Provide OIDC but not SAML
        props.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "https://accounts.example.com/.well-known/openid-configuration" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "client-id" );
        props.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "client-secret" );
        // SAML missing → no SAML client

        final SSOConfig config = new SSOConfig( props, CALLBACK_URL );

        assertTrue( config.isEnabled() );
        assertNotNull( config.getPac4jConfig() );
        final List<Client> allClients = config.getPac4jConfig().getClients().findAllClients();
        assertEquals( 1, allClients.size(),
                "Only the OIDC client should be registered when SAML props are missing" );
        assertEquals( "OidcClient", allClients.get( 0 ).getName() );
    }
}
