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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Wire-level coverage for the admin-bypass on read paths. Seeds one node
 * on an excluded source page and one on an allowed source page; asserts
 * the admin-server {@code query_nodes} / {@code search_knowledge} see both,
 * demonstrating the admin-bypass shipped in
 * docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md §Fix 1.
 *
 * <p>The symmetric agent-facing assertion (that /knowledge-mcp does NOT
 * see the excluded node) is covered at the unit layer by
 * KgNodeRepositoryBypassTest; the IT scaffold here is wired to
 * /wikantik-admin-mcp only.</p>
 */
public class KgCurationVisibilityIT extends WithMcpTestSetup {

    @Test
    public void adminMcpQueryNodesSeesEntitiesOnExcludedPages() {
        final Map< String, Object > r = mcp.callTool( "query_nodes",
                Map.of( "filters",
                        Map.of( "name", WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ),
                        "limit", 10 ) );
        final String body = r.toString();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ),
                "Admin query_nodes must see entity on excluded page: " + body );
    }

    @Test
    public void adminMcpSearchKnowledgeSeesEntitiesOnExcludedPages() {
        final Map< String, Object > r = mcp.callTool( "search_knowledge",
                Map.of( "query", "KgVisibilityExclude", "limit", 10 ) );
        final String body = r.toString();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ),
                "Admin search_knowledge must see entity on excluded page: " + body );
    }

    @Test
    public void adminMcpQueryNodesSeesAllowedEntities() {
        final Map< String, Object > r = mcp.callTool( "query_nodes",
                Map.of( "filters",
                        Map.of( "name", WithMcpTestSetup.SEEDED_VISIBILITY_ALLOWED_NAME ),
                        "limit", 10 ) );
        final String body = r.toString();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_ALLOWED_NAME ), body );
    }
}
