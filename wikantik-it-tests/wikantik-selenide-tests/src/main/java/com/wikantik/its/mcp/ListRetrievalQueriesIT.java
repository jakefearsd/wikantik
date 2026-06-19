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
package com.wikantik.its.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wire-level Cargo IT for the {@code list_retrieval_queries} admin-MCP tool.
 *
 * <p>Proves end-to-end (Cargo Tomcat + real Postgres) that the tool is:</p>
 * <ol>
 *   <li>Registered and discoverable via {@code tools/list}.</li>
 *   <li>Callable via {@code tools/call}, returning a well-formed
 *       {@code {"queries": [...]}} envelope (the IT DB may have zero rows;
 *       we assert structure and absence of an error flag).</li>
 * </ol>
 */
public class ListRetrievalQueriesIT extends WithMcpTestSetup {

    // ------------------------------------------------------------------
    // 1. tools/list discovery
    // ------------------------------------------------------------------

    @Test
    public void listToolsContainsListRetrievalQueries() {
        final McpSchema.ListToolsResult result = mcp.listTools();
        final Set< String > toolNames = result.tools().stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toSet() );

        Assertions.assertTrue( toolNames.contains( "list_retrieval_queries" ),
                "tools/list must expose list_retrieval_queries; got: " + toolNames );
    }

    // ------------------------------------------------------------------
    // 2. tools/call — structure + non-error, tolerates empty DB
    // ------------------------------------------------------------------

    @Test
    public void callListRetrievalQueriesReturnsQueriesArray() {
        final Map< String, Object > result = mcp.callTool( "list_retrieval_queries",
                Map.of( "since_days", 3650, "limit", 5 ) );

        Assertions.assertNotNull( result,
                "list_retrieval_queries must return a non-null envelope" );

        // The payload must contain a 'queries' key whose value is a list.
        // In an empty IT DB the list may be empty; that is fine — we assert shape.
        final Object queries = result.get( "queries" );
        Assertions.assertNotNull( queries,
                "list_retrieval_queries payload must contain 'queries' key: " + result );
        Assertions.assertInstanceOf( List.class, queries,
                "'queries' value must be a JSON array: " + result );
    }
}
