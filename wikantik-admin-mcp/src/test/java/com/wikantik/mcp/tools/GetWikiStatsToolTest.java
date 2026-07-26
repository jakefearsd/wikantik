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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubReferenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class GetWikiStatsToolTest {

    private StubPageManager pm;
    private StubReferenceManager refMgr;
    private GetWikiStatsTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        refMgr = new StubReferenceManager();
        tool = new GetWikiStatsTool( pm, refMgr );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testReturnsAllStatFields() throws Exception {
        pm.savePage( "StatsTestPage", "Some content [BrokenLink]" );
        refMgr.addReferences( "StatsTestPage", Set.of( "BrokenLink" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertNotNull( data.get( "totalPages" ) );
        assertNotNull( data.get( "brokenLinkCount" ) );
        assertNotNull( data.get( "orphanedPageCount" ) );
        assertNotNull( data.get( "recentChangesCount" ) );
        assertTrue( ( ( Number ) data.get( "totalPages" ) ).intValue() >= 1 );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_wiki_stats", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }

    /**
     * execute() had NO outer try/catch: an unexpected RuntimeException from a manager
     * call used to propagate straight out of the tool instead of coming back as the
     * structured {error, suggestion} envelope every other admin-mcp tool produces.
     */
    @Test
    void executeReturnsErrorEnvelopeOnUnexpectedException() {
        final PageManager mockPm = Mockito.mock( PageManager.class );
        final ReferenceManager mockRefMgr = Mockito.mock( ReferenceManager.class );
        when( mockRefMgr.findUncreated() ).thenThrow( new RuntimeException( "DB offline" ) );
        final GetWikiStatsTool failingTool = new GetWikiStatsTool( mockPm, mockRefMgr );

        final McpSchema.CallToolResult result = failingTool.execute( Map.of() );

        assertEquals( Boolean.TRUE, result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "DB offline" ), "expected error envelope to surface the failure message: " + json );
    }
}
