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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.AuthoredPages;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class HistoryResourceTest {

    private static TestEngine engine;
    private static HistoryResource servlet;
    private final Gson gson = new Gson();

    // Per-class engine (see RestTestSupport javadoc): "RestHistoryPage" is seeded
    // once with identical content — no test mutates, renames, or deletes it, and no
    // test asserts an absolute version count (all checks are ">= 1").
    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "RestHistoryPage", "Version 1 content." );
        servlet = RestTestSupport.initServlet( HistoryResource::new, engine );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testGetVersionHistory() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestHistoryPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "versions" ) );
        assertTrue( obj.get( "versions" ).isJsonArray() );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        assertTrue( versions.size() >= 1, "Should have at least one version" );
    }

    @Test
    void testGetVersionHistoryFields() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        final JsonObject firstVersion = versions.get( 0 ).getAsJsonObject();

        assertTrue( firstVersion.has( "version" ) );
        assertTrue( firstVersion.has( "author" ) );
        assertTrue( firstVersion.has( "lastModified" ) );
        assertTrue( firstVersion.get( "version" ).getAsInt() >= 1 );
    }

    @Test
    void testGetHistoryPageNotFound() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryMissingName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryEmptyPathInfo() throws Exception {
        // PathInfo of "/" should return null from extractPathParam
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history/" );
        Mockito.doReturn( "/" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetHistoryVersionEntryStructure() throws Exception {
        final String json = doGet( "RestHistoryPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray versions = obj.getAsJsonArray( "versions" );
        assertTrue( versions.size() >= 1, "Should have at least one version" );

        final JsonObject firstVersion = versions.get( 0 ).getAsJsonObject();
        // The version entry should have these fields (changeNote may be null)
        assertTrue( firstVersion.has( "version" ) );
        assertTrue( firstVersion.has( "author" ) );
        assertTrue( firstVersion.has( "lastModified" ) );
        assertTrue( firstVersion.get( "version" ).getAsInt() >= 1 );
    }

    @Test
    void testVersionAuthorsAreDisplayNamesAndLegacyStringsPassThrough() throws Exception {
        AuthoredPages.save( engine, "RestHistoryAuthors", "First version.", "Legacy Person" );
        AuthoredPages.save( engine, "RestHistoryAuthors", "Second version.", "janne" );
        try {
            final JsonArray versions = gson.fromJson( doGet( "RestHistoryAuthors" ), JsonObject.class )
                    .getAsJsonArray( "versions" );
            final java.util.Set< String > authors = new java.util.HashSet<>();
            versions.forEach( v -> authors.add( v.getAsJsonObject().get( "author" ).getAsString() ) );

            assertEquals( java.util.Set.of( "Legacy Person", "Janne Jalkanen" ), authors );
        } finally {
            engine.deleteQuietly( "RestHistoryAuthors" );
        }
    }

    @Test
    void testAMachineWrittenVerificationNoteNamesTheVerifierByDisplayName() throws Exception {
        // mark_page_verified records "verified by <login>" (an email for an SSO administrator); readers of the history
        // get the verifier's display name, and a note an editor typed stays as written.
        AuthoredPages.save( engine, "RestHistoryVerified", "First version.", "Legacy Person", "verified by hand" );
        AuthoredPages.save( engine, "RestHistoryVerified", "Second version.", "janne", "verified by janne" );
        AuthoredPages.save( engine, "RestHistoryVerified", "Third version.", "janne", "verified by gone.user@example.test" );
        try {
            final JsonArray versions = gson.fromJson( doGet( "RestHistoryVerified" ), JsonObject.class )
                    .getAsJsonArray( "versions" );
            final java.util.Set< String > notes = new java.util.HashSet<>();
            versions.forEach( v -> notes.add( v.getAsJsonObject().get( "changeNote" ).getAsString() ) );

            assertEquals( java.util.Set.of( "verified by hand", "verified by Janne Jalkanen", "verified by unknown contributor" ),
                    notes );
        } finally {
            engine.deleteQuietly( "RestHistoryVerified" );
        }
    }

    // ----- MultiEdit Phase 3 Task 8: contributors -----

    private static final String CONTRIB_CID = "01HYCONTRIBUTORSHISTORY0001";

    private static String savePageWithCanonicalId( final String name, final String cid ) throws Exception {
        engine.saveText( name, "---\ncanonical_id: " + cid + "\n---\nVersion one body." );
        final String text = engine.getManager( com.wikantik.api.managers.PageManager.class ).getPureText( name, -1 );
        return com.wikantik.api.frontmatter.FrontmatterParser.parse( text ).metadata().get( "canonical_id" ).toString();
    }

    private JsonObject versionEntry( final String json, final int version ) {
        for ( final com.google.gson.JsonElement e : gson.fromJson( json, JsonObject.class ).getAsJsonArray( "versions" ) ) {
            if ( e.getAsJsonObject().get( "version" ).getAsInt() == version ) {
                return e.getAsJsonObject();
            }
        }
        return fail( "no version " + version + " in " + json );
    }

    private static java.util.List< String > names( final JsonObject entry ) {
        final java.util.List< String > out = new java.util.ArrayList<>();
        entry.getAsJsonArray( "contributors" ).forEach( e -> out.add( e.getAsString() ) );
        return out;
    }

    @Test
    void historyListsContributorsPerVersion() throws Exception {
        final String cid = savePageWithCanonicalId( "RestHistoryContrib", CONTRIB_CID );
        final var store = new com.wikantik.collab.testing.InMemoryCollabStore();
        final var wiring = com.wikantik.collab.adapter.CollabTestWiring.wire( engine, store );
        try {
            store.recordContributors( cid, 1, java.util.Set.of( "Zed Legacy", "Amy Legacy" ), true );
            final JsonObject v1 = versionEntry( doGet( "RestHistoryContrib" ), 1 );
            assertEquals( java.util.List.of( "Amy Legacy", "Zed Legacy" ), names( v1 ), "deduplicated and sorted" );
        } finally {
            wiring.close();
            engine.deleteQuietly( "RestHistoryContrib" );
        }
    }

    @Test
    void historyContributorsAreDisplayNamesNeverLogins() throws Exception {
        final String cid = savePageWithCanonicalId( "RestHistoryContribNames", "01HYCONTRIBUTORSHISTORY0002" );
        final var store = new com.wikantik.collab.testing.InMemoryCollabStore();
        final var wiring = com.wikantik.collab.adapter.CollabTestWiring.wire( engine, store );
        final var db = engine.getManager( com.wikantik.auth.UserManager.class ).getUserDatabase();
        try {
            db.recordDeletedLogin( "gone-login" );
            com.wikantik.api.identity.PublicIdentity identity =
                com.wikantik.auth.identity.PublicIdentities.forEngine( engine );
            identity.invalidate( null );
            store.recordContributors( cid, 1, java.util.Set.of( "janne", "gone-login", "stranger@example.test" ), true );

            final String json = doGet( "RestHistoryContribNames" );
            final java.util.List< String > shown = names( versionEntry( json, 1 ) );
            assertTrue( shown.contains( "Janne Jalkanen" ), shown.toString() );
            assertTrue( shown.contains( com.wikantik.api.identity.PublicIdentity.FORMER_MEMBER ), shown.toString() );
            assertTrue( shown.contains( com.wikantik.api.identity.PublicIdentity.UNKNOWN_CONTRIBUTOR ), shown.toString() );
            assertFalse( json.contains( "gone-login" ), "no raw key in the body" );
            assertFalse( json.contains( "stranger@example.test" ), "no raw key in the body" );
            assertFalse( json.contains( "\"janne\"" ), "no raw login in the body" );
        } finally {
            wiring.close();
            engine.deleteQuietly( "RestHistoryContribNames" );
        }
    }

    @Test
    void historyContributorsListTheVersionAuthorFirstInTheSameForm() throws Exception {
        AuthoredPages.save( engine, "RestHistoryContribAuthor",
            "---\ncanonical_id: 01HYCONTRIBUTORSHISTORY0003\n---\nbody", "janne" );
        final String text = engine.getManager( com.wikantik.api.managers.PageManager.class )
            .getPureText( "RestHistoryContribAuthor", -1 );
        final String cid = com.wikantik.api.frontmatter.FrontmatterParser.parse( text ).metadata().get( "canonical_id" ).toString();
        final var store = new com.wikantik.collab.testing.InMemoryCollabStore();
        final var wiring = com.wikantik.collab.adapter.CollabTestWiring.wire( engine, store );
        try {
            store.recordContributors( cid, 1, java.util.Set.of( "Aaron Legacy", "janne" ), true );
            final JsonObject v1 = versionEntry( doGet( "RestHistoryContribAuthor" ), 1 );
            assertEquals( "Janne Jalkanen", v1.get( "author" ).getAsString() );
            assertEquals( java.util.List.of( "Janne Jalkanen", "Aaron Legacy" ), names( v1 ),
                "author first, in the same display-name form, so the UI can filter by equality" );
        } finally {
            wiring.close();
            engine.deleteQuietly( "RestHistoryContribAuthor" );
        }
    }

    @Test
    void historyWithoutCollabWiringHasEmptyContributors() throws Exception {
        final JsonObject v1 = versionEntry( doGet( "RestHistoryPage" ), 1 );
        assertTrue( v1.has( "contributors" ), "always present" );
        assertTrue( names( v1 ).isEmpty() );
    }

    @Test
    void aRenamedPageKeepsItsContributors() throws Exception {
        final String cid = savePageWithCanonicalId( "RestHistoryRenameFrom", "01HYCONTRIBUTORSHISTORY0004" );
        final var store = new com.wikantik.collab.testing.InMemoryCollabStore();
        final var wiring = com.wikantik.collab.adapter.CollabTestWiring.wire( engine, store );
        try {
            store.recordContributors( cid, 1, java.util.Set.of( "Amy Legacy" ), true );
            final com.wikantik.api.core.Context ctx = com.wikantik.api.spi.Wiki.context().create( engine,
                engine.getManager( com.wikantik.api.managers.PageManager.class ).getPage( "RestHistoryRenameFrom" ) );
            engine.getManager( com.wikantik.content.PageRenamer.class )
                .renamePage( ctx, "RestHistoryRenameFrom", "RestHistoryRenameTo", true );
            assertEquals( java.util.List.of( "Amy Legacy" ), names( versionEntry( doGet( "RestHistoryRenameTo" ), 1 ) ) );
        } finally {
            wiring.close();
            engine.deleteQuietly( "RestHistoryRenameFrom", "RestHistoryRenameTo" );
        }
    }

    // ----- Helper methods -----

    private String doGet( final String pageName ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/history/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
