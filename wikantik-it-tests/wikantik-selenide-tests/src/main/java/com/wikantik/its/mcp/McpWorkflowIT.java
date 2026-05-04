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

import java.util.List;
import java.util.Map;

/**
 * Multi-tool workflow integration tests simulating real AI agent usage patterns
 * against {@code /wikantik-admin-mcp} (the write surface). Read-side workflows
 * (search/get) are covered by the {@code /knowledge-mcp} tool unit tests in the
 * {@code wikantik-knowledge} module.
 */
public class McpWorkflowIT extends WithMcpTestSetup {

    @Test
    public void writeCreateLinkThenCheckBacklinks() {
        final String target = uniquePageName( "WFTarget" );
        final String source = uniquePageName( "WFSource" );

        mcp.importPage( target, "Target page content" );
        mcp.importPage( source, "Source links to [" + target + "](" + target + ") here" );

        final Map< String, Object > backlinks = mcp.getBacklinks( target );
        @SuppressWarnings( "unchecked" )
        final List< String > links = ( List< String > ) backlinks.get( "backlinks" );
        Assertions.assertTrue( links.contains( source ),
                "Source page should appear as a backlink for target" );
    }
}
