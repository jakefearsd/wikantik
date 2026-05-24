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

import com.wikantik.TestEngine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pac4j.core.profile.CommonProfile;

import java.util.Properties;

class SSOAutoProvisionServiceTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @Test
    void testProvisionNewUser() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "sso-new-user" );
        profile.addAttribute( "preferred_username", "sso-new-user" );
        profile.addAttribute( "name", "SSO New User" );
        profile.addAttribute( "email", "sso-new@example.com" );

        // Provision the user
        service.provisionIfNeeded( "sso-new-user", profile );

        // Verify the user was created
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile created = userDb.findByLoginName( "sso-new-user" );
        Assertions.assertNotNull( created );
        Assertions.assertEquals( "sso-new-user", created.getLoginName() );
        Assertions.assertEquals( "SSO New User", created.getFullname() );
        Assertions.assertEquals( "sso-new@example.com", created.getEmail() );

        // Clean up
        userDb.deleteByLoginName( "sso-new-user" );
    }

    @Test
    void testProvisionExistingUserDoesNotOverwrite() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );

        // First, create a user manually
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile existing = userDb.newProfile();
        existing.setLoginName( "sso-existing" );
        existing.setFullname( "Original Name" );
        existing.setEmail( "original@example.com" );
        userDb.save( existing );

        // Now try to provision the same user with different claims
        final CommonProfile profile = new CommonProfile();
        profile.setId( "sso-existing" );
        profile.addAttribute( "preferred_username", "sso-existing" );
        profile.addAttribute( "name", "New SSO Name" );
        profile.addAttribute( "email", "new-sso@example.com" );

        service.provisionIfNeeded( "sso-existing", profile );

        // Verify the original profile was NOT overwritten
        final UserProfile found = userDb.findByLoginName( "sso-existing" );
        Assertions.assertEquals( "Original Name", found.getFullname() );
        Assertions.assertEquals( "original@example.com", found.getEmail() );

        // Clean up
        userDb.deleteByLoginName( "sso-existing" );
    }

    @Test
    void testProvisionDisabledDoesNothing() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "false" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "sso-no-provision" );
        profile.addAttribute( "preferred_username", "sso-no-provision" );
        profile.addAttribute( "name", "Should Not Be Created" );

        service.provisionIfNeeded( "sso-no-provision", profile );

        // Verify no user was created
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> userDb.findByLoginName( "sso-no-provision" ) );
    }

    @Test
    void testProvisionWithMissingFullNameUsesLoginName() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "sso-noname" );
        // No "name" attribute - should fall back to login name

        service.provisionIfNeeded( "sso-noname", profile );

        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile created = userDb.findByLoginName( "sso-noname" );
        Assertions.assertEquals( "sso-noname", created.getFullname() );

        // Clean up
        userDb.deleteByLoginName( "sso-noname" );
    }

    @Test
    void testProvisionDeduplicatesCollidingWikiName() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );

        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();

        // A pre-existing account whose full name "Jake Fear" derives wiki name "JakeFear".
        final UserProfile existing = userDb.newProfile();
        existing.setLoginName( "jakefear-local" );
        existing.setFullname( "Jake Fear" );
        existing.setEmail( "jake@local.example" );
        userDb.save( existing );
        Assertions.assertEquals( "JakeFear", userDb.findByLoginName( "jakefear-local" ).getWikiName() );

        // An SSO login for a *different* account (distinct login name) that resolves the
        // same display name. Before the fix this threw on the unique wiki_name constraint
        // and no profile was persisted.
        final CommonProfile profile = new CommonProfile();
        profile.setId( "114291954688138286475" );
        profile.addAttribute( "email", "jakefear@simpleagility.com" );
        profile.addAttribute( "name", "Jake Fear" );

        service.provisionIfNeeded( "jakefear@simpleagility.com", "114291954688138286475", profile );

        final UserProfile created = userDb.findByLoginName( "jakefear@simpleagility.com" );
        Assertions.assertNotNull( created, "SSO profile must be persisted despite the wiki-name clash." );
        Assertions.assertEquals( "Jake Fear", created.getFullname() );
        // The de-duplicated wiki name is what was stored (and what a prod JDBC INSERT
        // would use to satisfy the unique constraint). Assert via a stored-attribute
        // lookup rather than getWikiName(), which re-derives from the full name on read.
        Assertions.assertEquals( "jakefear@simpleagility.com", userDb.findByWikiName( "JakeFear2" ).getLoginName(),
            "Colliding wiki name must be de-duplicated to JakeFear2 and stored." );
        // Original account keeps its wiki name.
        Assertions.assertEquals( "jakefear-local", userDb.findByWikiName( "JakeFear" ).getLoginName() );

        // Clean up
        userDb.deleteByLoginName( "jakefear@simpleagility.com" );
        userDb.deleteByLoginName( "jakefear-local" );
    }
}
