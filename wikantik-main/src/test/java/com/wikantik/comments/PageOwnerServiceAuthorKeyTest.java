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
package com.wikantik.comments;

import com.wikantik.AuthoredPages;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the cross-task contract between authorship and ownership: {@code WikiEngine}'s {@code pageAuthorLookup}
 * hands {@code PageOwnerService} the page's <em>version author</em>, and the service accepts it only when it is an
 * existing login. Since authorship stores the login, a page saved by an account bootstraps to that account;
 * a legacy full-name author still does not (a name never confers ownership).
 */
@RequiresPostgres
class PageOwnerServiceAuthorKeyTest {

    private static final Logger LOG = LogManager.getLogger( PageOwnerServiceAuthorKeyTest.class );

    private static TestEngine engine;
    private DataSource ds;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "page_owners" );
    }

    /** Mirrors {@code WikiEngine.initKnowledgeGraph}'s userExistsLookup. */
    private static Predicate< String > accountExists() {
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        return login -> {
            try {
                db.findByLoginName( login );
                return true;
            } catch ( final NoSuchPrincipalException e ) {
                LOG.debug( "No account '{}' in the test user database: {}", login, e.getMessage() );
                return false;
            }
        };
    }

    /** Mirrors {@code WikiEngine.initKnowledgeGraph}'s pageAuthorLookup (here the "canonical id" is the page name). */
    private static Function< String, Optional< String > > versionAuthorOf() {
        final PageManager pm = engine.getManager( PageManager.class );
        return pageName -> Optional.ofNullable( pm.getPage( pageName ) )
                .map( Page::getAuthor )
                .filter( author -> !author.isBlank() );
    }

    @Test
    void aPageSavedByAnAuthenticatedAccountBootstrapsToThatAccountsLogin() throws Exception {
        engine.saveText( "OwnerAuthorKeyPage", "Saved by the admin account." );   // author = session's login principal
        final PageOwnerService svc = new PageOwnerService( ds, accountExists(), versionAuthorOf() );

        svc.getOwner( "OwnerAuthorKeyPage" );

        final var row = svc.findRaw( "OwnerAuthorKeyPage" ).orElseThrow();
        assertEquals( "admin", row.ownerLogin(), "the stored author is the login, so the owner resolves" );
        assertEquals( PageOwnerService.BOOTSTRAP_ASSIGNER, row.assignedBy() );
    }

    @Test
    void aLegacyFullNameAuthorIsNotMappedToAnAccount() throws Exception {
        AuthoredPages.save( engine, "OwnerLegacyNamePage", "Saved under a full name.", "Administrator" );
        final PageOwnerService svc = new PageOwnerService( ds, accountExists(), versionAuthorOf() );

        svc.getOwner( "OwnerLegacyNamePage" );

        assertNull( svc.findRaw( "OwnerLegacyNamePage" ).orElseThrow().ownerLogin(),
            "a display name must never confer ownership; the page stays orphaned until assigned" );
    }
}
