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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.http.callback.NoParameterCallbackUrlResolver;
import org.pac4j.jee.adapter.JEEFrameworkAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads SSO configuration from {@code wikantik.properties} and builds a pac4j
 * {@link Config} with the appropriate OIDC and/or SAML clients.
 *
 * @since 3.1
 */
public class SSOConfig {

    private static final Logger LOG = LogManager.getLogger( SSOConfig.class );

    /** Property for enabling SSO. */
    public static final String PROP_SSO_ENABLED = "wikantik.sso.enabled";

    /** Property for SSO type: "oidc", "saml", or "both". */
    public static final String PROP_SSO_TYPE = "wikantik.sso.type";

    /** OIDC discovery URI. */
    public static final String PROP_OIDC_DISCOVERY_URI = "wikantik.sso.oidc.discoveryUri";

    /** OIDC client ID. */
    public static final String PROP_OIDC_CLIENT_ID = "wikantik.sso.oidc.clientId";

    /** OIDC client secret. */
    public static final String PROP_OIDC_CLIENT_SECRET = "wikantik.sso.oidc.clientSecret";

    /** OIDC scopes. */
    public static final String PROP_OIDC_SCOPE = "wikantik.sso.oidc.scope";

    /** SAML IdP metadata path. */
    public static final String PROP_SAML_IDP_METADATA = "wikantik.sso.saml.identityProviderMetadataPath";

    /** SAML SP entity ID. */
    public static final String PROP_SAML_SP_ENTITY_ID = "wikantik.sso.saml.serviceProviderEntityId";

    /** SAML keystore path. */
    public static final String PROP_SAML_KEYSTORE_PATH = "wikantik.sso.saml.keystorePath";

    /** SAML keystore password. */
    public static final String PROP_SAML_KEYSTORE_PASSWORD = "wikantik.sso.saml.keystorePassword";

    /** SAML private key password. */
    public static final String PROP_SAML_PRIVATE_KEY_PASSWORD = "wikantik.sso.saml.privateKeyPassword";

    /**
     * SAML AuthnRequest binding type. Controls which binding pac4j uses when
     * sending the AuthnRequest to the IdP's SingleSignOnService. The value must
     * match a binding the IdP advertises in its metadata.
     *
     * <p>HTTP-Redirect ({@code urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect})
     * is the supported and recommended binding — the SSORedirectServlet issues a
     * 302 redirect carrying the AuthnRequest as a query parameter, which is what
     * all major IdPs expect. HTTP-POST AuthnRequest (form-post content action) is
     * not supported by the current redirect servlet implementation; do not configure
     * {@code urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST} here unless the
     * redirect servlet is extended to render form-post content.
     */
    public static final String PROP_SAML_AUTHN_REQUEST_BINDING = "wikantik.sso.saml.authnRequestBindingType";

    /**
     * Optional path where pac4j writes the generated SP metadata XML file.
     * When set, pac4j persists the SP's {@code SPSSODescriptor} (including the
     * registered ACS URL) to this file and re-reads it on restart. Without this,
     * pac4j holds the SP metadata only in memory and may fail to resolve the ACS
     * URL during assertion validation against some IdP implementations.
     *
     * <p>For IT environments, set this to a writable path inside the build's
     * {@code target/} directory (e.g.
     * {@code ${project.build.directory}/test-classes/sp-metadata.xml}).
     */
    public static final String PROP_SAML_SP_METADATA_PATH = "wikantik.sso.saml.serviceProviderMetadataPath";

    /** Auto-provisioning enabled. */
    public static final String PROP_AUTO_PROVISION = "wikantik.sso.autoProvision";

    /** IdP claim treated as the immutable identity (account link key). */
    public static final String PROP_SSO_IDENTITY_CLAIM = "wikantik.sso.identityClaim";

    /** Claim mapping prefix for IdP attribute to JSPWiki field mapping. */
    public static final String PREFIX_CLAIM_MAPPING = "wikantik.sso.claimMapping.";

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
    private final String identityClaim;
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
        this.identityClaim = props.getProperty( PROP_SSO_IDENTITY_CLAIM, "sub" );

        if( enabled ) {
            final List< Client > clients = new ArrayList<>();
            if( "oidc".equalsIgnoreCase( ssoType ) || "both".equalsIgnoreCase( ssoType ) ) {
                buildOidcClient( props, clients );
            }
            if( "saml".equalsIgnoreCase( ssoType ) || "both".equalsIgnoreCase( ssoType ) ) {
                buildSamlClient( props, callbackUrl, clients );
            }

            final Clients clientsHolder = new Clients( callbackUrl, clients );
            // pac4j's default QueryParameterCallbackUrlResolver appends
            // ?client_name=<name> to the callback URL, which then becomes the
            // OAuth redirect_uri (and SAML ACS URL). Strict IdPs such as Google
            // require the redirect_uri to exactly match a registered value and
            // reject any query string, producing redirect_uri_mismatch. For the
            // common single-client deployment we therefore use a clean,
            // parameter-free callback URL; with one client pac4j's
            // DefaultCallbackClientFinder still resolves it without the param.
            // "both" mode keeps the query-parameter resolver so the two clients
            // remain distinguishable at the shared callback endpoint.
            if( clients.size() == 1 ) {
                clientsHolder.setCallbackUrlResolver( new NoParameterCallbackUrlResolver() );
            }
            final Config config = new Config();
            config.setClients( clientsHolder );
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

    private void buildSamlClient( final Properties props, final String callbackUrl, final List< Client > clients ) {
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

        final String authnBindingType = props.getProperty( PROP_SAML_AUTHN_REQUEST_BINDING );
        final String spMetadataPath = props.getProperty( PROP_SAML_SP_METADATA_PATH );

        try {
            final var samlConfig = new org.pac4j.saml.config.SAML2Configuration(
                keystorePath, keystorePassword, privateKeyPassword, idpMetadata );
            samlConfig.setServiceProviderEntityId( spEntityId );
            if( authnBindingType != null && !authnBindingType.isBlank() ) {
                samlConfig.setAuthnRequestBindingType( authnBindingType );
                LOG.debug( "SAML AuthnRequest binding type set to: {}", authnBindingType );
            }
            if( spMetadataPath != null && !spMetadataPath.isBlank() ) {
                samlConfig.setServiceProviderMetadataPath( spMetadataPath );
                LOG.debug( "SAML SP metadata path set to: {}", spMetadataPath );
            }

            // Explicitly register the ACS URL that the IdP will use as the assertion
            // Destination. pac4j's QueryParameterCallbackUrlResolver would otherwise
            // append "?client_name=SAML2Client" to the ACS Location in the SP metadata,
            // causing a Destination mismatch when the IdP posts the assertion back to
            // the raw callback path (without the client_name query parameter).
            // Setting assertionConsumerServiceUrl ensures the SP metadata registers
            // exactly the URL the IdP will post to.
            if( callbackUrl != null && !callbackUrl.isBlank() ) {
                samlConfig.setAssertionConsumerServiceUrl( callbackUrl );
                LOG.debug( "SAML ACS URL set to: {}", callbackUrl );
            }

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

    /** Returns the IdP claim used as the immutable account-link identity. */
    public String getIdentityClaim() {
        return identityClaim;
    }
}
