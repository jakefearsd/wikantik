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
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.TestJNDIContext;
import com.wikantik.WikiSession;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.user.JDBCUserDatabase;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two accounts may share a full name (ruling R24); their wiki names stay unique. A numbered wiki
 * name ({@code JohnSmith2}) must survive a reload and a re-save, and the session of its holder must
 * never carry the other account's wiki name.
 */
class SharedWikiNameJdbcTest extends AbstractJdbcUsersEngineTest {

    // ---- C-1: numbered wiki names survive reload and re-save -------------------------------------

    @Test
    void numberedWikiNameSurvivesReloadAndResave() throws Exception {
        saveUser( PREFIX + "jsmith", "John Smith" );
        saveUser( PREFIX + "jsmith2", "John Smith" );

        final UserProfile reloaded = db.findByLoginName( PREFIX + "jsmith2" );
        assertEquals( "John Smith", reloaded.getFullname() );
        assertEquals( "JohnSmith2", reloaded.getWikiName(), "the stored wiki name is read back, not re-derived" );
        assertEquals( "JohnSmith2", db.findByLoginName( PREFIX + "jsmith2" ).getWikiName(), "cached copy too" );

        reloaded.setEmail( "changed@example.test" );
        assertDoesNotThrow( () -> db.save( reloaded ), "re-saving must not collide on users_wiki_name_uniq" );

        final JDBCUserDatabase fresh = new JDBCUserDatabase();
        fresh.initialize( engine, new Properties() );
        assertEquals( PREFIX + "jsmith2", fresh.findByWikiName( "JohnSmith2" ).getLoginName() );
        assertEquals( PREFIX + "jsmith", fresh.findByWikiName( "JohnSmith" ).getLoginName() );
        assertEquals( "changed@example.test", fresh.findByLoginName( PREFIX + "jsmith2" ).getEmail() );
    }

    @Test
    void sessionOfTheSecondHolderNeverCarriesTheFirstHoldersWikiName() throws Exception {
        saveUser( PREFIX + "jsmith", "John Smith" );
        saveUser( PREFIX + "jsmith2", "John Smith" );

        final Principal[] principals = login( PREFIX + "jsmith2" ).getPrincipals();
        assertFalse( Arrays.stream( principals ).anyMatch( p -> p.getName().equals( "JohnSmith" ) ),
                "the other account's wiki name leaked into the session: " + Arrays.toString( principals ) );
        assertTrue( Arrays.stream( principals ).anyMatch( p -> p.getName().equals( "JohnSmith2" ) ),
                Arrays.toString( principals ) );
    }
}
