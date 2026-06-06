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
package com.wikantik.mcp.resources;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.providers.PageProvider;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiResources}: drives each MCP resource/template read handler
 * with mocked managers and asserts the JSON payload it returns. Covers the page (present
 * + absent), attachments, backlinks, and all-pages handlers — the bulk of the class.
 */
class WikiResourcesTest {

    private PageManager pageManager;
    private ReferenceManager referenceManager;
    private AttachmentManager attachmentManager;
    private SystemPageRegistry systemPageRegistry;
    private WikiResources resources;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        referenceManager = mock( ReferenceManager.class );
        attachmentManager = mock( AttachmentManager.class );
        systemPageRegistry = mock( SystemPageRegistry.class );
        resources = new WikiResources( pageManager, referenceManager, attachmentManager, systemPageRegistry );
    }

    /** Reads a static resource's text payload by uri. */
    private String readStatic( final String uri ) {
        final McpServerFeatures.SyncResourceSpecification spec = resources.staticResources().stream()
                .filter( s -> s.resource().uri().equals( uri ) )
                .findFirst().orElseThrow();
        final McpSchema.ReadResourceResult result =
                spec.readHandler().apply( null, new McpSchema.ReadResourceRequest( uri ) );
        return ( (McpSchema.TextResourceContents) result.contents().get( 0 ) ).text();
    }

    /** Reads a templated resource's text payload by invoking the template whose name matches. */
    private String readTemplate( final String templateName, final String uri ) {
        final McpServerFeatures.SyncResourceTemplateSpecification spec = resources.resourceTemplates().stream()
                .filter( s -> s.resourceTemplate().name().equals( templateName ) )
                .findFirst().orElseThrow();
        final McpSchema.ReadResourceResult result =
                spec.readHandler().apply( null, new McpSchema.ReadResourceRequest( uri ) );
        return ( (McpSchema.TextResourceContents) result.contents().get( 0 ) ).text();
    }

    private static Page mockPage( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getAuthor() ).thenReturn( "alice" );
        when( p.getVersion() ).thenReturn( 3 );
        when( p.getLastModified() ).thenReturn( new Date( 0 ) );
        return p;
    }

    @Test
    void exposesFourTemplatesAndTwoStaticResources() {
        assertEquals( 4, resources.resourceTemplates().size() );
        assertEquals( 2, resources.staticResources().size() );
        assertTrue( resources.staticResources().stream()
                .anyMatch( s -> s.resource().uri().equals( "wiki://pages" ) ) );
    }

    @Test
    void readPageReturnsContentAndMetadataWhenPresent() {
        final Page main = mockPage( "Main" );
        when( pageManager.getPage( eq( "Main" ), anyInt() ) ).thenReturn( main );
        when( pageManager.getPureText( eq( "Main" ), anyInt() ) ).thenReturn( "Hello body" );

        final String json = readTemplate( "Wiki Page", "wiki://pages/Main" );
        assertTrue( json.contains( "\"exists\":true" ), json );
        assertTrue( json.contains( "Main" ), json );
        assertTrue( json.contains( "Hello body" ), json );
    }

    @Test
    void readPageReturnsExistsFalseWhenMissing() {
        when( pageManager.getPage( eq( "Ghost" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( null );

        final String json = readTemplate( "Wiki Page", "wiki://pages/Ghost" );
        assertTrue( json.contains( "\"exists\":false" ), json );
    }

    @Test
    void readBacklinksReturnsSortedReferrers() {
        when( referenceManager.findReferrers( "Target" ) ).thenReturn( Set.of( "Zeta", "Alpha" ) );

        final String json = readTemplate( "Page Backlinks", "wiki://pages/Target/backlinks" );
        // Sorted: Alpha before Zeta.
        assertTrue( json.indexOf( "Alpha" ) < json.indexOf( "Zeta" ), json );
    }

    @Test
    void readAttachmentsListsAttachmentsForPresentPage() throws Exception {
        final Page page = mockPage( "Docs" );
        when( pageManager.getPage( "Docs" ) ).thenReturn( page );
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "diagram.png" );
        when( att.getSize() ).thenReturn( 1234L );
        when( att.getLastModified() ).thenReturn( new Date( 0 ) );
        when( attachmentManager.listAttachments( page ) ).thenReturn( List.of( att ) );

        final String json = readTemplate( "Page Attachments", "wiki://pages/Docs/attachments" );
        assertTrue( json.contains( "\"exists\":true" ), json );
        assertTrue( json.contains( "diagram.png" ), json );
    }

    @Test
    void readAttachmentsReturnsExistsFalseWhenPageMissing() {
        when( pageManager.getPage( "Ghost" ) ).thenReturn( null );

        final String json = readTemplate( "Page Attachments", "wiki://pages/Ghost/attachments" );
        assertTrue( json.contains( "\"exists\":false" ), json );
    }

    @Test
    void listAllPagesReturnsSortedNames() throws Exception {
        final Page beta = mockPage( "Beta" );
        final Page alpha = mockPage( "Alpha" );
        when( pageManager.getAllPages() ).thenReturn( List.of( beta, alpha ) );
        when( systemPageRegistry.isSystemPage( any() ) ).thenReturn( false );

        final String json = readStatic( "wiki://pages" );
        assertTrue( json.contains( "Alpha" ) && json.contains( "Beta" ), json );
        assertTrue( json.indexOf( "Alpha" ) < json.indexOf( "Beta" ), json );
        assertFalse( json.contains( "\"error\"" ), json );
    }
}
