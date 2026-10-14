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

    private SSOAutoProvisionService service() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        return new SSOAutoProvisionService( engine, new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" ) );
    }

    private UserDatabase userDb() {
        return engine.getManager( UserManager.class ).getUserDatabase();
    }

    /** Provisions {@code login} with the given IdP name claim (may be null) and returns the stored display name. */
    private String provisioned( final String login, final String nameClaim ) throws Exception {
        final CommonProfile profile = new CommonProfile();
        profile.setId( login );
        if ( nameClaim != null ) {
            profile.addAttribute( "name", nameClaim );
        }
        service().provisionIfNeeded( login, login, profile );
        return userDb().findByLoginName( login ).getFullname();
    }

    private void saveLocalAccount( final String login, final String fullName ) throws Exception {
        final UserProfile p = userDb().newProfile();
        p.setLoginName( login );
        com.wikantik.auth.ProfileNameRules.applyFullName( engine, userDb(), p, fullName );
        p.setEmail( login + "@example.test" );
        userDb().save( p );
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
        service.provisionIfNeeded( "sso-new-user", "sso-new-user", profile );

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

        service.provisionIfNeeded( "sso-existing", "sso-existing", profile );

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

        service.provisionIfNeeded( "sso-no-provision", "sso-no-provision", profile );

        // Verify no user was created
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> userDb.findByLoginName( "sso-no-provision" ) );
    }

    @Test
    void testProvisionNameClaimEqualToAnotherAccountIsSuffixed() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();

        final UserProfile boss = userDb.newProfile();
        boss.setLoginName( "ssoboss" );
        boss.setFullname( "Sso Boss" );
        boss.setEmail( "ssoboss@example.com" );
        userDb.save( boss );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "sso-claimer" );
        profile.addAttribute( "name", "ssoboss" );

        service.provisionIfNeeded( "sso-claimer", "sso-claimer", profile );

        Assertions.assertEquals( "ssoboss 2", userDb.findByLoginName( "sso-claimer" ).getFullname(),
                "a name claim equal to another account's login name gets a numeric suffix" );

        userDb.deleteByLoginName( "sso-claimer" );
        userDb.deleteByLoginName( "ssoboss" );
    }

    @Test
    void testProvisionReservedLoginNameGetsNonReservedDisplayName() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        final SSOConfig ssoConfig = new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" );
        final SSOAutoProvisionService service = new SSOAutoProvisionService( engine, ssoConfig );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "authenticated" );

        service.provisionIfNeeded( "authenticated", "authenticated", profile );

        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile created = userDb.findByLoginName( "authenticated" );
        Assertions.assertFalse( com.wikantik.auth.ReservedProfileNames.isReserved( engine, created.getFullname() ),
                "provisioned full name must not be reserved: " + created.getFullname() );
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> userDb.findByWikiName( "authenticated" ) );

        userDb.deleteByLoginName( "authenticated" );
    }

    // was testProvisionWithMissingFullNameUsesLoginName — the login (an email on prod) must never become the name
    @Test
    void testProvisionWithMissingNameClaimGetsAPlaceholder() throws Exception {
        Assertions.assertEquals( "Member 1", provisioned( "sso-noname", null ) );
        Assertions.assertEquals( "Member 2", provisioned( "sso-noname-2", null ), "smallest free N" );
    }

    // was testProvisionReplacesReservedDisplayName — a reserved claim is unusable; it no longer falls back to the login
    @Test
    void testProvisionReplacesReservedDisplayNameWithAPlaceholder() throws Exception {
        Assertions.assertEquals( "Member 1", provisioned( "sso-reserved", "Admin" ) );
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> userDb().findByWikiName( "Admin" ) );
    }

    @Test
    void testProvisionClaimUsedAsIsWhenFree() throws Exception {
        Assertions.assertEquals( "Jane Q. Public", provisioned( "sso-free", "  Jane Q. Public " ), "trimmed claim" );
    }

    @Test
    void testProvisionTakenClaimIsNumberedIgnoringCaseAndWhitespace() throws Exception {
        saveLocalAccount( "jf-local", "Jake Fear" );
        Assertions.assertEquals( "Jake Fear 2", provisioned( "sso-jf2", "Jake Fear" ) );
        Assertions.assertEquals( "Jake Fear 3", provisioned( "sso-jf3", "Jake Fear" ) );
        Assertions.assertEquals( "jake fear 4", provisioned( "sso-jf4", " jake fear " ),
                "case-insensitive clash; the claim keeps its own spelling in the numbered form" );
    }

    @Test
    void testProvisionEmailShapedClaimIsUnusable() throws Exception {
        final String name = provisioned( "sso-email-claim", "jane.doe@example.com" );
        Assertions.assertEquals( "Member 1", name );
        Assertions.assertFalse( name.contains( "@" ) );
    }

    @Test
    void testProvisionClaimEqualToAnotherLoginIsNumberedCaseInsensitively() throws Exception {
        saveLocalAccount( "ssoboss", "Sso Boss" );
        Assertions.assertEquals( "SSOBoss 2", provisioned( "sso-claimer", "SSOBoss" ) );
    }

    @Test
    void testProvisionNeverUsesAnEmailLoginAsTheDisplayName() throws Exception {
        final String name = provisioned( "someone@example.com", null );
        Assertions.assertEquals( "Member 1", name );
        Assertions.assertNotEquals( "someone@example.com", name );
    }

    // was testProvisionDeduplicatesCollidingWikiName — two accounts may no longer share a name; the second is numbered
    @Test
    void testProvisionDeduplicatesCollidingDisplayNameAndWikiName() throws Exception {
        saveLocalAccount( "jakefear-local", "Jake Fear" );
        Assertions.assertEquals( "JakeFear", userDb().findByLoginName( "jakefear-local" ).getWikiName() );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "114291954688138286475" );
        profile.addAttribute( "email", "jakefear@simpleagility.com" );
        profile.addAttribute( "name", "Jake Fear" );
        service().provisionIfNeeded( "jakefear@simpleagility.com", "114291954688138286475", profile );

        final UserProfile created = userDb().findByLoginName( "jakefear@simpleagility.com" );
        Assertions.assertNotNull( created, "SSO profile must be persisted despite the name clash." );
        Assertions.assertEquals( "Jake Fear 2", created.getFullname() );
        Assertions.assertEquals( "jakefear@simpleagility.com", userDb().findByWikiName( "JakeFear2" ).getLoginName(),
                "the numbered display name derives a free wiki name" );
        Assertions.assertEquals( "jakefear-local", userDb().findByWikiName( "JakeFear" ).getLoginName() );
    }

    @Test
    void testDescribeChoiceExplainsEachBranch() {
        Assertions.assertEquals( "no name claim from the identity provider; placeholder",
                SSOAutoProvisionService.describeChoice( null, "Member 1" ) );
        Assertions.assertEquals( "no name claim from the identity provider; placeholder",
                SSOAutoProvisionService.describeChoice( "   ", "Member 1" ) );
        Assertions.assertEquals( "name claim used as-is",
                SSOAutoProvisionService.describeChoice( " Jane Doe ", "Jane Doe" ) );
        Assertions.assertEquals( "name claim was taken; numbered",
                SSOAutoProvisionService.describeChoice( "Jane Doe", "Jane Doe 2" ) );
        Assertions.assertEquals( "name claim unusable (reserved, email-shaped or malformed); placeholder",
                SSOAutoProvisionService.describeChoice( "Admin", "Member 1" ) );
    }
}
