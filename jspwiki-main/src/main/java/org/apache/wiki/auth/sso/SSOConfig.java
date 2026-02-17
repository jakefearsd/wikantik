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
package org.apache.wiki.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.jee.adapter.JEEFrameworkAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads SSO configuration from {@code jspwiki.properties} and builds a pac4j
 * {@link Config} with the appropriate OIDC and/or SAML clients.
 *
 * @since 3.1
 */
public class SSOConfig {

    private static final Logger LOG = LogManager.getLogger( SSOConfig.class );

    /** Property for enabling SSO. */
    public static final String PROP_SSO_ENABLED = "jspwiki.sso.enabled";

    /** Property for SSO type: "oidc", "saml", or "both". */
    public static final String PROP_SSO_TYPE = "jspwiki.sso.type";

    /** OIDC discovery URI. */
    public static final String PROP_OIDC_DISCOVERY_URI = "jspwiki.sso.oidc.discoveryUri";

    /** OIDC client ID. */
    public static final String PROP_OIDC_CLIENT_ID = "jspwiki.sso.oidc.clientId";

    /** OIDC client secret. */
    public static final String PROP_OIDC_CLIENT_SECRET = "jspwiki.sso.oidc.clientSecret";

    /** OIDC scopes. */
    public static final String PROP_OIDC_SCOPE = "jspwiki.sso.oidc.scope";

    /** SAML IdP metadata path. */
    public static final String PROP_SAML_IDP_METADATA = "jspwiki.sso.saml.identityProviderMetadataPath";

    /** SAML SP entity ID. */
    public static final String PROP_SAML_SP_ENTITY_ID = "jspwiki.sso.saml.serviceProviderEntityId";

    /** SAML keystore path. */
    public static final String PROP_SAML_KEYSTORE_PATH = "jspwiki.sso.saml.keystorePath";

    /** SAML keystore password. */
    public static final String PROP_SAML_KEYSTORE_PASSWORD = "jspwiki.sso.saml.keystorePassword";

    /** SAML private key password. */
    public static final String PROP_SAML_PRIVATE_KEY_PASSWORD = "jspwiki.sso.saml.privateKeyPassword";

    /** Auto-provisioning enabled. */
    public static final String PROP_AUTO_PROVISION = "jspwiki.sso.autoProvision";

    /** Claim mapping prefix for IdP attribute to JSPWiki field mapping. */
    public static final String PREFIX_CLAIM_MAPPING = "jspwiki.sso.claimMapping.";

    /** The SSO callback URL path. */
    public static final String CALLBACK_PATH = "/sso/callback";

    /** Default OIDC scopes. */
    private static final String DEFAULT_SCOPE = "openid profile email";

    private final boolean enabled;
    private final String ssoType;
    private final boolean autoProvision;
    private final String claimLoginName;
    private final String claimFullName;
    private final String claimEmail;
    private final Config pac4jConfig;

    /**
     * Constructs a new SSOConfig from wiki properties.
     *
     * @param props the wiki properties
     * @param callbackUrl the full URL for the SSO callback endpoint (e.g., "http://localhost:8080/JSPWiki/sso/callback")
     */
    public SSOConfig( final Properties props, final String callbackUrl ) {
        this.enabled = Boolean.parseBoolean( props.getProperty( PROP_SSO_ENABLED, "false" ) );
        this.ssoType = props.getProperty( PROP_SSO_TYPE, "oidc" );
        this.autoProvision = Boolean.parseBoolean( props.getProperty( PROP_AUTO_PROVISION, "true" ) );
        this.claimLoginName = props.getProperty( PREFIX_CLAIM_MAPPING + "loginName", "preferred_username" );
        this.claimFullName = props.getProperty( PREFIX_CLAIM_MAPPING + "fullName", "name" );
        this.claimEmail = props.getProperty( PREFIX_CLAIM_MAPPING + "email", "email" );

        if( enabled ) {
            final List< Client > clients = new ArrayList<>();
            if( "oidc".equalsIgnoreCase( ssoType ) || "both".equalsIgnoreCase( ssoType ) ) {
                buildOidcClient( props, clients );
            }
            if( "saml".equalsIgnoreCase( ssoType ) || "both".equalsIgnoreCase( ssoType ) ) {
                buildSamlClient( props, clients );
            }

            final Config config = new Config();
            config.setClients( new Clients( callbackUrl, clients ) );
            new JEEFrameworkAdapter().applyDefaultSettingsIfUndefined( config );
            this.pac4jConfig = config;
            LOG.info( "SSO enabled with type '{}', {} client(s) configured.", ssoType, clients.size() );
        } else {
            this.pac4jConfig = null;
            LOG.info( "SSO is disabled." );
        }
    }

