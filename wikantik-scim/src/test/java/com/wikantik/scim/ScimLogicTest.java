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
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins the parsing, patch, mapping and paging logic of the SCIM module that the resource-level tests only
 * exercise through the happy path (found by mutation testing).
 */
class ScimLogicTest {

    private static JsonObject json( final String s ) {
        return JsonParser.parseString( s ).getAsJsonObject();
    }

    private static JsonObject patch( final String ops ) {
        return json( "{\"Operations\":" + ops + "}" );
    }

    // ---------------------------------------------------------------- ScimPatchApplier

    @ParameterizedTest
    @ValueSource( strings = { "{}", "{\"Operations\":{}}", "{\"Operations\":\"x\"}", "{\"Operations\":null}" } )
    void userPatchWithoutAnOperationsArrayIsRefused( final String body ) {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply( json( body ) ) );
    }

    @Test
    void userPatchEmptyOperationsChangesNothing() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply( patch( "[]" ) );
        assertNull( r.activeChange() );
        assertEquals( 0, r.attributes().size() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "emails[type eq work].value", "emails[0]", "a.b c", "name.givenName and x" } )
    void userPatchComplexPathsAreRefused( final String path ) {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
                patch( "[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"x\"}]" ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "name.givenName", "a b", "displayName" } )
    void userPatchSimplePathsAreAcceptedVerbatim( final String path ) {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply(
                patch( "[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"x\"}]" ) );
        assertEquals( "x", r.attributes().get( path ).getAsString() );
    }

    @Test
    void userPatchPathlessValueObjectSplitsActiveFromAttributes() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply( patch(
                "[{\"op\":\"replace\",\"value\":{\"active\":false,\"displayName\":\"Al\",\"externalId\":\"e1\"}}]" ) );
        assertEquals( Boolean.FALSE, r.activeChange() );
        assertEquals( 2, r.attributes().size() );
        assertEquals( "Al", r.attributes().get( "displayName" ).getAsString() );
        assertEquals( "e1", r.attributes().get( "externalId" ).getAsString() );
        assertFalse( r.attributes().has( "active" ), "active must not leak into the attribute bag" );
    }

    @Test
    void userPatchActiveAsStringIsCoerced() {
        // Microsoft Entra sends the strings "True"/"False".
        assertEquals( Boolean.FALSE, ScimPatchApplier.apply(
                patch( "[{\"op\":\"Replace\",\"path\":\"active\",\"value\":\"False\"}]" ) ).activeChange() );
        assertEquals( Boolean.TRUE, ScimPatchApplier.apply(
                patch( "[{\"op\":\"ADD\",\"path\":\"active\",\"value\":\"True\"}]" ) ).activeChange() );
    }

    @Test
    void userPatchRemoveOfASimpleAttributeIsRecordedAsJsonNull() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply(
                patch( "[{\"op\":\"remove\",\"path\":\"emails\"}]" ) );
        assertTrue( r.attributes().has( "emails" ) );
        assertTrue( r.attributes().get( "emails" ).isJsonNull() );
    }

    @Test
    void userPatchRemoveWithoutPathChangesNothing() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply( patch( "[{\"op\":\"remove\"}]" ) );
        assertEquals( 0, r.attributes().size() );
        assertNull( r.activeChange() );
    }

    @Test
    void userPatchRemoveOfActiveIsRefused() {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
                patch( "[{\"op\":\"remove\",\"path\":\"active\"}]" ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[{\"op\":\"move\",\"path\":\"x\"}]", "[{\"path\":\"x\",\"value\":1}]" } )
    void userPatchUnknownOrMissingOpIsRefused( final String ops ) {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply( patch( ops ) ) );
    }

    @Test
    void userPatchLaterOperationsOverrideEarlierOnes() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply( patch(
                "[{\"op\":\"replace\",\"path\":\"active\",\"value\":false},"
              + "{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]" ) );
        assertEquals( Boolean.TRUE, r.activeChange() );
    }

    // ---------------------------------------------------------------- ScimGroupPatchApplier

    private static LinkedHashSet<String> groupApply( final List<String> current, final String ops ) {
        return ScimGroupPatchApplier.apply( current, patch( ops ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "{}", "{\"Operations\":{}}", "{\"Operations\":5}" } )
    void groupPatchWithoutAnOperationsArrayIsRefused( final String body ) {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class,
                () -> ScimGroupPatchApplier.apply( List.of(), json( body ) ) );
    }

    @Test
    void groupPatchAddWithNoValueLeavesMembersAlone() {
        assertEquals( List.of( "a" ), new ArrayList<>( groupApply( List.of( "a" ), "[{\"op\":\"add\",\"path\":\"members\"}]" ) ) );
        assertEquals( List.of( "a" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":null}]" ) ) );
    }

    @Test
    void groupPatchAddAcceptsArraysBareObjectsAndEntraMembersObject() {
        assertEquals( List.of( "a", "b", "c" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"b\"},\"c\"]}]" ) ) );
        assertEquals( List.of( "a", "d" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":{\"value\":\"d\"}}]" ) ) );
        assertEquals( List.of( "a", "e" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"add\",\"value\":{\"members\":[{\"value\":\"e\"}]}}]" ) ) );
    }

    @Test
    void groupPatchAddOfAnExistingMemberDoesNotDuplicate() {
        assertEquals( List.of( "a" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"a\"}]}]" ) ) );
    }

    @Test
    void groupPatchAddOfAnUnrecognisedValueObjectIsRefused() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class, () -> groupApply( List.of(),
                "[{\"op\":\"add\",\"path\":\"members\",\"value\":{\"foo\":1}}]" ) );
    }

    @Test
    void groupPatchReplaceWithNoValueEmptiesTheGroup() {
        assertTrue( groupApply( List.of( "a", "b" ), "[{\"op\":\"replace\",\"path\":\"members\"}]" ).isEmpty() );
    }

    @Test
    void groupPatchReplaceOfAnotherPathIsRefused() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class, () -> groupApply( List.of(),
                "[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"X\"}]" ) );
    }

    @Test
    void groupPatchRemoveByValuePathTakesOnlyThatMember() {
        assertEquals( List.of( "a", "c" ), new ArrayList<>( groupApply( List.of( "a", "b", "c" ),
                "[{\"op\":\"remove\",\"path\":\"members[value eq \\\"b\\\"]\"}]" ) ) );
    }

    @Test
    void groupPatchRemoveWithoutPathIsRefused() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class, () -> groupApply( List.of( "a" ),
                "[{\"op\":\"remove\"}]" ) );
    }

    @Test
    void groupPatchUnknownOpIsRefusedAndOperationsApplyInOrder() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class, () -> groupApply( List.of(),
                "[{\"op\":\"copy\",\"path\":\"members\"}]" ) );
        assertEquals( List.of( "z" ), new ArrayList<>( groupApply( List.of( "a" ),
                "[{\"op\":\"remove\",\"path\":\"members\"},{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"z\"}]}]" ) ) );
    }

    // ---------------------------------------------------------------- ScimFilterParser

    @Test
    void filterAllowsSurroundingWhitespaceAndEmptyValue() {
        final ScimFilterParser.Eq eq = ScimFilterParser.parse( "  userName   eq   \"\"  " ).orElseThrow();
        assertEquals( "userName", eq.attribute() );
        assertEquals( "", eq.value() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "userName eq alice", "userName eq \"a\" and active eq \"true\"", "userName eq \"a", "eq \"a\"" } )
    void filterOutsideTheSupportedGrammarIsRefused( final String filter ) {
        assertThrows( ScimFilterParser.UnsupportedFilterException.class, () -> ScimFilterParser.parse( filter ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "USERNAME EQ \"a\"", "username Eq \"a\"", "userName eQ \"a\"" } )
    void filterOperatorAndAttributeNamesAreCaseInsensitive( final String filter ) {
        final ScimFilterParser.Eq eq = ScimFilterParser.parse( filter ).orElseThrow();
        assertEquals( "userName", eq.attribute(), "attribute is canonicalised so callers can compare it exactly" );
        assertEquals( "a", eq.value() );
    }

    @Test
    void filterValueKeepsItsCase() {
        assertEquals( "AbC", ScimFilterParser.parse( "externalid eq \"AbC\"" ).orElseThrow().value() );
    }

    @Test
    void blankFilterIsNoConstraint() {
        assertTrue( ScimFilterParser.parse( "   " ).isEmpty() );
    }

    // ---------------------------------------------------------------- ScimIo

    @Test
    void listResponseEchoesStartIndexAndPagesFromOneBased() {
        final JsonObject r = ScimIo.buildListResponse( List.of( "a", "b", "c", "d" ), 2, 2, s -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "n", s );
            return o;
        } );
        assertEquals( 4, r.get( "totalResults" ).getAsInt() );
        assertEquals( 2, r.get( "startIndex" ).getAsInt() );
        assertEquals( 2, r.get( "itemsPerPage" ).getAsInt() );
        assertEquals( "b", r.getAsJsonArray( "Resources" ).get( 0 ).getAsJsonObject().get( "n" ).getAsString() );
        assertEquals( "c", r.getAsJsonArray( "Resources" ).get( 1 ).getAsJsonObject().get( "n" ).getAsString() );
        assertTrue( r.getAsJsonArray( "schemas" ).toString().contains( "ListResponse" ) );
    }

    @ParameterizedTest
    @CsvSource( { "1,10,3", "3,10,1", "4,10,0", "9,10,0", "1,0,0", "2,1,1" } )
    void listResponsePageSizeAtTheBoundaries( final int start, final int count, final int expectedPage ) {
        final JsonObject r = ScimIo.buildListResponse( List.of( 1, 2, 3 ), start, count, i -> new JsonObject() );
        assertEquals( 3, r.get( "totalResults" ).getAsInt() );
        assertEquals( expectedPage, r.get( "itemsPerPage" ).getAsInt() );
        assertEquals( expectedPage, r.getAsJsonArray( "Resources" ).size() );
    }

    @Test
    void listResponseTreatsAZeroStartIndexAsTheFirstPage() {
        final JsonObject r = ScimIo.buildListResponse( List.of( 1, 2 ), 0, 5, i -> new JsonObject() );
        assertEquals( 2, r.get( "itemsPerPage" ).getAsInt() );
    }

    @ParameterizedTest
    @CsvSource( value = { "/abc,abc", "abc,abc", "/a/b,a/b", "/,", "'',", "'   ',", "'/  '," }, nullValues = "" )
    void pathSegmentExtraction( final String pathInfo, final String expected ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getPathInfo() ).thenReturn( pathInfo );
        assertEquals( expected, ScimIo.extractPathSegment( req ) );
    }

    @Test
    void pathSegmentOfARequestWithNoPathInfoIsNull() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        assertNull( ScimIo.extractPathSegment( req ) );
    }

    // ---------------------------------------------------------------- AbstractScimServlet helpers

    @ParameterizedTest
    @CsvSource( value = { "7,50,7", "' 12 ',50,12", "1,50,1", "0,50,50", "-3,50,50", "abc,50,50", "'',50,50", "2147483648,50,50" } )
    void positiveIntegerParameterOrDefault( final String raw, final int dflt, final int expected ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "count" ) ).thenReturn( raw );
        assertEquals( expected, AbstractScimServlet.parseIntParam( req, "count", dflt ) );
    }

    @Test
    void absentIntegerParameterGivesTheDefault() {
        assertEquals( 9, AbstractScimServlet.parseIntParam( mock( HttpServletRequest.class ), "count", 9 ) );
    }

    @Test
    void sendScimSetsTheScimMediaTypeAndWritesTheBody() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        AbstractScimServlet.sendScim( resp, json( "{\"id\":\"x\"}" ) );
        verify( resp ).setContentType( "application/scim+json" );
        assertEquals( "x", json( sw.toString() ).get( "id" ).getAsString() );
    }

    @Test
    void sendErrorSetsStatusMediaTypeAndRfc7644ErrorBody() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        AbstractScimServlet.sendError( resp, 409, "uniqueness", "dup" );
        verify( resp ).setStatus( 409 );
        verify( resp ).setContentType( "application/scim+json" );
        final JsonObject body = json( sw.toString() );
        assertEquals( "409", body.get( "status" ).getAsString() );
        assertEquals( "uniqueness", body.get( "scimType" ).getAsString() );
        assertEquals( "dup", body.get( "detail" ).getAsString() );
    }

    // ---------------------------------------------------------------- ScimUserMapper

    private static UserProfile profile( final String uid, final String login ) {
        final UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( uid );
        when( p.getLoginName() ).thenReturn( login );
        return p;
    }

    @Test
    void toScimMarksTheOnlyEmailPrimaryAndTypesTheMeta() {
        final UserProfile p = profile( "u1", "alice" );
        when( p.getEmail() ).thenReturn( "a@x.com" );
        when( p.getFullname() ).thenReturn( "Alice A" );
        when( p.getWikiName() ).thenReturn( "AliceA" );
        final JsonObject o = ScimUserMapper.toScim( p, "http://h/scim/v2/Users" );
        final JsonObject em = o.getAsJsonArray( "emails" ).get( 0 ).getAsJsonObject();
        assertTrue( em.get( "primary" ).getAsBoolean() );
        assertEquals( "User", o.getAsJsonObject( "meta" ).get( "resourceType" ).getAsString() );
        assertEquals( "http://h/scim/v2/Users/u1", o.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
        assertEquals( "Alice A", o.getAsJsonObject( "name" ).get( "formatted" ).getAsString() );
        assertEquals( "AliceA", o.get( "displayName" ).getAsString() );
        assertTrue( o.get( "active" ).getAsBoolean() );
    }

    @Test
    void toScimOmitsOptionalAttributesTheProfileLacks() {
        final JsonObject o = ScimUserMapper.toScim( profile( "u2", "bob" ), "http://h/Users" );
        assertFalse( o.has( "emails" ) );
        assertFalse( o.has( "name" ) );
        assertFalse( o.has( "displayName" ) );
        assertFalse( o.has( "externalId" ) );
    }

    @Test
    void readCreateJoinsGivenAndFamilyNamesWhenThereIsNoFormattedName() {
        assertEquals( "Ann Lee", ScimUserMapper.readCreate(
                json( "{\"userName\":\"u\",\"name\":{\"givenName\":\"Ann\",\"familyName\":\"Lee\"}}" ) ).fullName() );
        assertEquals( "Ann", ScimUserMapper.readCreate(
                json( "{\"userName\":\"u\",\"name\":{\"givenName\":\"Ann\"}}" ) ).fullName() );
        assertEquals( "Lee", ScimUserMapper.readCreate(
                json( "{\"userName\":\"u\",\"name\":{\"familyName\":\"Lee\"}}" ) ).fullName() );
        assertNull( ScimUserMapper.readCreate( json( "{\"userName\":\"u\",\"name\":{\"honorificPrefix\":\"Dr\"}}" ) ).fullName() );
        assertNull( ScimUserMapper.readCreate( json( "{\"userName\":\"u\"}" ) ).fullName() );
    }

    @Test
    void readCreatePrefersTheFormattedName() {
        assertEquals( "Dr Ann Lee", ScimUserMapper.readCreate( json(
                "{\"name\":{\"formatted\":\"Dr Ann Lee\",\"givenName\":\"Ann\",\"familyName\":\"Lee\"}}" ) ).fullName() );
    }

    @Test
    void readCreateToleratesAnExplicitNullFormattedName() {
        // IdPs routinely send "formatted": null next to givenName/familyName.
        final ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( json(
                "{\"userName\":\"u\",\"name\":{\"formatted\":null,\"givenName\":\"Ann\",\"familyName\":\"Lee\"}}" ) );
        assertEquals( "Ann Lee", f.fullName() );
    }

    @Test
    void readCreateEmailSelection() {
        assertEquals( "second@x", ScimUserMapper.readCreate( json(
                "{\"emails\":[{\"value\":\"first@x\"},{\"value\":\"second@x\",\"primary\":true}]}" ) ).email() );
        assertEquals( "first@x", ScimUserMapper.readCreate( json(
                "{\"emails\":[{\"value\":\"first@x\",\"primary\":false},{\"value\":\"second@x\",\"primary\":false}]}" ) ).email() );
        assertEquals( "first@x", ScimUserMapper.readCreate( json(
                "{\"emails\":[{\"value\":\"first@x\"},{\"value\":\"second@x\"}]}" ) ).email() );
        assertNull( ScimUserMapper.readCreate( json( "{\"emails\":[]}" ) ).email() );
        assertNull( ScimUserMapper.readCreate( json( "{\"emails\":\"a@x\"}" ) ).email() );
        assertNull( ScimUserMapper.readCreate( json( "{}" ) ).email() );
    }

    @Test
    void readCreateToleratesAnExplicitNullPrimaryFlag() {
        assertEquals( "only@x", ScimUserMapper.readCreate( json(
                "{\"emails\":[{\"value\":\"only@x\",\"primary\":null}]}" ) ).email() );
    }

    @Test
    void readCreateActiveIsTriState() {
        assertEquals( Boolean.TRUE, ScimUserMapper.readCreate( json( "{\"active\":true}" ) ).active() );
        assertEquals( Boolean.FALSE, ScimUserMapper.readCreate( json( "{\"active\":false}" ) ).active() );
        assertNull( ScimUserMapper.readCreate( json( "{\"active\":null}" ) ).active() );
        assertNull( ScimUserMapper.readCreate( json( "{}" ) ).active() );
    }

    @Test
    void readCreateReadsPasswordDisplayNameAndTreatsJsonNullAsAbsent() {
        final ScimUserMapper.CreateFields f = ScimUserMapper.readCreate(
                json( "{\"userName\":null,\"password\":\"pw\",\"displayName\":\"Al\",\"externalId\":null}" ) );
        assertNull( f.userName() );
        assertNull( f.externalId() );
        assertEquals( "pw", f.password() );
        assertEquals( "Al", f.displayName() );
    }

    // ---------------------------------------------------------------- ScimGroupMapper

    @Test
    void groupToScimTypesTheMeta() {
        final com.wikantik.auth.authorize.Group g = mock( com.wikantik.auth.authorize.Group.class );
        when( g.getName() ).thenReturn( "Devs" );
        when( g.members() ).thenReturn( new java.security.Principal[0] );
        final JsonObject o = ScimGroupMapper.toScim( g, "http://h/Users", "http://h/Groups", l -> null );
        assertEquals( "Group", o.getAsJsonObject( "meta" ).get( "resourceType" ).getAsString() );
        assertEquals( "http://h/Groups/Devs", o.getAsJsonObject( "meta" ).get( "location" ).getAsString() );
    }

    // ---------------------------------------------------------------- ScimUserFields

    private static UserProfile editable( final Map<String, Serializable> attrs ) {
        final UserProfile p = mock( UserProfile.class );
        when( p.getAttributes() ).thenReturn( attrs );
        return p;
    }

    @Test
    void patchAttributesWithNothingToApplyIsNotDirty() {
        final UserProfile p = editable( new HashMap<>() );
        assertFalse( ScimUserFields.applyPatchAttributes( p, json( "{}" ) ) );
        assertFalse( ScimUserFields.applyPatchAttributes( p, json(
                "{\"displayName\":null,\"emails\":[],\"name\":{},\"externalId\":null}" ) ) );
        verify( p, never() ).setWikiName( any() );
        verify( p, never() ).setEmail( any() );
        verify( p, never() ).setFullname( any() );
    }

    @Test
    void patchAttributesAppliesEveryAttributeNotJustTheFirstThatChanged() {
        final Map<String, Serializable> attrs = new HashMap<>();
        final UserProfile p = editable( attrs );
        final boolean dirty = ScimUserFields.applyPatchAttributes( p, json(
                "{\"displayName\":\"Al\",\"name\":{\"formatted\":\"  Alice A \"},"
              + "\"emails\":[{\"value\":\"a@x\"},{\"value\":\"b@x\"}],\"externalId\":\"ext-1\"}" ) );
        assertTrue( dirty );
        verify( p ).setWikiName( "Al" );
        verify( p ).setFullname( "Alice A" );
        verify( p ).setEmail( "a@x" );
        assertEquals( "ext-1", attrs.get( "sso.subject" ) );
    }

    @Test
    void patchAttributesIgnoresMalformedEmailsShapes() {
        final UserProfile p = editable( new HashMap<>() );
        assertFalse( ScimUserFields.applyPatchAttributes( p, json( "{\"emails\":\"a@x\"}" ) ) );
        assertFalse( ScimUserFields.applyPatchAttributes( p, json( "{\"emails\":[{\"type\":\"work\"}]}" ) ) );
        verify( p, never() ).setEmail( any() );
    }

    @Test
    void patchAttributesIgnoresNameThatIsNotAnObject() {
        final UserProfile p = editable( new HashMap<>() );
        assertFalse( ScimUserFields.applyPatchAttributes( p, json( "{\"name\":\"Alice\"}" ) ) );
        verify( p, never() ).setFullname( any() );
    }

    @Test
    void commonCreateFieldsSetOnlyWhatWasSupplied() {
        final Map<String, Serializable> attrs = new HashMap<>();
        final UserProfile p = editable( attrs );
        ScimUserFields.applyCommonCreateFields( p, new ScimUserMapper.CreateFields(
                "u", "ext-2", " Alice A ", "a@x", "Al", null, null ) );
        verify( p ).setFullname( "Alice A" );
        verify( p ).setEmail( "a@x" );
        verify( p ).setWikiName( "Al" );
        assertEquals( "ext-2", attrs.get( "sso.subject" ) );
    }

    @Test
    void commonCreateFieldsWithNothingSuppliedTouchesNothing() {
        final UserProfile p = mock( UserProfile.class ); // getAttributes() == null: touching it would NPE
        ScimUserFields.applyCommonCreateFields( p, new ScimUserMapper.CreateFields( "u", null, null, null, null, null, null ) );
        verify( p, never() ).setFullname( any() );
        verify( p, never() ).setEmail( any() );
        verify( p, never() ).setWikiName( any() );
    }

    @Test
    void nullHeaderIsNotABearerCredential() throws Exception {
        final ScimAccessFilter f = new ScimAccessFilter( "tok" );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        final jakarta.servlet.FilterChain chain = mock( jakarta.servlet.FilterChain.class );
        f.doFilter( req, resp, chain );
        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( 401 );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[{\"op\":\"replace\",\"path\":\"active\"}]",
            "[{\"op\":\"add\",\"path\":\"active\",\"value\":null}]",
            "[{\"op\":\"replace\",\"value\":{\"active\":null}}]" } )
    void userPatchActiveWithoutAValueIsAnInvalidValueNotACrash( final String ops ) {
        final ScimPatchApplier.UnsupportedPatchException e = assertThrows(
                ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply( patch( ops ) ) );
        assertEquals( "invalidValue", e.scimType() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "userName", "displayName", "name", "name.formatted", "id", "active", "meta" } )
    void userPatchRemoveOfRequiredOrReadOnlyIsMutability( final String path ) {
        final ScimPatchApplier.UnsupportedPatchException e = assertThrows(
                ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
                        patch( "[{\"op\":\"remove\",\"path\":\"" + path + "\"}]" ) ) );
        assertEquals( "mutability", e.scimType() );
    }

    @Test
    void userPatchRemoveOfAnUnknownAttributeIsNoTarget() {
        final ScimPatchApplier.UnsupportedPatchException e = assertThrows(
                ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
                        patch( "[{\"op\":\"remove\",\"path\":\"nickName\"}]" ) ) );
        assertEquals( "noTarget", e.scimType() );
    }

    @Test
    void userPatchOtherRefusalsKeepTheInvalidPathType() {
        assertEquals( "invalidPath", assertThrows( ScimPatchApplier.UnsupportedPatchException.class,
                () -> ScimPatchApplier.apply( json( "{}" ) ) ).scimType() );
    }

    @Test
    void userPatchRemoveOfEmailsAndExternalIdIsRecordedAsJsonNull() {
        final ScimPatchApplier.Result r = ScimPatchApplier.apply( patch(
                "[{\"op\":\"remove\",\"path\":\"emails\"},{\"op\":\"remove\",\"path\":\"externalId\"}]" ) );
        assertTrue( r.attributes().get( "emails" ).isJsonNull() );
        assertTrue( r.attributes().get( "externalId" ).isJsonNull() );
    }
}
