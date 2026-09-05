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

import java.util.Properties;

/**
 * The configuration-surface burn-down (task 7) declares every {@code wikantik.sso.*}
 * property explicitly in {@code ini/wikantik.properties}, including the
 * required-when-enabled ones (OIDC discovery/clientId/clientSecret, SAML IdP
 * metadata/SP entity id), which now ship as an explicit blank value rather than
 * being absent (commented out). {@link SSOConfig#buildOidcClient} /
 * {@code buildSamlClient} originally guarded on {@code == null} only, so a
 * present-but-blank property (the shape {@code Properties.getProperty} returns
 * for {@code key =}) slipped past the guard and built a broken client instead
 * of skipping cleanly like the absent-property case does.
 */
class SSOConfigBlankPropertiesTest {

    @Test
    void blankOidcPropertiesProduceNoClient() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "oidc" );
        p.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "" );

        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );

        Assertions.assertTrue( cfg.getPac4jConfig().getClients().findAllClients().isEmpty(),
            "blank OIDC properties (declared but empty) must behave exactly like absent properties: no client built" );
    }

    @Test
    void blankSamlPropertiesProduceNoClient() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        p.setProperty( SSOConfig.PROP_SAML_IDP_METADATA, "" );
        p.setProperty( SSOConfig.PROP_SAML_SP_ENTITY_ID, "" );

        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );

        Assertions.assertTrue( cfg.getPac4jConfig().getClients().findAllClients().isEmpty(),
            "blank SAML properties (declared but empty) must behave exactly like absent properties: no client built" );
    }
}
