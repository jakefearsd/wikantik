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
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.mcp.tools.*;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.managers.ReferenceManager;

import java.util.List;

/**
 * Factory that creates and categorises all MCP tools based on their registration
 * requirements. Tools fall into three groups:
 *
 * <ul>
 *   <li><strong>Read-only tools</strong> ({@link #readOnlyTools()}) &mdash; need no per-request
 *       author or user resolution; they are registered with a plain exchange handler.</li>
 *   <li><strong>Author-configurable tools</strong> ({@link #authorConfigurableTools()}) &mdash;
 *       implement {@link AuthorConfigurable} and require the MCP client name to be
 *       injected as the default author before each invocation.</li>
 *   <li><strong>User-configurable tool</strong> ({@link #lockPageTool()}) &mdash; the
 *       {@link LockPageTool} requires a different kind of user resolution (lock owner)
 *       and is therefore handled separately.</li>
 * </ul>
 *
 * <p>This separation keeps {@link McpServerInitializer} focused on server setup and
 * registration while this class owns tool instantiation and categorisation.</p>
 */
public class McpToolRegistry {

    private final List< McpTool > readOnly;
    private final List< McpTool > authorConfigurable;
    private final LockPageTool lockPage;

    /**
     * Creates every MCP tool, deriving all required managers from the given engine.
     *
     * @param engine the wiki engine used to obtain managers and configuration
     */
    public McpToolRegistry( final Engine engine ) {
        final PageManager pageManager = engine.getManager( PageManager.class );
        final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
        final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );
        final SystemPageRegistry systemPageRegistry = engine.getManager( SystemPageRegistry.class );
        final DifferenceManager differenceManager = engine.getManager( DifferenceManager.class );
        final PageRenamer pageRenamer = engine.getManager( PageRenamer.class );
        final PageSaveHelper pageSaveHelper = new PageSaveHelper( engine );

        // --- Read-only tools (no author resolution needed) ---
        final ReadPageTool readPage = new ReadPageTool( pageManager, systemPageRegistry );
        final SearchPagesTool searchPages = new SearchPagesTool( engine );
        final ListPagesTool listPages = new ListPagesTool( pageManager, systemPageRegistry );
        final GetBacklinksTool getBacklinks = new GetBacklinksTool( referenceManager );
        final RecentChangesTool recentChanges = new RecentChangesTool( pageManager, systemPageRegistry );
        final GetAttachmentsTool getAttachments = new GetAttachmentsTool( pageManager, attachmentManager );
        final QueryMetadataTool queryMetadata = new QueryMetadataTool( pageManager );
        final DeletePageTool deletePage = new DeletePageTool( pageManager, systemPageRegistry );
        final GetPageHistoryTool getPageHistory = new GetPageHistoryTool( pageManager );
        final DiffPageTool diffPage = new DiffPageTool( engine, pageManager, differenceManager );
        final GetOutboundLinksTool getOutboundLinks = new GetOutboundLinksTool( referenceManager );
        final GetBrokenLinksTool getBrokenLinks = new GetBrokenLinksTool( referenceManager );
        final GetOrphanedPagesTool getOrphanedPages = new GetOrphanedPagesTool( referenceManager, systemPageRegistry );
        final GetWikiStatsTool getWikiStats = new GetWikiStatsTool( pageManager, referenceManager );
        final ListMetadataValuesTool listMetadataValues = new ListMetadataValuesTool( pageManager );
        final UnlockPageTool unlockPage = new UnlockPageTool( pageManager );
        final ReadAttachmentTool readAttachment = new ReadAttachmentTool( attachmentManager );
        final DeleteAttachmentTool deleteAttachment = new DeleteAttachmentTool( attachmentManager );
        final ScanMarkdownLinksTool scanMarkdownLinks = new ScanMarkdownLinksTool( pageManager );
        final VerifyPagesTool verifyPages = new VerifyPagesTool( pageManager, referenceManager );
        final PreviewStructuredDataTool previewStructuredData = new PreviewStructuredDataTool(
                pageManager, engine.getApplicationName(), engine.getBaseURL() );
        final String indexNowApiKey = engine.getWikiProperties().getProperty( "wikantik.indexnow.apiKey" );
        final PingSearchEnginesTool pingSearchEngines = new PingSearchEnginesTool(
                engine.getBaseURL(), indexNowApiKey, java.net.http.HttpClient.newHttpClient() );
        final GetClusterMapTool getClusterMap = new GetClusterMapTool( pageManager, systemPageRegistry );
        final AuditClusterTool auditCluster = new AuditClusterTool( pageManager, referenceManager );
        final AuditCrossClusterTool auditCrossCluster = new AuditCrossClusterTool( pageManager, referenceManager, systemPageRegistry );

        readOnly = List.of(
                readPage, searchPages, listPages, getBacklinks, recentChanges,
                getAttachments, queryMetadata, deletePage, getPageHistory, diffPage,
                getOutboundLinks, getBrokenLinks, getOrphanedPages, getWikiStats,
                listMetadataValues, unlockPage, readAttachment, deleteAttachment,
                scanMarkdownLinks, verifyPages, previewStructuredData, pingSearchEngines,
                getClusterMap, auditCluster, auditCrossCluster
        );

        // --- Author-configurable tools (need author resolution from MCP exchange) ---
        final WritePageTool writePage = new WritePageTool( pageSaveHelper, systemPageRegistry );
        final BatchWritePagesTool batchWrite = new BatchWritePagesTool( pageSaveHelper );
        final RenamePageTool renamePage = new RenamePageTool( engine, pageManager, pageRenamer, systemPageRegistry );
        final UploadAttachmentTool uploadAttachment = new UploadAttachmentTool( engine, pageManager, attachmentManager );
        final PatchPageTool patchPage = new PatchPageTool( pageSaveHelper, pageManager );
        final BatchPatchPagesTool batchPatchPages = new BatchPatchPagesTool( pageSaveHelper, pageManager );
        final UpdateMetadataTool updateMetadata = new UpdateMetadataTool( pageSaveHelper, pageManager );
        final BatchUpdateMetadataTool batchUpdateMetadata = new BatchUpdateMetadataTool( pageSaveHelper, pageManager );
        final ApplyAuditFixesTool applyAuditFixes = new ApplyAuditFixesTool( pageSaveHelper, pageManager );
        final PublishClusterTool publishCluster = new PublishClusterTool( pageSaveHelper, pageManager );
        final ExtendClusterTool extendCluster = new ExtendClusterTool( pageSaveHelper, pageManager );

        authorConfigurable = List.of(
                writePage, batchWrite, renamePage, uploadAttachment,
                patchPage, batchPatchPages, updateMetadata, batchUpdateMetadata,
                applyAuditFixes, publishCluster, extendCluster
        );

        // --- User-configurable tool (lock owner resolution) ---
        lockPage = new LockPageTool( pageManager );
    }

    /** Returns tools that need no per-request author or user resolution. */
    public List< McpTool > readOnlyTools() {
        return readOnly;
    }

    /** Returns tools that implement {@link AuthorConfigurable} and need author injection. */
    public List< McpTool > authorConfigurableTools() {
        return authorConfigurable;
    }

    /** Returns the {@link LockPageTool} which needs special user (lock-owner) resolution. */
    public LockPageTool lockPageTool() {
        return lockPage;
    }
}
