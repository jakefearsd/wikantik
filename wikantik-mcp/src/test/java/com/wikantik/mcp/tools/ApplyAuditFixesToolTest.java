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
import com.wikantik.TestEngine;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApplyAuditFixesToolTest {

    private TestEngine engine;
    private ApplyAuditFixesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new ApplyAuditFixesTool( engine, engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSetMetadata() throws Exception {
        engine.saveText( "FixMeta1", "---\ntype: article\n---\nBody content." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixMeta1", "action", "set_metadata", "field", "status", "value", "active" )
        ) );
        args.put( "author", "AuditBot" );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertNull( results.get( 0 ).get( "previousValue" ) );

        // Verify metadata was applied
        final String stored = engine.getManager( PageManager.class ).getPureText( "FixMeta1", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "active", parsed.metadata().get( "status" ) );
        assertTrue( stored.contains( "Body content." ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSetMetadataPreviousValue() throws Exception {
        engine.saveText( "FixMetaPrev", "---\ntype: article\nstatus: draft\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixMetaPrev", "action", "set_metadata", "field", "status", "value", "active" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( "draft", results.get( 0 ).get( "previousValue" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAddHubBacklinkWithExistingSeeAlso() throws Exception {
        engine.saveText( "FixBacklink1", "---\ntype: article\ncluster: ai\n---\n# Article\n\nContent here.\n\n## See Also\n\n- [OtherPage](OtherPage)" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixBacklink1", "action", "add_hub_backlink", "hubPage", "AiHub" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "FixBacklink1", -1 );
        assertTrue( stored.contains( "[AiHub](AiHub)" ) );
        assertTrue( stored.contains( "[OtherPage](OtherPage)" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAddHubBacklinkWithoutSeeAlso() throws Exception {
        engine.saveText( "FixBacklink2", "---\ntype: article\ncluster: ai\n---\n# Article\n\nContent here." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixBacklink2", "action", "add_hub_backlink", "hubPage", "AiHub" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "FixBacklink2", -1 );
        assertTrue( stored.contains( "## See Also" ) );
        assertTrue( stored.contains( "[AiHub](AiHub)" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFixTypoLink() throws Exception {
        engine.saveText( "FixTypo1", "---\ntype: article\n---\nSee [MachinLearning](MachinLearning) for details." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixTypo1", "action", "fix_typo_link",
                        "brokenLink", "MachinLearning", "correctedLink", "MachineLearning" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "FixTypo1", -1 );
        assertTrue( stored.contains( "(MachineLearning)" ) );
        assertFalse( stored.contains( "(MachinLearning)" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPartialFailure() throws Exception {
        engine.saveText( "FixPartial1", "---\ntype: article\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixPartial1", "action", "set_metadata", "field", "status", "value", "active" ),
                Map.of( "page", "NonExistentPage", "action", "set_metadata", "field", "status", "value", "active" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( false, results.get( 1 ).get( "success" ) );
        assertTrue( results.get( 1 ).get( "detail" ).toString().contains( "not found" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testIdempotentHubBacklink() throws Exception {
        engine.saveText( "FixIdem1", "---\ntype: article\n---\n# Article\n\n## See Also\n\n- [AiHub](AiHub)" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of(
                Map.of( "page", "FixIdem1", "action", "add_hub_backlink", "hubPage", "AiHub" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertTrue( results.get( 0 ).get( "detail" ).toString().contains( "already exists" ) );
    }

    @Test
    void testEmptyFixesFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "fixes", List.of() );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "apply_audit_fixes", def.name() );
        assertNotNull( def.description() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
