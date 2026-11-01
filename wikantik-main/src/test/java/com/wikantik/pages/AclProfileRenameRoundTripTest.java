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
package com.wikantik.pages;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Acl;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A profile rename rewrites the access rules of every page that names the user — and only those: the frontmatter
 * (canonical_id, cluster, …) and the body come back exactly as they were. The rewrite bypasses the save filters, so a
 * rule written above the frontmatter would silently turn it into body text.
 */
class AclProfileRenameRoundTripTest {

    private static final String PAGE = "AclRenameRoundTrip";

    private final TestEngine engine = TestEngine.build();

    @AfterEach
    void stop() {
        engine.deleteQuietly( PAGE );
        engine.stop();
    }

    private String stored() {
        return engine.getManager( PageManager.class ).getPureText( PAGE, PageProvider.LATEST_VERSION );
    }

    @Test
    void aRenameRewritesTheRulesAndKeepsTheFrontmatterAndBody() throws Exception {
        engine.saveText( PAGE, "---\ncanonical_id: 01JC0RENAMER0VNDTR1P000000\ntype: article\ncluster: finance\nsummary: A page whose edit rule names janne\n---\n"
                + "[{ALLOW view All}]\n[{ALLOW edit janne}]\n\nBody text stays.\n\nWrite `[{ALLOW edit janne}]` to restrict.\n" );
        final ParsedPage before = FrontmatterParser.parse( stored() );
        assertEquals( "01JC0RENAMER0VNDTR1P000000", before.metadata().get( "canonical_id" ), "premise: the page has a canonical_id" );

        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile oldProfile = db.newProfile();
        oldProfile.setLoginName( "janne" );
        oldProfile.setFullname( "Janne Jalkanen" );
        oldProfile.setWikiName( "JanneJalkanen" );
        final UserProfile newProfile = db.newProfile();
        newProfile.setLoginName( "janne" );
        newProfile.setFullname( "Janne Renamed" );
        newProfile.setWikiName( "JanneRenamed" );
        ( ( WikiEventListener ) engine.getManager( PageManager.class ) ).actionPerformed( new WikiSecurityEvent(
                engine.getManager( UserManager.class ), WikiSecurityEvent.PROFILE_NAME_CHANGED, new UserProfile[]{ oldProfile, newProfile } ) );

        final String after = stored();
        final ParsedPage parsed = FrontmatterParser.parse( after );
        assertEquals( before.metadata().get( "canonical_id" ), parsed.metadata().get( "canonical_id" ), after );
        assertEquals( "finance", parsed.metadata().get( "cluster" ), after );
        assertEquals( before.metadata(), parsed.metadata(), "the frontmatter is untouched: " + after );
        assertTrue( parsed.body().startsWith( "[{ALLOW edit Janne Renamed}]\n[{ALLOW view All}]\n" ), after );
        assertTrue( parsed.body().contains( "Body text stays." ), after );
        assertTrue( parsed.body().contains( "Write `[{ALLOW edit janne}]` to restrict." ), "a documentation example is kept: " + after );

        final Acl acl = engine.getManager( AclManager.class ).getPermissions(
                engine.getManager( PageManager.class ).getPage( PAGE ) );
        assertNotNull( acl.getAclEntry( new WikiPrincipal( "Janne Renamed" ) ), acl.toString() );
        assertNull( acl.getAclEntry( new WikiPrincipal( "janne" ) ), acl.toString() );
    }
}
