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

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolUtilsTest {

    private final Gson gson = new Gson();

    @Test
    void testJsonResultWrapsDataCorrectly() {
        final Map< String, String > data = Map.of( "key", "value" );
        final McpSchema.CallToolResult result = McpToolUtils.jsonResult( gson, data );

        assertNotNull( result.content() );
        assertEquals( 1, result.content().size() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "\"key\"" ) );
        assertTrue( json.contains( "\"value\"" ) );
        assertNotEquals( Boolean.TRUE, result.isError() );
    }

    @Test
    void testErrorResultSetsIsErrorFlag() {
        final McpSchema.CallToolResult result = McpToolUtils.errorResult( gson, "something broke" );

        assertEquals( Boolean.TRUE, result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "something broke" ) );
        assertTrue( json.contains( "\"error\"" ) );
    }

    @Test
    void testGetIntWithPresentValue() {
        final Map< String, Object > args = Map.of( "limit", 42 );
        assertEquals( 42, McpToolUtils.getInt( args, "limit", 10 ) );
    }

    @Test
    void testGetIntWithAbsentValueReturnsDefault() {
        final Map< String, Object > args = Map.of();
        assertEquals( 10, McpToolUtils.getInt( args, "limit", 10 ) );
    }

    @Test
    void testGetIntWithDoubleValue() {
        final Map< String, Object > args = Map.of( "limit", 42.0 );
        assertEquals( 42, McpToolUtils.getInt( args, "limit", 10 ) );
    }

    @Test
    void testGetBooleanWithTrue() {
        final Map< String, Object > args = Map.of( "flag", true );
        assertTrue( McpToolUtils.getBoolean( args, "flag" ) );
    }

    @Test
    void testGetBooleanWithFalse() {
        final Map< String, Object > args = Map.of( "flag", false );
        assertFalse( McpToolUtils.getBoolean( args, "flag" ) );
    }

    @Test
    void testGetBooleanWithAbsent() {
        final Map< String, Object > args = Map.of();
        assertFalse( McpToolUtils.getBoolean( args, "flag" ) );
    }

    @Test
    void testGetStringReturnsValue() {
        final Map< String, Object > args = Map.of( "name", "hello" );
        assertEquals( "hello", McpToolUtils.getString( args, "name" ) );
    }

    @Test
    void testGetStringReturnsNullWhenAbsent() {
        final Map< String, Object > args = new HashMap<>();
        assertNull( McpToolUtils.getString( args, "name" ) );
    }

    @Test
    void testGetStringWithDefaultReturnsDefault() {
        final Map< String, Object > args = new HashMap<>();
        assertEquals( "fallback", McpToolUtils.getString( args, "name", "fallback" ) );
    }

    @Test
    void testGetStringWithDefaultReturnsValueWhenPresent() {
        final Map< String, Object > args = Map.of( "name", "actual" );
        assertEquals( "actual", McpToolUtils.getString( args, "name", "fallback" ) );
    }

    @Test
    void testNormalizeVersionNegativeBecomesOne() {
        assertEquals( 1, McpToolUtils.normalizeVersion( -1 ) );
    }

    @Test
    void testNormalizeVersionZeroBecomesOne() {
        assertEquals( 1, McpToolUtils.normalizeVersion( 0 ) );
    }

    @Test
    void testNormalizeVersionPositiveUnchanged() {
        assertEquals( 5, McpToolUtils.normalizeVersion( 5 ) );
    }

    @Test
    void testErrorResultWithSuggestion() {
        final McpSchema.CallToolResult result = McpToolUtils.errorResult( gson, "not found", "use list_pages" );
        assertEquals( Boolean.TRUE, result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not found" ) );
        assertTrue( json.contains( "use list_pages" ) );
        assertTrue( json.contains( "\"suggestion\"" ) );
    }

    // --- checkForSerializedResponse tests ---

    @Test
    void testCheckForSerializedResponseDetectsFullReadPageResponse() {
        final String serialized = "{\"exists\":true,\"pageName\":\"Foo\",\"content\":\"# Hello\",\"metadata\":{},\"version\":1}";
        final McpSchema.CallToolResult result = McpToolUtils.checkForSerializedResponse( serialized );
        assertNotNull( result, "Should detect serialized response" );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "serialized JSON response" ) );
    }

    @Test
    void testCheckForSerializedResponseAllowsPlainMarkdown() {
        final String markdown = "# Hello World\n\nThis is a normal page with some content.";
        final McpSchema.CallToolResult result = McpToolUtils.checkForSerializedResponse( markdown );
        assertNull( result, "Normal markdown should not be flagged" );
    }

    @Test
    void testCheckForSerializedResponseAllowsNullContent() {
        assertNull( McpToolUtils.checkForSerializedResponse( null ) );
    }

    @Test
    void testCheckForSerializedResponseAllowsShortContent() {
        assertNull( McpToolUtils.checkForSerializedResponse( "short" ) );
    }

    @Test
    void testCheckForSerializedResponseAllowsJsonWithoutPageFields() {
        final String json = "{\"data\": [1, 2, 3], \"count\": 3, \"status\": \"ok\"}";
        assertNull( McpToolUtils.checkForSerializedResponse( json ) );
    }

    // --- computeContentHash tests ---

    @Test
    void testComputeContentHashConsistentResults() {
        final String content = "Hello, world!";
        final String hash1 = McpToolUtils.computeContentHash( content );
        final String hash2 = McpToolUtils.computeContentHash( content );
        assertEquals( hash1, hash2, "Same content should produce same hash" );
        assertEquals( 64, hash1.length(), "SHA-256 hash should be 64 hex characters" );
    }

    @Test
    void testComputeContentHashDifferentForDifferentContent() {
        final String hash1 = McpToolUtils.computeContentHash( "Content A" );
        final String hash2 = McpToolUtils.computeContentHash( "Content B" );
        assertNotEquals( hash1, hash2, "Different content should produce different hashes" );
    }

    // --- checkVersionOrHash tests ---

    @Test
    void testCheckVersionOrHashReturnsNullWhenNoConstraints() {
        final StubPageManager pm = new StubPageManager();
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "AnyPage", -1, null, gson );
        assertNull( result, "No constraints should return null (OK)" );
    }

    @Test
    void testCheckVersionOrHashReturnsNullForNewPage() {
        final StubPageManager pm = new StubPageManager();
        // Page doesn't exist — version constraint can't conflict
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "NewPage", 1, null, gson );
        assertNull( result, "New page should not cause version conflict" );
    }

    @Test
    void testCheckVersionOrHashDetectsVersionConflict() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "ExistingPage", "Content." );

        // Page is at version 1, but we expect version 99
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "ExistingPage", 99, null, gson );
        assertNotNull( result, "Should detect version conflict" );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Version conflict" ) );
    }

    @Test
    void testCheckVersionOrHashVersionMatchReturnsNull() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "VersionMatch", "Content." );

        // Page is at version 1, and we expect version 1
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "VersionMatch", 1, null, gson );
        assertNull( result, "Matching version should return null (OK)" );
    }

    @Test
    void testCheckVersionOrHashDetectsContentHashConflict() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "HashPage", "Original content." );

        final String wrongHash = "0000000000000000000000000000000000000000000000000000000000000000";
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "HashPage", -1, wrongHash, gson );
        assertNotNull( result, "Should detect content hash conflict" );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Content hash conflict" ) );
    }

    @Test
    void testCheckVersionOrHashMatchingHashReturnsNull() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "HashMatch", "Exact content." );

        final String correctHash = McpToolUtils.computeContentHash( pm.getPureText( "HashMatch", -1 ) );
        final McpSchema.CallToolResult result = McpToolUtils.checkVersionOrHash( pm, "HashMatch", -1, correctHash, gson );
        assertNull( result, "Matching hash should return null (OK)" );
    }

}
