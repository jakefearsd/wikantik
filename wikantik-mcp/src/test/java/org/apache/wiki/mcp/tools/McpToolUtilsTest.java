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
package org.apache.wiki.mcp.tools;

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
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

}
