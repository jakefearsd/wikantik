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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class SSOConfigTest {

    @Test
    void testDisabledByDefault() {
        final Properties props = new Properties();
        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        Assertions.assertFalse( config.isEnabled() );
        Assertions.assertNull( config.getPac4jConfig() );
    }

    @Test
    void testExplicitlyDisabled() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "false" );

        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        Assertions.assertFalse( config.isEnabled() );
        Assertions.assertNull( config.getPac4jConfig() );
    }

    @Test
    void testEnabledWithMissingOidcProperties() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        // Missing clientId, clientSecret, discoveryUri

        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        Assertions.assertTrue( config.isEnabled() );
        // Config object exists but has no clients (they failed to build)
        Assertions.assertNotNull( config.getPac4jConfig() );
    }

    @Test
    void testDefaultSsoType() {
        final Properties props = new Properties();
        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        Assertions.assertEquals( "oidc", config.getSsoType() );
    }

    @Test
    void testAutoProvisionDefault() {
        final Properties props = new Properties();
        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        Assertions.assertTrue( config.isAutoProvision() );
    }

    @Test
    void testAutoProvisionDisabled() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "false" );

        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        Assertions.assertFalse( config.isAutoProvision() );
    }

    @Test
    void testDefaultClaimMappings() {
        final Properties props = new Properties();
        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        Assertions.assertEquals( "preferred_username", config.getClaimLoginName() );
        Assertions.assertEquals( "name", config.getClaimFullName() );
        Assertions.assertEquals( "email", config.getClaimEmail() );
    }

    @Test
    void testCustomClaimMappings() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PREFIX_CLAIM_MAPPING + "loginName", "upn" );
        props.setProperty( SSOConfig.PREFIX_CLAIM_MAPPING + "fullName", "displayName" );
        props.setProperty( SSOConfig.PREFIX_CLAIM_MAPPING + "email", "mail" );

        final SSOConfig config = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        Assertions.assertEquals( "upn", config.getClaimLoginName() );
        Assertions.assertEquals( "displayName", config.getClaimFullName() );
        Assertions.assertEquals( "mail", config.getClaimEmail() );
    }

    @Test
    void testSsoTypeValues() {
        final Properties oidcProps = new Properties();
        oidcProps.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        Assertions.assertEquals( "oidc", new SSOConfig( oidcProps, "http://localhost/cb" ).getSsoType() );

        final Properties samlProps = new Properties();
        samlProps.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        Assertions.assertEquals( "saml", new SSOConfig( samlProps, "http://localhost/cb" ).getSsoType() );

        final Properties bothProps = new Properties();
        bothProps.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        Assertions.assertEquals( "both", new SSOConfig( bothProps, "http://localhost/cb" ).getSsoType() );
    }

    @Test
    void testCallbackPath() {
        Assertions.assertEquals( "/sso/callback", SSOConfig.CALLBACK_PATH );
    }
}
