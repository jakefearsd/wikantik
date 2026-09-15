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
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolUtilsTest {

    private static String textOf( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    // --- jsonResult / errorResult -------------------------------------------------

    @Test
    void jsonResultSetsContentAndStructuredContent() {
        final Map< String, Object > data = Map.of( "a", 1 );
        final McpSchema.CallToolResult result = McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, data );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        assertEquals( data, result.structuredContent() );
        assertTrue( textOf( result ).contains( "\"a\":1" ) );
    }

    @Test
    void errorResultSetsIsErrorAndMessage() {
        final McpSchema.CallToolResult result = McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "boom" );

        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "boom" ) );
        assertEquals( "boom", ( ( Map< ?, ? > ) result.structuredContent() ).get( "error" ) );
    }

    @Test
    void errorResultWithSuggestionIncludesBothFields() {
        final McpSchema.CallToolResult result =
                McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "boom", "try again" );

        assertTrue( result.isError() );
        final Map< ?, ? > body = ( Map< ?, ? > ) result.structuredContent();
        assertEquals( "boom", body.get( "error" ) );
        assertEquals( "try again", body.get( "suggestion" ) );
    }

    // --- sanitizeErrorMessage -------------------------------------------------------

    @Test
    void sanitizeErrorMessageHandlesNullAndEmpty() {
        assertEquals( "internal error (see server log)", McpToolUtils.sanitizeErrorMessage( null ) );
        assertEquals( "internal error (see server log)", McpToolUtils.sanitizeErrorMessage( "" ) );
    }

    @Test
    void sanitizeErrorMessageStripsStackFrames() {
        final String raw = "boom at com.wikantik.Foo.bar(Foo.java:123)";
        assertEquals( "boom", McpToolUtils.sanitizeErrorMessage( raw ) );
    }

    @Test
    void sanitizeErrorMessageStripsExceptionClassNames() {
        final String raw = "java.lang.RuntimeException: something bad happened";
        final String sanitized = McpToolUtils.sanitizeErrorMessage( raw );
        assertFalse( sanitized.contains( "java.lang.RuntimeException" ) );
        assertTrue( sanitized.contains( "error" ) );
    }

    @Test
    void sanitizeErrorMessageStripsJdbcUrls() {
        final String raw = "connection failed jdbc:postgresql://localhost:5432/wikantik user error";
        final String sanitized = McpToolUtils.sanitizeErrorMessage( raw );
        assertFalse( sanitized.contains( "jdbc:postgresql" ) );
        assertTrue( sanitized.contains( "[jdbc-url]" ) );
    }

    @Test
    void sanitizeErrorMessageCollapsesWhitespace() {
        assertEquals( "a b", McpToolUtils.sanitizeErrorMessage( "a   \n\t  b" ) );
    }

    @Test
    void sanitizeErrorMessageFallsBackWhenStrippedToEmpty() {
        // A pure stack-frame fragment collapses to whitespace after stripping.
        final String raw = "  at com.wikantik.Foo.bar(Foo.java:1)  ";
        assertEquals( "internal error (see server log)", McpToolUtils.sanitizeErrorMessage( raw ) );
    }

    // --- normalizeVersion / formatTimestamp -----------------------------------------

    @Test
    void normalizeVersionClampsToOne() {
        assertEquals( 1, McpToolUtils.normalizeVersion( -1 ) );
        assertEquals( 1, McpToolUtils.normalizeVersion( 0 ) );
        assertEquals( 5, McpToolUtils.normalizeVersion( 5 ) );
    }

    @Test
    void formatTimestampReturnsNullForNullDate() {
        assertNull( McpToolUtils.formatTimestamp( null ) );
    }

    @Test
    void formatTimestampReturnsIsoInstant() {
        final Date date = new Date( 0L );
        assertEquals( "1970-01-01T00:00:00Z", McpToolUtils.formatTimestamp( date ) );
    }

    // --- string/int/boolean argument accessors --------------------------------------

    @Test
    void getStringReturnsNullWhenAbsent() {
        assertNull( McpToolUtils.getString( Map.of(), "missing" ) );
    }

    @Test
    void getStringReturnsValueWhenPresent() {
        assertEquals( "hi", McpToolUtils.getString( Map.of( "k", "hi" ), "k" ) );
    }

    @Test
    void getStringWithDefaultFallsBackWhenAbsent() {
        assertEquals( "dflt", McpToolUtils.getString( Map.of(), "k", "dflt" ) );
    }

    @Test
    void getStringAnyReturnsFirstNonBlankMatch() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "slug", "  " );
        args.put( "id", "real-id" );
        assertEquals( "real-id", McpToolUtils.getStringAny( args, "slug", "id" ) );
    }

    @Test
    void getStringAnyReturnsNullWhenNoKeyMatches() {
        assertNull( McpToolUtils.getStringAny( Map.of(), "slug", "id" ) );
    }

    @Test
    void pageSlugOnlyAcceptsSlugKeyNotLegacyPageNameAlias() {
        // D13/retirement: pageName must NOT be accepted as an alias for slug.
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "LegacyAliasPage" );
        assertNull( McpToolUtils.pageSlug( args ), "pageName must not be accepted as a slug alias" );

        args.put( "slug", "RealSlug" );
        assertEquals( "RealSlug", McpToolUtils.pageSlug( args ) );
    }

    @Test
    void pageSlugsOnlyAcceptsSlugsKey() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "names", List.of( "A", "B" ) ); // legacy-shaped key, must be ignored
        assertNull( McpToolUtils.pageSlugs( args ) );

        args.put( "slugs", List.of( "A", "B" ) );
        assertEquals( List.of( "A", "B" ), McpToolUtils.pageSlugs( args ) );
    }

    @Test
    void firstListArgReturnsNullWhenArgsNull() {
        assertNull( McpToolUtils.firstListArg( null, "k" ) );
    }

    @Test
    void firstListArgSkipsNonListValues() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "k1", "not-a-list" );
        args.put( "k2", List.of( 1, 2 ) );
        assertEquals( List.of( 1, 2 ), McpToolUtils.firstListArg( args, "k1", "k2" ) );
    }

    @Test
    void getIntReturnsDefaultWhenAbsentOrNonNumeric() {
        assertEquals( 7, McpToolUtils.getInt( Map.of(), "k", 7 ) );
        final Map< String, Object > args = new HashMap<>();
        args.put( "k", "not-a-number" );
        assertEquals( 7, McpToolUtils.getInt( args, "k", 7 ) );
    }

    @Test
    void getIntCoercesNumberTypes() {
        assertEquals( 42, McpToolUtils.getInt( Map.of( "k", 42.9 ), "k", 0 ) );
        assertEquals( 42, McpToolUtils.getInt( Map.of( "k", 42L ), "k", 0 ) );
    }

    @Test
    void getBooleanIsFalseUnlessExplicitlyTrue() {
        assertFalse( McpToolUtils.getBoolean( Map.of(), "k" ) );
        assertFalse( McpToolUtils.getBoolean( Map.of( "k", "true" ), "k" ) ); // string, not Boolean
        assertTrue( McpToolUtils.getBoolean( Map.of( "k", Boolean.TRUE ), "k" ) );
    }

    // --- paginate --------------------------------------------------------------------

    @Test
    void paginateSlicesAndReportsHasMore() {
        final List< Integer > all = List.of( 1, 2, 3, 4, 5 );
        final Map< String, Object > args = new HashMap<>();
        args.put( "limit", 2 );
        args.put( "offset", 1 );

        final Map< String, Object > page = McpToolUtils.paginate( "items", all, args, 10 );

        assertEquals( List.of( 2, 3 ), page.get( "items" ) );
        assertEquals( 5, page.get( "count" ) );
        assertEquals( 2, page.get( "returned" ) );
        assertEquals( 1, page.get( "offset" ) );
        assertEquals( 2, page.get( "limit" ) );
        assertEquals( true, page.get( "hasMore" ) );
    }

    @Test
    void paginateUsesDefaultLimitAndClampsNegativeOffset() {
        final List< Integer > all = List.of( 1, 2, 3 );
        final Map< String, Object > args = new HashMap<>();
        args.put( "offset", -5 );

        final Map< String, Object > page = McpToolUtils.paginate( "items", all, args, 2 );

        assertEquals( 0, page.get( "offset" ) );
        assertEquals( 2, page.get( "limit" ) );
        assertEquals( List.of( 1, 2 ), page.get( "items" ) );
        assertEquals( true, page.get( "hasMore" ) );
    }

    @Test
    void paginateReportsNoMoreWhenOffsetBeyondEnd() {
        final List< Integer > all = List.of( 1, 2 );
        final Map< String, Object > args = new HashMap<>();
        args.put( "offset", 50 );

        final Map< String, Object > page = McpToolUtils.paginate( "items", all, args, 10 );

        assertTrue( ( ( List< ? > ) page.get( "items" ) ).isEmpty() );
        assertEquals( false, page.get( "hasMore" ) );
    }

    // --- stringOrNull / parseUuid / castStringKey -------------------------------------

    @Test
    void stringOrNullDelegatesToToString() {
        assertNull( McpToolUtils.stringOrNull( null ) );
        assertEquals( "42", McpToolUtils.stringOrNull( 42 ) );
    }

    @Test
    void parseUuidParsesValidUuidRegardlessOfSourceType() {
        final UUID id = UUID.randomUUID();
        assertEquals( id, McpToolUtils.parseUuid( id.toString() ) );
    }

    @Test
    void parseUuidReturnsNullOnNullOrInvalid() {
        assertNull( McpToolUtils.parseUuid( null ) );
        assertNull( McpToolUtils.parseUuid( "not-a-uuid" ) );
    }

    @Test
    void castStringKeyReturnsSameMapInstance() {
        final Map< String, Object > src = Map.of( "a", "b" );
        assertEquals( src, McpToolUtils.castStringKey( src ) );
    }

    // --- runBulk -----------------------------------------------------------------------

    @Test
    void runBulkRejectsNonListOperations() {
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", "not-a-list", 10, "author", op -> Map.of() );
        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "non-empty array" ) );
    }

    @Test
    void runBulkRejectsEmptyList() {
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", List.of(), 10, "author", op -> Map.of() );
        assertTrue( result.isError() );
    }

    @Test
    void runBulkRejectsOverBulkLimit() {
        final List< Object > ops = new ArrayList<>();
        ops.add( Map.of( "tag", "t1" ) );
        ops.add( Map.of( "tag", "t2" ) );
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", ops, 1, "author", op -> Map.of() );
        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "bulk limit exceeded" ) );
    }

    @Test
    void runBulkSkipsNonMapOperationEntries() {
        final List< Object > ops = List.of( "not-a-map" );
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", ops, 10, "author", op -> Map.of() );
        assertTrue( textOf( result ).contains( "operation must be an object" ) );
    }

    @Test
    void runBulkAggregatesSucceededAndFailedWithTagAndAction() {
        final List< Object > ops = List.of(
                Map.of( "tag", "t1", "action", "add", "ok", true ),
                Map.of( "tag", "t2", "action", "add", "ok", false ) );

        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", ops, 10, "author",
                op -> Boolean.TRUE.equals( op.get( "ok" ) )
                        ? Map.of( "id", "n1" )
                        : Map.of( "error", "failed" ) );

        assertFalse( result.isError() );
        final String text = textOf( result );
        assertTrue( text.contains( "\"status\":\"completed\"" ) );
        assertTrue( text.contains( "\"tag\":\"t1\"" ) );
        assertTrue( text.contains( "\"tag\":\"t2\"" ) );
        assertTrue( text.contains( "1 of 2 edge operations applied" ) );
    }

    @Test
    void runBulkSetsIsErrorWhenAllFailedAndFlagIsTrue() {
        final List< Object > ops = List.of( Map.of( "tag", "t1" ) );
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "edge", ops, 10, "author", op -> Map.of( "error", "nope" ) );
        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "\"status\":\"failed\"" ) );
    }

    @Test
    void runBulkAllFailedDoesNotSetIsErrorWhenFlagFalse() {
        final List< Object > ops = List.of( Map.of( "tag", "t1" ) );
        final McpSchema.CallToolResult result = McpToolUtils.runBulk(
                "tool", "node", ops, 10, "author", op -> Map.of( "error", "nope" ), false );
        assertFalse( result.isError() );
    }

    // --- parseProvenanceFilter ----------------------------------------------------------

    @Test
    void parseProvenanceFilterReturnsNullWhenAbsentOrEmpty() {
        assertNull( McpToolUtils.parseProvenanceFilter( Map.of() ) );
        assertNull( McpToolUtils.parseProvenanceFilter( Map.of( "provenance_filter", List.of() ) ) );
        assertNull( McpToolUtils.parseProvenanceFilter( Map.of( "provenance_filter", "not-a-list" ) ) );
    }

    @Test
    void parseProvenanceFilterParsesKnownValuesAndSkipsUnknown() {
        final Set< Provenance > result = McpToolUtils.parseProvenanceFilter(
                Map.of( "provenance_filter", List.of( "human-authored", "bogus-value" ) ) );
        assertEquals( Set.of( Provenance.HUMAN_AUTHORED ), result );
    }

    @Test
    void parseProvenanceFilterReturnsNullWhenAllValuesUnknown() {
        assertNull( McpToolUtils.parseProvenanceFilter( Map.of( "provenance_filter", List.of( "bogus" ) ) ) );
    }

    // --- checkForSerializedResponse -------------------------------------------------------

    @Test
    void checkForSerializedResponseReturnsNullForPlainMarkdown() {
        assertNull( McpToolUtils.checkForSerializedResponse( "# Just a heading\n\nSome body text." ) );
    }

    @Test
    void checkForSerializedResponseReturnsNullForShortContent() {
        assertNull( McpToolUtils.checkForSerializedResponse( "{\"exists\":true}" ) );
    }

    @Test
    void checkForSerializedResponseDetectsReadPageJsonEcho() {
        final String fakeJson = "{\"exists\":true,\"pageName\":\"Foo\",\"content\":\"body text here\"}";
        final McpSchema.CallToolResult result = McpToolUtils.checkForSerializedResponse( fakeJson );
        assertTrue( result != null && result.isError() );
        assertTrue( textOf( result ).contains( "serialized JSON response" ) );
    }

    @Test
    void checkForSerializedResponseReturnsNullForNullContent() {
        assertNull( McpToolUtils.checkForSerializedResponse( null ) );
    }

    // --- computeContentHash ----------------------------------------------------------------

    @Test
    void computeContentHashIsDeterministicSha256() {
        assertEquals( "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                McpToolUtils.computeContentHash( "" ) );
        assertEquals( McpToolUtils.computeContentHash( "hello" ), McpToolUtils.computeContentHash( "hello" ) );
    }

    // --- checkVersionOrHash ----------------------------------------------------------------

    @Test
    void checkVersionOrHashReturnsNullWhenNoExpectationsGiven() {
        final PageManager pm = mock( PageManager.class );
        assertNull( McpToolUtils.checkVersionOrHash( pm, "Page", 0, null, McpToolUtils.SHARED_GSON ) );
    }

    @Test
    void checkVersionOrHashReturnsNullWhenPageDoesNotExist() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( "New" ) ).thenReturn( null );
        assertNull( McpToolUtils.checkVersionOrHash( pm, "New", 3, null, McpToolUtils.SHARED_GSON ) );
    }

    @Test
    void checkVersionOrHashDetectsVersionConflict() {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 5 );
        when( pm.getPage( "Existing" ) ).thenReturn( page );

        final McpSchema.CallToolResult result =
                McpToolUtils.checkVersionOrHash( pm, "Existing", 3, null, McpToolUtils.SHARED_GSON );

        assertTrue( result != null && result.isError() );
        assertTrue( textOf( result ).contains( "Version conflict" ) );
    }

    @Test
    void checkVersionOrHashPassesWhenVersionMatches() {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "Existing" ) ).thenReturn( page );

        assertNull( McpToolUtils.checkVersionOrHash( pm, "Existing", 3, null, McpToolUtils.SHARED_GSON ) );
    }

    @Test
    void checkVersionOrHashDetectsContentHashConflict() {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( pm.getPage( "Existing" ) ).thenReturn( page );
        when( pm.getPureText( eq( "Existing" ), anyInt() ) ).thenReturn( "current text" );

        final String staleHash = McpToolUtils.computeContentHash( "stale text" );
        final McpSchema.CallToolResult result =
                McpToolUtils.checkVersionOrHash( pm, "Existing", 0, staleHash, McpToolUtils.SHARED_GSON );

        assertTrue( result != null && result.isError() );
        assertTrue( textOf( result ).contains( "Content hash conflict" ) );
    }

    @Test
    void checkVersionOrHashPassesWhenContentHashMatches() {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( pm.getPage( "Existing" ) ).thenReturn( page );
        when( pm.getPureText( eq( "Existing" ), anyInt() ) ).thenReturn( "current text" );

        final String matchingHash = McpToolUtils.computeContentHash( "current text" );
        assertNull( McpToolUtils.checkVersionOrHash( pm, "Existing", 0, matchingHash, McpToolUtils.SHARED_GSON ) );
    }
}