    private void buildOidcClient( final Properties props, final List< Client > clients ) {
        final String discoveryUri = props.getProperty( PROP_OIDC_DISCOVERY_URI );
        final String clientId = props.getProperty( PROP_OIDC_CLIENT_ID );
        final String clientSecret = props.getProperty( PROP_OIDC_CLIENT_SECRET );
        final String scope = props.getProperty( PROP_OIDC_SCOPE, DEFAULT_SCOPE );

        if( discoveryUri == null || clientId == null || clientSecret == null ) {
            LOG.error( "OIDC SSO is enabled but missing required properties: {}, {}, {}",
                PROP_OIDC_DISCOVERY_URI, PROP_OIDC_CLIENT_ID, PROP_OIDC_CLIENT_SECRET );
            return;
        }

        try {
            final var oidcConfig = new org.pac4j.oidc.config.OidcConfiguration();
            oidcConfig.setDiscoveryURI( discoveryUri );
            oidcConfig.setClientId( clientId );
            oidcConfig.setSecret( clientSecret );
            oidcConfig.setScope( scope );
            // Explicitly set client authentication method to prevent pac4j from
            // auto-selecting private_key_jwt (which requires a PrivateKeyJwtConfig).
            oidcConfig.setClientAuthenticationMethod(
                com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod.CLIENT_SECRET_BASIC );

            final var oidcClient = new org.pac4j.oidc.client.OidcClient( oidcConfig );
            oidcClient.setName( "OidcClient" );
            clients.add( oidcClient );
            LOG.info( "Configured OIDC client with discovery URI: {}", discoveryUri );
        } catch( final Exception e ) {
            LOG.error( "Failed to configure OIDC client", e );
        }
    }

    private void buildSamlClient( final Properties props, final List< Client > clients ) {
        final String idpMetadata = props.getProperty( PROP_SAML_IDP_METADATA );
        final String spEntityId = props.getProperty( PROP_SAML_SP_ENTITY_ID );
        final String keystorePath = props.getProperty( PROP_SAML_KEYSTORE_PATH );
        final String keystorePassword = props.getProperty( PROP_SAML_KEYSTORE_PASSWORD );
        final String privateKeyPassword = props.getProperty( PROP_SAML_PRIVATE_KEY_PASSWORD );

        if( idpMetadata == null || spEntityId == null ) {
            LOG.error( "SAML SSO is enabled but missing required properties: {}, {}",
                PROP_SAML_IDP_METADATA, PROP_SAML_SP_ENTITY_ID );
            return;
        }

        try {
            final var samlConfig = new org.pac4j.saml.config.SAML2Configuration(
                keystorePath, keystorePassword, privateKeyPassword, idpMetadata );
            samlConfig.setServiceProviderEntityId( spEntityId );

            final var samlClient = new org.pac4j.saml.client.SAML2Client( samlConfig );
            samlClient.setName( "SAML2Client" );
            clients.add( samlClient );
            LOG.info( "Configured SAML client with SP entity ID: {}", spEntityId );
        } catch( final Exception e ) {
            LOG.error( "Failed to configure SAML client", e );
        }
    }

    /** Returns {@code true} if SSO is enabled in the configuration. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Returns the SSO type: "oidc", "saml", or "both". */
    public String getSsoType() {
        return ssoType;
    }

    /** Returns {@code true} if auto-provisioning of user profiles is enabled. */
    public boolean isAutoProvision() {
        return autoProvision;
    }

    /** Returns the pac4j Config, or {@code null} if SSO is disabled. */
    public Config getPac4jConfig() {
        return pac4jConfig;
    }

    /** Returns the IdP claim name mapped to the JSPWiki login name. */
    public String getClaimLoginName() {
        return claimLoginName;
    }

    /** Returns the IdP claim name mapped to the JSPWiki full name. */
    public String getClaimFullName() {
        return claimFullName;
    }

    /** Returns the IdP claim name mapped to the JSPWiki email. */
    public String getClaimEmail() {
        return claimEmail;
    }
}
