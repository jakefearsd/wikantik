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
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.references.ReferenceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that returns a quick dashboard overview of wiki health:
 * total pages, broken links, orphaned pages, and recent changes count.
 */
public class GetWikiStatsTool {

    public static final String TOOL_NAME = "get_wiki_stats";

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;
    private final Gson gson = new Gson();

    public GetWikiStatsTool( final PageManager pageManager, final ReferenceManager referenceManager ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
    }

    public McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get a quick overview of wiki health and size. " +
                        "Returns {totalPages, brokenLinkCount, orphanedPageCount, recentChangesCount}. " +
                        "Use this as a starting point before diving into details with " +
                        "get_broken_links or get_orphaned_pages." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "totalPages", pageManager.getTotalPageCount() );
        result.put( "brokenLinkCount", referenceManager.findUncreated().size() );
        result.put( "orphanedPageCount", referenceManager.findUnreferenced().size() );
        result.put( "recentChangesCount", pageManager.getRecentChanges().size() );
        return McpToolUtils.jsonResult( gson, result );
    }
}
