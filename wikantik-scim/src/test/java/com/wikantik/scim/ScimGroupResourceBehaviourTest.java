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
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Success-path behaviour of {@link ScimGroupResource}: what reaches the group store, the response documents,
 * the paging and the audit trail. Complements {@link ScimGroupResourceTest} (error branches, Admin guard).
 */
class ScimGroupResourceBehaviourTest {

    private ScimGroupResource resource;
    private GroupManager gm;
    private UserDatabase db;
    private AuditService audit;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter out;
    private MockedStatic<WikiSession> sessions;
    private Session sysSession;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ScimGroupResource();
        final WikiEngine engine = mock( WikiEngine.class );
        gm = mock( GroupManager.class );
        db = mock( UserDatabase.class );
        audit = mock( AuditService.class );
        final UserManager um = mock( UserManager.class );
        when( engine.getManager( GroupManager.class ) ).thenReturn( gm );
        when( engine.getManager( UserManager.class ) ).thenReturn( um );
        when( um.getUserDatabase() ).thenReturn( db );
        when( engine.getAuditService() ).thenReturn( audit );
        final Field f = ScimGroupResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, engine );

        sysSession = mock( Session.class );
        sessions = mockStatic( WikiSession.class );
        sessions.when( () -> WikiSession.getWikiSession( any(), any() ) ).thenReturn( sysSession );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out ) );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost/scim/v2/Groups" ) );
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private JsonObject responseJson() {
        return JsonParser.parseString( out.toString() ).getAsJsonObject();
    }

    private void body( final String json ) throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( json ) ) );
    }

    private UserProfile user( final String uid, final String login ) throws Exception {
        final UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( uid );
        when( p.getLoginName() ).thenReturn( login );
        when( db.findByUid( uid ) ).thenReturn( p );
        when( db.findByLoginName( login ) ).thenReturn( p );
        return p;
    }

    private static Principal principal( final String name ) {
        final Principal p = mock( Principal.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private Group group( final String name, final String... memberLogins ) {
        final Group g = mock( Group.class );
        when( g.getName() ).thenReturn( name );
        final Principal[] members = new Principal[ memberLogins.length ];
        for ( int i = 0; i < members.length; i++ ) members[ i ] = principal( memberLogins[ i ] );
        when( g.members() ).thenReturn( members );
        return g;
    }

    private AuditEntry auditedEntry() throws Exception {
        final ArgumentCaptor<AuditEntry> c = ArgumentCaptor.forClass( AuditEntry.class );
        verify( audit ).record( c.capture() );
        return c.getValue();
    }

    // ------------------------------------------------------------------ create

    @Test
    void createSavesTheGroupWithTheResolvedLoginsAndAnswers201WithLocation() throws Exception {
        user( "uid-1", "carol" );
        user( "uid-2", "dan" );
        final Group saved = group( "Devs", "carol", "dan" );
        final Group parsed = group( "Devs" );
        when( gm.getGroup( "Devs" ) ).thenThrow( new NoSuchPrincipalException( "Devs" ) ).thenReturn( saved );
        when( gm.parseGroup( eq( "Devs" ), anyString(), eq( true ) ) ).thenReturn( parsed );
        body( "{\"displayName\":\"Devs\",\"members\":[{\"value\":\"uid-1\"},{\"value\":\"uid-2\"}]}" );

        resource.doPost( req, resp );

        verify( gm ).parseGroup( "Devs", "carol\ndan", true );
        verify( gm ).setGroup( sysSession, parsed );
        verify( resp ).setStatus( 201 );
        verify( resp ).setHeader( "Location", "http://localhost/scim/v2/Groups/Devs" );
        verify( resp ).setContentType( "application/scim+json" );
        final JsonObject o = responseJson();
        assertEquals( "Devs", o.get( "displayName" ).getAsString() );
        assertEquals( 2, o.getAsJsonArray( "members" ).size() );
        final JsonObject first = o.getAsJsonArray( "members" ).get( 0 ).getAsJsonObject();
        assertEquals( "uid-1", first.get( "value" ).getAsString() );
        assertEquals( "http://localhost/scim/v2/Users/uid-1", first.get( "$ref" ).getAsString() );
        assertEquals( "http://localhost/scim/v2/Groups/Devs", o.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
    }

    @Test
    void createIsAuditedAsASuccessfulGroupCreate() throws Exception {
        final Group g = group( "Ops" );
        when( gm.getGroup( "Ops" ) ).thenThrow( new NoSuchPrincipalException( "Ops" ) ).thenReturn( g );
        when( gm.parseGroup( eq( "Ops" ), anyString(), anyBoolean() ) ).thenReturn( g );
        body( "{\"displayName\":\"Ops\"}" );
        resource.doPost( req, resp );
        final AuditEntry e = auditedEntry();
        assertEquals( "Ops", e.targetId() );
        assertEquals( "group", e.targetType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
    }

    @Test
    void createVanishingAfterSaveIs404() throws Exception {
        final Group g = group( "Gone" );
        when( gm.getGroup( "Gone" ) ).thenThrow( new NoSuchPrincipalException( "Gone" ) );
        when( gm.parseGroup( eq( "Gone" ), anyString(), anyBoolean() ) ).thenReturn( g );
        body( "{\"displayName\":\"Gone\"}" );
        resource.doPost( req, resp );
        verify( resp ).setStatus( 404 );
        assertTrue( responseJson().get( "detail" ).getAsString().contains( "Gone" ) );
    }

    @Test
    void createFailingInTheGroupStoreIs500AndNamesTheProblem() throws Exception {
        when( gm.getGroup( "Bad" ) ).thenThrow( new NoSuchPrincipalException( "Bad" ) );
        when( gm.parseGroup( eq( "Bad" ), anyString(), anyBoolean() ) )
                .thenThrow( new com.wikantik.auth.WikiSecurityException( "store down" ) );
        body( "{\"displayName\":\"Bad\"}" );
        resource.doPost( req, resp );
        verify( resp ).setStatus( 500 );
        assertTrue( responseJson().get( "detail" ).getAsString().startsWith( "Failed to create group" ) );
    }

    @Test
    void createRefusesBlankDisplayNamesWithoutTouchingTheStore() throws Exception {
        for ( final String json : new String[]{ "{\"displayName\":\"\"}", "{\"displayName\":\"  \"}", "{\"displayName\":null}" } ) {
            final HttpServletResponse r = mock( HttpServletResponse.class );
            final StringWriter w = new StringWriter();
            when( r.getWriter() ).thenReturn( new PrintWriter( w ) );
            body( json );
            resource.doPost( req, r );
            verify( r ).setStatus( 400 );
            assertEquals( "invalidValue", JsonParser.parseString( w.toString() ).getAsJsonObject().get( "scimType" ).getAsString() );
        }
        verify( gm, never() ).setGroup( any(), any() );
        verify( audit, never() ).record( any() );
    }

    // ------------------------------------------------------------------ list / get

    @Test
    void getByNameReturnsMembersResolvedToUidsAndDropsUnresolvableOnes() throws Exception {
        user( "uid-1", "carol" );
        when( db.findByLoginName( "ghost" ) ).thenThrow( new NoSuchPrincipalException( "ghost" ) );
        final Group hoisted1 = group( "Devs", "carol", "ghost" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted1 );
        when( req.getPathInfo() ).thenReturn( "/Devs" );
        resource.doGet( req, resp );
        final var members = responseJson().getAsJsonArray( "members" );
        assertEquals( 1, members.size() );
        assertEquals( "uid-1", members.get( 0 ).getAsJsonObject().get( "value" ).getAsString() );
    }

    @Test
    void listReturnsEachGroupDocumentAndPagesThem() throws Exception {
        final Principal[] roles = new Principal[]{ principal( "A" ), principal( "B" ), principal( "C" ) };
        when( gm.getRoles() ).thenReturn( roles );
        final Group hoisted2 = group( "A" );
        when( gm.getGroup( "A" ) ).thenReturn( hoisted2 );
        final Group hoisted3 = group( "B" );
        when( gm.getGroup( "B" ) ).thenReturn( hoisted3 );
        final Group hoisted4 = group( "C" );
        when( gm.getGroup( "C" ) ).thenReturn( hoisted4 );
        when( req.getParameter( "startIndex" ) ).thenReturn( "2" );
        when( req.getParameter( "count" ) ).thenReturn( "1" );
        resource.doGet( req, resp );
        final JsonObject o = responseJson();
        assertEquals( 3, o.get( "totalResults" ).getAsInt() );
        assertEquals( 2, o.get( "startIndex" ).getAsInt() );
        assertEquals( 1, o.getAsJsonArray( "Resources" ).size() );
        assertEquals( "B", o.getAsJsonArray( "Resources" ).get( 0 ).getAsJsonObject().get( "displayName" ).getAsString() );
    }

    @Test
    void listFilteredByDisplayNameBuildsTheDocumentForThatGroup() throws Exception {
        final Group hoisted5 = group( "QA" );
        when( gm.getGroup( "QA" ) ).thenReturn( hoisted5 );
        when( req.getParameter( "filter" ) ).thenReturn( "displayName eq \"QA\"" );
        resource.doGet( req, resp );
        final JsonObject o = responseJson();
        assertEquals( 1, o.get( "totalResults" ).getAsInt() );
        assertEquals( "QA", o.getAsJsonArray( "Resources" ).get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void listFilteredOnAnotherAttributeIs400InvalidFilterNamingTheAttribute() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "externalId eq \"x\"" );
        resource.doGet( req, resp );
        verify( resp ).setStatus( 400 );
        final JsonObject o = responseJson();
        assertEquals( "invalidFilter", o.get( "scimType" ).getAsString() );
        assertTrue( o.get( "detail" ).getAsString().contains( "externalId" ) );
    }

    @Test
    void listFilterIsCaseInsensitiveAsRfc7644Requires() throws Exception {
        final Group hoisted6 = group( "QA" );
        when( gm.getGroup( "QA" ) ).thenReturn( hoisted6 );
        when( req.getParameter( "filter" ) ).thenReturn( "DisplayName EQ \"QA\"" );
        resource.doGet( req, resp );
        verify( resp, never() ).setStatus( 400 );
        assertEquals( 1, responseJson().get( "totalResults" ).getAsInt() );
    }

    // ------------------------------------------------------------------ replace

    @Test
    void replaceRewritesTheMembershipFromTheBodyAndAudits() throws Exception {
        user( "uid-3", "erin" );
        final Group parsed = group( "Devs" );
        final Group saved = group( "Devs", "erin" );
        when( gm.getGroup( "Devs" ) ).thenReturn( saved );
        when( gm.parseGroup( eq( "Devs" ), anyString(), eq( true ) ) ).thenReturn( parsed );
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/Devs" );
        body( "{\"displayName\":\"Devs\",\"members\":[{\"value\":\"uid-3\"}]}" );

        resource.service( req, resp );

        verify( gm ).parseGroup( "Devs", "erin", true );
        verify( gm ).setGroup( sysSession, parsed );
        assertEquals( "uid-3", responseJson().getAsJsonArray( "members" ).get( 0 ).getAsJsonObject().get( "value" ).getAsString() );
        final AuditEntry e = auditedEntry();
        assertEquals( "Devs", e.targetId() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
    }

    @Test
    void replaceWithMalformedJsonIsRefusedBeforeAnythingIsWritten() throws Exception {
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/Devs" );
        body( "[1,2" );
        resource.service( req, resp );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidSyntax", responseJson().get( "scimType" ).getAsString() );
        verify( gm, never() ).setGroup( any(), any() );
        verify( audit, never() ).record( any() );
    }

    @Test
    void replaceOfAGroupThatIsGoneAfterTheWriteIs404() throws Exception {
        final Group hoisted7 = group( "Gone" );
        when( gm.parseGroup( eq( "Gone" ), anyString(), anyBoolean() ) ).thenReturn( hoisted7 );
        when( gm.getGroup( "Gone" ) ).thenThrow( new NoSuchPrincipalException( "Gone" ) );
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/Gone" );
        body( "{\"members\":[]}" );
        resource.service( req, resp );
        verify( resp ).setStatus( 404 );
    }

    @Test
    void replaceFailingInTheStoreIs500() throws Exception {
        when( gm.parseGroup( eq( "Bad" ), anyString(), anyBoolean() ) )
                .thenThrow( new com.wikantik.auth.WikiSecurityException( "disk" ) );
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/Bad" );
        body( "{\"members\":[]}" );
        resource.service( req, resp );
        verify( resp ).setStatus( 500 );
        assertTrue( responseJson().get( "detail" ).getAsString().startsWith( "Failed to update group" ) );
    }

    // ------------------------------------------------------------------ patch

    private void patch( final String name, final String json ) throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/" + name );
        body( json );
        resource.service( req, resp );
    }

    @Test
    void patchAddKeepsTheCurrentMembersAndAddsTheNewOne() throws Exception {
        user( "uid-1", "carol" );
        user( "uid-2", "dan" );
        final Group current = group( "Devs", "carol" );
        final Group parsed = group( "Devs" );
        when( gm.getGroup( "Devs" ) ).thenReturn( current );
        when( gm.parseGroup( eq( "Devs" ), anyString(), eq( true ) ) ).thenReturn( parsed );

        patch( "Devs", "{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"uid-2\"}]}]}" );

        verify( gm ).parseGroup( "Devs", "carol\ndan", true );
        verify( gm ).setGroup( sysSession, parsed );
        final AuditEntry e = auditedEntry();
        assertEquals( "Devs", e.targetId() );
    }

    @Test
    void patchRemoveOfOneMemberLeavesTheOthers() throws Exception {
        user( "uid-1", "carol" );
        user( "uid-2", "dan" );
        final Group hoisted8 = group( "Devs", "carol", "dan" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted8 );
        final Group hoisted9 = group( "Devs" );
        when( gm.parseGroup( eq( "Devs" ), anyString(), anyBoolean() ) ).thenReturn( hoisted9 );

        patch( "Devs", "{\"Operations\":[{\"op\":\"remove\",\"path\":\"members[value eq \\\"uid-1\\\"]\"}]}" );

        verify( gm ).parseGroup( "Devs", "dan", true );
    }

    @Test
    void patchDoesNotCarryOverMembersWhoseLoginNoLongerResolves() throws Exception {
        user( "uid-2", "dan" );
        when( db.findByLoginName( "ghost" ) ).thenThrow( new NoSuchPrincipalException( "ghost" ) );
        final Group hoisted10 = group( "Devs", "ghost", "dan" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted10 );
        final Group hoisted11 = group( "Devs" );
        when( gm.parseGroup( eq( "Devs" ), anyString(), anyBoolean() ) ).thenReturn( hoisted11 );

        patch( "Devs", "{\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[]}]}" );

        verify( gm ).parseGroup( "Devs", "dan", true );
    }

    @Test
    void patchWithMalformedJsonIs400InvalidSyntaxAndWritesNothing() throws Exception {
        final Group hoisted12 = group( "Devs" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted12 );
        patch( "Devs", "nope" );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidSyntax", responseJson().get( "scimType" ).getAsString() );
        verify( gm, never() ).setGroup( any(), any() );
    }

    @Test
    void patchOfAGroupThatDisappearsAfterTheWriteIs404() throws Exception {
        final Group current = group( "Devs" );
        when( gm.getGroup( "Devs" ) ).thenReturn( current ).thenThrow( new NoSuchPrincipalException( "Devs" ) );
        when( gm.parseGroup( eq( "Devs" ), anyString(), anyBoolean() ) ).thenReturn( current );
        patch( "Devs", "{\"Operations\":[]}" );
        verify( resp ).setStatus( 404 );
    }

    @Test
    void patchFailingInTheStoreIs500() throws Exception {
        final Group hoisted13 = group( "Devs" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted13 );
        when( gm.parseGroup( eq( "Devs" ), anyString(), anyBoolean() ) )
                .thenThrow( new com.wikantik.auth.WikiSecurityException( "disk" ) );
        patch( "Devs", "{\"Operations\":[]}" );
        verify( resp ).setStatus( 500 );
        assertTrue( responseJson().get( "detail" ).getAsString().startsWith( "Failed to patch group" ) );
    }

    // ------------------------------------------------------------------ URL derivation

    @Test
    void baseUrlsAreDerivedFromTheRequestUrlWhateverFollowsGroups() throws Exception {
        final Group hoisted14 = group( "Devs", "carol" );
        when( gm.getGroup( "Devs" ) ).thenReturn( hoisted14 );
        user( "uid-1", "carol" );
        when( req.getPathInfo() ).thenReturn( "/Devs" );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "https://h.example/scim/v2/Groups/Devs" ) );
        resource.doGet( req, resp );
        final JsonObject o = responseJson();
        assertEquals( "https://h.example/scim/v2/Groups/Devs", o.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
        assertEquals( "https://h.example/scim/v2/Users/uid-1",
                o.getAsJsonArray( "members" ).get( 0 ).getAsJsonObject().get( "$ref" ).getAsString() );
    }

    @Test
    void replaceOfAnUnknownGroupIs404AndDoesNotCreateIt() throws Exception {
        when( gm.getGroup( "Nope" ) ).thenThrow( new NoSuchPrincipalException( "Nope" ) );
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/Nope" );
        body( "{\"displayName\":\"Nope\",\"members\":[]}" );
        resource.service( req, resp );
        verify( resp ).setStatus( 404 );
        verify( gm, never() ).parseGroup( anyString(), anyString(), anyBoolean() );
        verify( gm, never() ).setGroup( any(), any() );
        verify( audit, never() ).record( any() );
    }
}
