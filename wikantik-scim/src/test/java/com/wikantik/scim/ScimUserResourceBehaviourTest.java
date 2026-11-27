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
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Behaviour of {@link ScimUserResource}'s success paths: what is written to the store, the lifecycle
 * transitions, the audit trail and the response documents. Complements {@link ScimUserResourceTest},
 * which concentrates on error branches.
 */
class ScimUserResourceBehaviourTest {

    private ScimUserResource resource;
    private WikiEngine engine;
    private UserDatabase db;
    private AuditService audit;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter out;
    private UserProfile savedProfile;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ScimUserResource();
        engine = mock( WikiEngine.class );
        final UserManager um = mock( UserManager.class );
        db = mock( UserDatabase.class );
        audit = mock( AuditService.class );
        when( engine.getManager( UserManager.class ) ).thenReturn( um );
        when( um.getUserDatabase() ).thenReturn( db );
        when( engine.getAuditService() ).thenReturn( audit );
        final Field f = ScimUserResource.class.getDeclaredField( "engine" );
        f.setAccessible( true );
        f.set( resource, engine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        out = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( out ) );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost/scim/v2/Users" ) );
    }

    private void body( final String json ) throws Exception {
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( json ) ) );
    }

    private JsonObject responseJson() {
        return JsonParser.parseString( out.toString() ).getAsJsonObject();
    }

    private UserProfile existing( final String uid, final String login, final boolean locked ) throws Exception {
        final UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( uid );
        when( p.getLoginName() ).thenReturn( login );
        when( p.isLocked() ).thenReturn( locked );
        when( p.getAttributes() ).thenReturn( new HashMap<String, Serializable>() );
        when( db.findByUid( uid ) ).thenReturn( p );
        when( db.findByLoginName( login ) ).thenReturn( p );
        return p;
    }

    /** Arranges a create of {@code login}: nothing exists, then the saved profile is found. */
    private UserProfile arrangeCreate( final String login ) throws Exception {
        final UserProfile fresh = mock( UserProfile.class );
        when( fresh.getAttributes() ).thenReturn( new HashMap<String, Serializable>() );
        when( db.newProfile() ).thenReturn( fresh );
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-" + login );
        when( saved.getLoginName() ).thenReturn( login );
        when( saved.getAttributes() ).thenReturn( new HashMap<String, Serializable>() );
        when( db.findByLoginName( login ) )
                .thenThrow( new NoSuchPrincipalException( login ) ).thenReturn( saved );
        savedProfile = saved;
        return fresh;
    }

    private AuditEntry auditedEntry() throws Exception {
        final ArgumentCaptor<AuditEntry> c = ArgumentCaptor.forClass( AuditEntry.class );
        verify( audit ).record( c.capture() );
        return c.getValue();
    }

    // ------------------------------------------------------------------ create

    @ParameterizedTest
    @ValueSource( strings = { "{}", "{\"userName\":null}", "{\"userName\":\"\"}", "{\"userName\":\"   \"}" } )
    void createWithoutAUsableUserNameIs400InvalidValueAndTouchesNothing( final String json ) throws Exception {
        body( json );
        resource.doPost( req, resp );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidValue", responseJson().get( "scimType" ).getAsString() );
        verify( db, never() ).save( any() );
        verify( audit, never() ).record( any() );
    }

    @Test
    void createRespondsWithLocationAndTheNewUserDocument() throws Exception {
        arrangeCreate( "dave" );
        body( "{\"userName\":\"dave\",\"displayName\":\"Dave User\",\"name\":{\"formatted\":\"Dave D\"}}" );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        verify( resp ).setHeader( "Location", "http://localhost/scim/v2/Users/uid-dave" );
        verify( resp ).setContentType( "application/scim+json" );
        final JsonObject o = responseJson();
        assertEquals( "uid-dave", o.get( "id" ).getAsString() );
        assertEquals( "dave", o.get( "userName" ).getAsString() );
        assertEquals( "http://localhost/scim/v2/Users/uid-dave", o.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
    }

    @Test
    void createWritesTheSuppliedAttributesAndLinksTheSsoSubject() throws Exception {
        final UserProfile fresh = arrangeCreate( "erin" );
        final Map<String, Serializable> attrs = new HashMap<>();
        when( fresh.getAttributes() ).thenReturn( attrs );
        body( "{\"userName\":\"erin\",\"externalId\":\"ext-erin\",\"password\":\"s3cret-Pass\","
                + "\"name\":{\"formatted\":\"Erin E\"},\"emails\":[{\"value\":\"e@x.com\",\"primary\":true}]}" );

        resource.doPost( req, resp );

        verify( fresh ).setLoginName( "erin" );
        verify( fresh ).setEmail( "e@x.com" );
        verify( fresh ).setFullname( "Erin E" );
        verify( fresh ).setPassword( "s3cret-Pass" );
        assertEquals( "ext-erin", attrs.get( "sso.subject" ) );
        verify( db ).save( fresh );
    }

    @Test
    void createWithoutAPasswordGeneratesAnUnguessableOneRatherThanAnEmptyOne() throws Exception {
        final UserProfile fresh = arrangeCreate( "fred" );
        body( "{\"userName\":\"fred\"}" );
        resource.doPost( req, resp );
        final ArgumentCaptor<String> pw = ArgumentCaptor.forClass( String.class );
        verify( fresh ).setPassword( pw.capture() );
        assertTrue( pw.getValue().length() >= 32, "generated password must be a UUID, not blank: " + pw.getValue() );
    }

    @Test
    void createWithABlankPasswordIsTreatedAsNoPassword() throws Exception {
        final UserProfile fresh = arrangeCreate( "gina" );
        body( "{\"userName\":\"gina\",\"password\":\"   \"}" );
        resource.doPost( req, resp );
        final ArgumentCaptor<String> pw = ArgumentCaptor.forClass( String.class );
        verify( fresh ).setPassword( pw.capture() );
        assertFalse( pw.getValue().isBlank() );
    }

    @Test
    void createWithActiveFalseLocksTheNewAccountIndefinitely() throws Exception {
        arrangeCreate( "hank" );
        body( "{\"userName\":\"hank\",\"active\":false}" );
        final UserProfile stored = savedProfile;

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        final ArgumentCaptor<Date> lock = ArgumentCaptor.forClass( Date.class );
        verify( stored ).setLockExpiry( lock.capture() );
        assertNotNull( lock.getValue() );
        assertTrue( lock.getValue().after( new Date( System.currentTimeMillis() + 3650L * 86_400_000L ) ),
                "an inactive account is locked indefinitely, not for a moment" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{\"userName\":\"ivy\",\"active\":true}", "{\"userName\":\"ivy\"}" } )
    void createOfAnActiveAccountDoesNotLockIt( final String json ) throws Exception {
        arrangeCreate( "ivy" );
        final UserProfile fresh = db.newProfile();
        body( json );
        resource.doPost( req, resp );
        verify( resp ).setStatus( 201 );
        verify( fresh, never() ).setLockExpiry( any() );
        verify( db, times( 1 ) ).save( any() );
    }

    @Test
    void createIsAuditedAsTheScimActorWithTheLogin() throws Exception {
        arrangeCreate( "jo" );
        body( "{\"userName\":\"jo\"}" );
        resource.doPost( req, resp );
        final AuditEntry e = auditedEntry();
        assertEquals( "scim.user.create", e.eventType() );
        assertEquals( "jo", e.targetId() );
        assertEquals( "user", e.targetType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
    }

    // ------------------------------------------------------------------ list / filters

    @Test
    void listFilteredOnDisplayNameIsRefusedForUsersWithInvalidFilter() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "displayName eq \"Al\"" );
        resource.doGet( req, resp );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidFilter", responseJson().get( "scimType" ).getAsString() );
        assertTrue( responseJson().get( "detail" ).getAsString().contains( "displayName" ) );
    }

    @Test
    void listFilterSyntaxErrorIs400InvalidFilter() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "userName co \"a\"" );
        resource.doGet( req, resp );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidFilter", responseJson().get( "scimType" ).getAsString() );
    }

    @Test
    void listFilterMatchingNothingStillAnswersAnEmptyListResponse() throws Exception {
        when( req.getParameter( "filter" ) ).thenReturn( "userName eq \"ghost\"" );
        when( db.findByLoginName( "ghost" ) ).thenThrow( new NoSuchPrincipalException( "ghost" ) );
        resource.doGet( req, resp );
        verify( resp, never() ).setStatus( anyInt() );
        final JsonObject o = responseJson();
        assertEquals( 0, o.get( "totalResults" ).getAsInt() );
        assertEquals( 0, o.getAsJsonArray( "Resources" ).size() );
    }

    @Test
    void listByUserNameReturnsTheFullUserDocumentWithItsLocation() throws Exception {
        existing( "uid-kim", "kim", false );
        when( req.getParameter( "filter" ) ).thenReturn( "userName eq \"kim\"" );
        resource.doGet( req, resp );
        final JsonObject o = responseJson();
        assertEquals( 1, o.get( "totalResults" ).getAsInt() );
        final JsonObject r = o.getAsJsonArray( "Resources" ).get( 0 ).getAsJsonObject();
        assertEquals( "kim", r.get( "userName" ).getAsString() );
        assertEquals( "http://localhost/scim/v2/Users/uid-kim", r.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
    }

    @Test
    void externalIdFilterSelectsOnlyAccountsLinkedToThatSubject() throws Exception {
        final Principal wa = mock( Principal.class );
        final Principal wb = mock( Principal.class );
        final Principal wc = mock( Principal.class );
        final Principal wd = mock( Principal.class );
        when( wa.getName() ).thenReturn( "A" );
        when( wb.getName() ).thenReturn( "B" );
        when( wc.getName() ).thenReturn( "C" );
        when( wd.getName() ).thenReturn( "D" );
        when( db.getWikiNames() ).thenReturn( new Principal[]{ wa, wb, wc, wd } );
        db( "A", "alice", Map.of( "sso.subject", "ext-1" ) );
        db( "B", "bob", Map.of( "sso.subject", "ext-2" ) );
        db( "C", "carl", Map.of() );
        final UserProfile nullAttrs = mock( UserProfile.class );
        when( nullAttrs.getLoginName() ).thenReturn( "dora" );
        when( db.findByWikiName( "D" ) ).thenReturn( nullAttrs );
        when( req.getParameter( "filter" ) ).thenReturn( "externalId eq \"ext-1\"" );

        resource.doGet( req, resp );

        final JsonObject o = responseJson();
        assertEquals( 1, o.get( "totalResults" ).getAsInt() );
        assertEquals( "alice", o.getAsJsonArray( "Resources" ).get( 0 ).getAsJsonObject().get( "userName" ).getAsString() );
    }

    private void db( final String wiki, final String login, final Map<String, Serializable> attrs ) throws Exception {
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( login );
        when( p.getAttributes() ).thenReturn( new HashMap<>( attrs ) );
        when( db.findByWikiName( wiki ) ).thenReturn( p );
    }

    @Test
    void externalIdFilterWithNoMatchIsEmptyNotAnError() throws Exception {
        final Principal wa = mock( Principal.class );
        when( wa.getName() ).thenReturn( "A" );
        when( db.getWikiNames() ).thenReturn( new Principal[]{ wa } );
        db( "A", "alice", Map.of( "sso.subject", "ext-1" ) );
        when( req.getParameter( "filter" ) ).thenReturn( "externalId eq \"nope\"" );
        resource.doGet( req, resp );
        assertEquals( 0, responseJson().get( "totalResults" ).getAsInt() );
    }

    @Test
    void getByIdBuildsTheLocationFromTheCollectionUrl() throws Exception {
        existing( "uid-lee", "lee", false );
        when( req.getPathInfo() ).thenReturn( "/uid-lee" );
        when( req.getRequestURL() ).thenReturn( new StringBuffer( "https://wiki.example.com/scim/v2/Users/uid-lee" ) );
        resource.doGet( req, resp );
        assertEquals( "https://wiki.example.com/scim/v2/Users/uid-lee",
                responseJson().getAsJsonObject( "meta" ).get( "location" ).getAsString() );
    }

    // ------------------------------------------------------------------ replace

    private void put( final String id, final String json ) throws Exception {
        when( req.getMethod() ).thenReturn( "PUT" );
        when( req.getPathInfo() ).thenReturn( "/" + id );
        body( json );
        resource.service( req, resp );
    }

    private void patch( final String id, final String json ) throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/" + id );
        body( json );
        resource.service( req, resp );
    }

    @Test
    void replaceSetsAPasswordOnlyWhenOneIsSupplied() throws Exception {
        final UserProfile p = existing( "uid-m", "mia", false );
        put( "uid-m", "{\"userName\":\"mia\",\"password\":\"New-Pass-123\"}" );
        verify( p ).setPassword( "New-Pass-123" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{\"userName\":\"mia\"}", "{\"userName\":\"mia\",\"password\":\"  \"}", "{\"userName\":\"mia\",\"password\":null}" } )
    void replaceWithNoUsablePasswordLeavesTheStoredOneAlone( final String json ) throws Exception {
        final UserProfile p = existing( "uid-m", "mia", false );
        put( "uid-m", json );
        verify( p, never() ).setPassword( any() );
        verify( db ).save( p );
    }

    @Test
    void replaceWithActiveFalseOnAnActiveAccountLocksItIndefinitely() throws Exception {
        final UserProfile p = existing( "uid-n", "ned", false );
        put( "uid-n", "{\"userName\":\"ned\",\"active\":false}" );
        final ArgumentCaptor<Date> lock = ArgumentCaptor.forClass( Date.class );
        verify( p ).setLockExpiry( lock.capture() );
        assertNotNull( lock.getValue() );
        verify( db ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void replaceWithActiveTrueOnALockedAccountClearsTheLock() throws Exception {
        final UserProfile p = existing( "uid-o", "oz", true );
        put( "uid-o", "{\"userName\":\"oz\",\"active\":true}" );
        verify( p ).setLockExpiry( null );
        verify( db ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{\"userName\":\"pat\",\"active\":true}", "{\"userName\":\"pat\"}" } )
    void replaceThatKeepsAnActiveAccountActiveNeverTouchesTheLock( final String json ) throws Exception {
        final UserProfile p = existing( "uid-p", "pat", false );
        put( "uid-p", json );
        verify( p, never() ).setLockExpiry( any() );
        verify( db ).save( p );
    }

    @Test
    void replaceThatKeepsALockedAccountLockedDoesNotUnlockIt() throws Exception {
        final UserProfile p = existing( "uid-q", "quin", true );
        put( "uid-q", "{\"userName\":\"quin\",\"active\":false}" );
        verify( p, never() ).setLockExpiry( any() );
    }

    @Test
    void replaceIsAuditedAsAnUpdateOfTheLogin() throws Exception {
        existing( "uid-r", "rae", false );
        put( "uid-r", "{\"userName\":\"rae\"}" );
        final AuditEntry e = auditedEntry();
        assertEquals( "scim.user.update", e.eventType() );
        assertEquals( "rae", e.targetId() );
    }

    // ------------------------------------------------------------------ patch

    @Test
    void patchActiveFalseLocksAndActiveTrueUnlocks() throws Exception {
        final UserProfile p = existing( "uid-s", "sam", false );
        patch( "uid-s", "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}" );
        verify( p ).setLockExpiry( any( Date.class ) );
        verify( p, never() ).setLockExpiry( null );
        verify( db ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchActiveTrueClearsTheLock() throws Exception {
        final UserProfile p = existing( "uid-t", "tia", true );
        patch( "uid-t", "{\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]}" );
        verify( p ).setLockExpiry( null );
        verify( db ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchOfOnlyAttributesDoesNotTouchTheLock() throws Exception {
        final UserProfile p = existing( "uid-u", "uma", false );
        patch( "uid-u", "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Uma U\"}]}" );
        verify( p, never() ).setLockExpiry( any() );
        verify( p ).setWikiName( "Uma U" );
        verify( db ).save( p );
    }

    @Test
    void patchThatChangesNothingDoesNotSaveButStillAnswersTheUser() throws Exception {
        final UserProfile p = existing( "uid-v", "vic", false );
        patch( "uid-v", "{\"Operations\":[{\"op\":\"remove\",\"path\":\"emails\"}]}" );
        verify( db, never() ).save( any() );
        assertEquals( "vic", responseJson().get( "userName" ).getAsString() );
        assertNotNull( p );
    }

    @Test
    void patchIsAuditedAsAnUpdateOfTheLogin() throws Exception {
        existing( "uid-w", "wes", false );
        patch( "uid-w", "{\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Wes W\"}]}" );
        final AuditEntry e = auditedEntry();
        assertEquals( "scim.user.update", e.eventType() );
        assertEquals( "wes", e.targetId() );
    }

    @Test
    void patchWithAnExplicitMalformedBodyShapeIsRefusedBeforeAnyChange() throws Exception {
        final UserProfile p = existing( "uid-x", "xan", false );
        patch( "uid-x", "{\"Operations\":[{\"op\":\"replace\",\"path\":\"emails[type eq work].value\",\"value\":\"a\"}]}" );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidPath", responseJson().get( "scimType" ).getAsString() );
        verify( p, never() ).setLockExpiry( any() );
        verify( db, never() ).save( any() );
    }

    @Test
    void replaceAndPatchOfAnUnknownUserAre404WithoutReadingTheBody() throws Exception {
        when( db.findByUid( "nope" ) ).thenThrow( new NoSuchPrincipalException( "nope" ) );
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/nope" );
        resource.service( req, resp );
        verify( resp ).setStatus( 404 );
        verify( req, never() ).getReader();
        assertTrue( responseJson().get( "detail" ).getAsString().contains( "nope" ) );
        verify( db, never() ).save( any() );
    }

    // ------------------------------------------------------------------ PATCH remove / invalid value, PUT unknown

    private void patchOp( final String id, final String op ) throws Exception {
        patch( id, "{\"Operations\":[" + op + "]}" );
    }

    @Test
    void patchRemoveOfEmailsClearsTheEmailAndSaves() throws Exception {
        final UserProfile p = existing( "uid-e", "eve", false );
        when( p.getEmail() ).thenReturn( "eve@x.com" );
        patchOp( "uid-e", "{\"op\":\"remove\",\"path\":\"emails\"}" );
        verify( p ).setEmail( null );
        verify( db ).save( p );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchRemoveOfEmailsWhenThereIsNoneIsANoOp() throws Exception {
        final UserProfile p = existing( "uid-e", "eve", false );
        when( p.getEmail() ).thenReturn( null );
        patchOp( "uid-e", "{\"op\":\"remove\",\"path\":\"emails\"}" );
        verify( db, never() ).save( any() );
        verify( resp, never() ).setStatus( anyInt() );
    }

    @Test
    void patchRemoveOfExternalIdUnlinksTheSsoSubject() throws Exception {
        final UserProfile p = existing( "uid-x", "xia", false );
        p.getAttributes().put( "sso.subject", "ext-1" );
        patchOp( "uid-x", "{\"op\":\"remove\",\"path\":\"externalId\"}" );
        assertFalse( p.getAttributes().containsKey( "sso.subject" ) );
        verify( db ).save( p );
    }

    @ParameterizedTest
    @ValueSource( strings = { "userName", "displayName", "name", "name.formatted", "id" } )
    void patchRemoveOfARequiredOrReadOnlyAttributeIs400Mutability( final String path ) throws Exception {
        final UserProfile p = existing( "uid-r", "rob", false );
        patchOp( "uid-r", "{\"op\":\"remove\",\"path\":\"" + path + "\"}" );
        verify( resp ).setStatus( 400 );
        assertEquals( "mutability", responseJson().get( "scimType" ).getAsString() );
        verify( db, never() ).save( any() );
        verify( p, never() ).setWikiName( any() );
    }

    @Test
    void patchRemoveOfActiveIs400Mutability() throws Exception {
        existing( "uid-r", "rob", false );
        patchOp( "uid-r", "{\"op\":\"remove\",\"path\":\"active\"}" );
        verify( resp ).setStatus( 400 );
        assertEquals( "mutability", responseJson().get( "scimType" ).getAsString() );
    }

    @Test
    void patchRemoveOfAnUnknownAttributeIs400NoTarget() throws Exception {
        existing( "uid-r", "rob", false );
        patchOp( "uid-r", "{\"op\":\"remove\",\"path\":\"nickName\"}" );
        verify( resp ).setStatus( 400 );
        assertEquals( "noTarget", responseJson().get( "scimType" ).getAsString() );
        verify( db, never() ).save( any() );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "{\"op\":\"replace\",\"path\":\"active\"}",
            "{\"op\":\"add\",\"path\":\"active\",\"value\":null}",
            "{\"op\":\"replace\",\"value\":{\"active\":null}}" } )
    void patchActiveWithNoValueIs400InvalidValueAndChangesNothing( final String op ) throws Exception {
        final UserProfile p = existing( "uid-a", "ann", false );
        patchOp( "uid-a", op );
        verify( resp ).setStatus( 400 );
        assertEquals( "invalidValue", responseJson().get( "scimType" ).getAsString() );
        verify( p, never() ).setLockExpiry( any() );
        verify( db, never() ).save( any() );
    }

    @Test
    void putOfAnUnknownUserIs404AndWritesNothing() throws Exception {
        when( db.findByUid( "nope" ) ).thenThrow( new NoSuchPrincipalException( "nope" ) );
        put( "nope", "{\"userName\":\"ghost\"}" );
        verify( resp ).setStatus( 404 );
        verify( db, never() ).save( any() );
        verify( db, never() ).newProfile();
    }
}
