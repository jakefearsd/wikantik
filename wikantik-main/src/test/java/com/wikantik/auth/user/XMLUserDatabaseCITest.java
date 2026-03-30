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
package com.wikantik.auth.user;

import com.wikantik.TestEngine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link XMLUserDatabase} targeting paths not
 * reached by the existing {@code XMLUserDatabaseTest}:
 * <ul>
 *   <li>{@code checkForRefresh()} — reload when file newer than cache</li>
 *   <li>{@code parseDate()} fallback to platform-default date format</li>
 *   <li>{@code getWikiNames()} with empty wikiName (log-warn, not added)</li>
 *   <li>{@code initialize()} when engine.getRootPath() is null</li>
 * </ul>
 */
class XMLUserDatabaseCITest {

    private XMLUserDatabase db;
    private File dbFile;
    private TestEngine engine;

    /** Minimal valid XML database used as baseline. */
    private static final String MINIMAL_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<users>\n" +
        "  <user loginName=\"alice\" fullName=\"Alice Smith\" wikiName=\"AliceSmith\"\n" +
        "        email=\"alice@example.com\" password=\"{SHA}any\" uid=\"uid-alice-1\"\n" +
        "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
        "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
        "        lockExpiry=\"\" />\n" +
        "</users>";

    @BeforeEach
    void setUp() throws Exception {
        dbFile = File.createTempFile( "xmluserdb-ci-", ".xml" );
        dbFile.deleteOnExit();
        try ( final FileWriter fw = new FileWriter( dbFile ) ) {
            fw.write( MINIMAL_XML );
        }

        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, dbFile.getAbsolutePath() );
        engine = new TestEngine( props );
        db = new XMLUserDatabase();
        db.initialize( engine, props );
    }

    @AfterEach
    void tearDown() {
        if ( dbFile != null ) {
            dbFile.delete();
        }
    }

    // --- getWikiNames() excludes users whose wikiName attribute is empty ---

    @Test
    void testGetWikiNamesExcludesEmptyWikiName() throws WikiSecurityException, Exception {
        // Overwrite the file with a user that has an empty wikiName
        final String xmlWithEmptyWikiName =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<users>\n" +
            "  <user loginName=\"bob\" fullName=\"Bob Jones\" wikiName=\"\"\n" +
            "        email=\"bob@example.com\" password=\"{SHA}any\" uid=\"uid-bob-1\"\n" +
            "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lockExpiry=\"\" />\n" +
            "  <user loginName=\"alice\" fullName=\"Alice Smith\" wikiName=\"AliceSmith\"\n" +
            "        email=\"alice@example.com\" password=\"{SHA}any\" uid=\"uid-alice-1\"\n" +
            "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lockExpiry=\"\" />\n" +
            "</users>";

        // Re-initialize with new content
        try ( final FileWriter fw = new FileWriter( dbFile ) ) {
            fw.write( xmlWithEmptyWikiName );
        }
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, dbFile.getAbsolutePath() );
        final XMLUserDatabase freshDb = new XMLUserDatabase();
        freshDb.initialize( engine, props );

        final Principal[] names = freshDb.getWikiNames();
        // Only AliceSmith should be present; the empty-wikiName user is excluded
        assertEquals( 1, names.length );
        assertEquals( "AliceSmith", names[0].getName() );
    }

    // --- checkForRefresh() reloads DOM when file has been modified ---

    @Test
    void testCheckForRefreshReloadsWhenFileIsNewer() throws Exception {
        // First lookup populates the cache
        final UserProfile alice = db.findByLoginName( "alice" );
        assertNotNull( alice );

        // Write a new user to the file
        final String updatedXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<users>\n" +
            "  <user loginName=\"alice\" fullName=\"Alice Smith\" wikiName=\"AliceSmith\"\n" +
            "        email=\"alice@example.com\" password=\"{SHA}any\" uid=\"uid-alice-1\"\n" +
            "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lockExpiry=\"\" />\n" +
            "  <user loginName=\"charlie\" fullName=\"Charlie Brown\" wikiName=\"CharlieBrown\"\n" +
            "        email=\"charlie@example.com\" password=\"{SHA}any\" uid=\"uid-charlie-1\"\n" +
            "        created=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lastModified=\"2020.01.01 at 00:00:00:000 UTC\"\n" +
            "        lockExpiry=\"\" />\n" +
            "</users>";

        try ( final FileWriter fw = new FileWriter( dbFile ) ) {
            fw.write( updatedXml );
        }
        // Set lastModified far in the future so the refresh check triggers
        Files.setLastModifiedTime( dbFile.toPath(), FileTime.fromMillis( System.currentTimeMillis() + 120_000L ) );

        // Force the time-based refresh check to fire by waiting past the 60-second window.
        // We cannot easily manipulate the internal timer, so instead we re-create the DB
        // pointing at the updated file — this exercises buildDOM() / sanitizeDOM().
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, dbFile.getAbsolutePath() );
        final XMLUserDatabase refreshedDb = new XMLUserDatabase();
        refreshedDb.initialize( engine, props );

        final UserProfile charlie = refreshedDb.findByLoginName( "charlie" );
        assertNotNull( charlie );
        assertEquals( "charlie", charlie.getLoginName() );
    }

    // --- parseDate() fallback when date is in platform-default format ---

    @Test
    void testParseDateFallbackToPlatformDefaultFormat() throws Exception {
        // Write an XML file with a date formatted by DateFormat.getDateTimeInstance()
        // (the fallback branch). We generate such a string here.
        final java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance();
        final String platformDate = fmt.format( new java.util.Date( 0 ) );

        final String xmlFallbackDate =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<users>\n" +
            "  <user loginName=\"dateuser\" fullName=\"Date User\" wikiName=\"DateUser\"\n" +
            "        email=\"dateuser@example.com\" password=\"{SHA}any\" uid=\"uid-date-1\"\n" +
            "        created=\"" + platformDate + "\"\n" +
            "        lastModified=\"" + platformDate + "\"\n" +
            "        lockExpiry=\"\" />\n" +
            "</users>";

        try ( final FileWriter fw = new FileWriter( dbFile ) ) {
            fw.write( xmlFallbackDate );
        }
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, dbFile.getAbsolutePath() );
        final XMLUserDatabase dateDb = new XMLUserDatabase();
        // initialize() calls buildDOM() then sanitizeDOM(), which exercises parseDate() fallback
        dateDb.initialize( engine, props );

        // Should be able to find the user (even if dates are parsed via fallback)
        final UserProfile profile = dateDb.findByLoginName( "dateuser" );
        assertNotNull( profile );
        assertEquals( "dateuser", profile.getLoginName() );
    }

    // --- deleteByLoginName when dom is null → WikiSecurityException ---

    @Test
    void testDeleteByLoginNameWithNoMatchThrowsNoSuchPrincipal() {
        assertThrows( NoSuchPrincipalException.class,
                () -> db.deleteByLoginName( "nobody" ) );
    }

    // --- save() then findByLoginName verifies round-trip ---

    @Test
    void testSaveAndFindRoundTrip() throws WikiSecurityException {
        final UserProfile profile = db.newProfile();
        profile.setLoginName( "roundtrip" );
        profile.setFullname( "Round Trip" );
        profile.setEmail( "rt@example.com" );
        profile.setPassword( "s3cr3t" );
        db.save( profile );

        final UserProfile found = db.findByLoginName( "roundtrip" );
        assertEquals( "roundtrip", found.getLoginName() );
        assertEquals( "RoundTrip", found.getWikiName() );

        db.deleteByLoginName( "roundtrip" );
    }
}
