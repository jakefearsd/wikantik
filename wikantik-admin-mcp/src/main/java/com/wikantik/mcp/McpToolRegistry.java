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
package com.wikantik.mcp;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.content.PageRenamer;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.mcp.tools.*;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.managers.ReferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory that creates and categorises all MCP tools based on their registration
 * requirements. Tools fall into two groups:
 *
 * <ul>
 *   <li><strong>Read-only tools</strong> ({@link #readOnlyTools()}) &mdash; need no per-request
 *       author resolution; they are registered with a plain exchange handler.</li>
 *   <li><strong>Author-configurable tools</strong> ({@link #authorConfigurableTools()}) &mdash;
 *       implement {@link AuthorConfigurable} and require the MCP client name to be
 *       injected as the default author before each invocation.</li>
 * </ul>
 *
 * <p>Content editing is handled by {@code write_pages} (create/replace) and
 * {@code update_page} (patch), both of which are author-configurable.</p>
 */
public class McpToolRegistry {

    private final List< McpTool > readOnly;
    private final List< McpTool > authorConfigurable;

    /**
     * Creates every MCP tool, deriving all required managers from the given engine.
     *
     * @param engine the wiki engine used to obtain managers and configuration
     */
    public McpToolRegistry( final Engine engine ) {
        final PageManager pageManager = engine.getManager( PageManager.class );
        final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
        final SystemPageRegistry systemPageRegistry = engine.getManager( SystemPageRegistry.class );
        final DifferenceManager differenceManager = engine.getManager( DifferenceManager.class );
        final PageRenamer pageRenamer = engine.getManager( PageRenamer.class );
        final PageSaveHelper pageSaveHelper = new PageSaveHelper( engine );

        // --- Read-only tools (no author resolution needed) ---
        final GetBacklinksTool getBacklinks = new GetBacklinksTool( referenceManager );
        final GetPageHistoryTool getPageHistory = new GetPageHistoryTool( pageManager );
        final DiffPageTool diffPage = new DiffPageTool( engine, pageManager, differenceManager );
        final GetOutboundLinksTool getOutboundLinks = new GetOutboundLinksTool( referenceManager );
        final GetBrokenLinksTool getBrokenLinks = new GetBrokenLinksTool( referenceManager );
        final GetOrphanedPagesTool getOrphanedPages = new GetOrphanedPagesTool( referenceManager, systemPageRegistry );
        final GetWikiStatsTool getWikiStats = new GetWikiStatsTool( pageManager, referenceManager );
        final VerifyPagesTool verifyPages = new VerifyPagesTool( pageManager, referenceManager );
        final PreviewStructuredDataTool previewStructuredData = new PreviewStructuredDataTool(
                pageManager, engine.getApplicationName(), engine.getBaseURL() );
        final String indexNowApiKey = engine.getWikiProperties().getProperty( "wikantik.indexnow.apiKey" );
        final PingSearchEnginesTool pingSearchEngines = new PingSearchEnginesTool(
                engine.getBaseURL(), indexNowApiKey, java.net.http.HttpClient.newHttpClient() );

        final List< McpTool > readOnlyList = new ArrayList<>( List.of(
                getBacklinks, getPageHistory, diffPage,
                getOutboundLinks, getBrokenLinks, getOrphanedPages, getWikiStats,
                verifyPages, previewStructuredData, pingSearchEngines
        ) );

        // --- Author-configurable tools (need author resolution from MCP exchange) ---
        final RenamePageTool renamePage = new RenamePageTool( engine, pageManager, pageRenamer, systemPageRegistry );
        final WritePagesTool writePages = new WritePagesTool( pageSaveHelper, pageManager );
        final UpdatePageTool updatePage = new UpdatePageTool( pageSaveHelper, pageManager );

        final List< McpTool > authorConfigurableList = new ArrayList<>( List.of(
                renamePage, writePages, updatePage
        ) );

        // --- Knowledge proposal tools (only if KnowledgeGraphService is available) ---
        final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
        if ( kgService != null ) {
            readOnlyList.add( new ListProposalsTool( kgService ) );
            authorConfigurableList.add( new ProposeKnowledgeTool( kgService ) );
        }

        readOnly = List.copyOf( readOnlyList );
        authorConfigurable = List.copyOf( authorConfigurableList );
    }

    /** Returns tools that need no per-request author resolution. */
    public List< McpTool > readOnlyTools() {
        return readOnly;
    }

    /** Returns tools that implement {@link AuthorConfigurable} and need author injection. */
    public List< McpTool > authorConfigurableTools() {
        return authorConfigurable;
    }
}
