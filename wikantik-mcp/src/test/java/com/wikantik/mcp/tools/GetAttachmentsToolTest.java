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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.attachment.DynamicAttachment;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.providers.AttachmentProvider;
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GetAttachmentsToolTest {

    private StubPageManager pm;
    private StubAttachmentManager attachmentManager;
    private GetAttachmentsTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        attachmentManager = new StubAttachmentManager();
        tool = new GetAttachmentsTool( pm, attachmentManager );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPageWithAttachments() {
        pm.savePage( "TestPage", "Content." );
        attachmentManager.addAttachment( "TestPage", "photo.png", 1024, new Date() );
        attachmentManager.addAttachment( "TestPage", "document.pdf", 2048, new Date() );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "TestPage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "exists" ) );
        assertEquals( "TestPage", data.get( "pageName" ) );

        final List< Map< String, Object > > attachments = ( List< Map< String, Object > > ) data.get( "attachments" );
        assertEquals( 2, attachments.size() );

        // Verify attachment fields
        final Map< String, Object > first = attachments.get( 0 );
        assertNotNull( first.get( "name" ) );
        assertNotNull( first.get( "size" ) );
        assertNotNull( first.get( "lastModified" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPageWithNoAttachments() {
        pm.savePage( "EmptyPage", "No attachments here." );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "EmptyPage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "exists" ) );
        assertEquals( "EmptyPage", data.get( "pageName" ) );

        final List< ? > attachments = ( List< ? > ) data.get( "attachments" );
        assertTrue( attachments.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNonexistentPageReturnsExistsFalse() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "NoSuchPage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( false, data.get( "exists" ) );
        assertEquals( "NoSuchPage", data.get( "pageName" ) );

        final List< ? > attachments = ( List< ? > ) data.get( "attachments" );
        assertTrue( attachments.isEmpty() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_attachments", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.description().contains( "attachment" ) );
        assertTrue( def.inputSchema().required().contains( "pageName" ) );
        assertTrue( def.annotations().readOnlyHint() );
    }

    @Test
    void testToolName() {
        assertEquals( "get_attachments", tool.name() );
    }

    /**
     * Minimal stub implementation of AttachmentManager for testing.
     */
    private static class StubAttachmentManager implements AttachmentManager {

        private final Map< String, List< Attachment > > attachments = new HashMap<>();

        void addAttachment( final String pageName, final String fileName, final long size, final Date lastModified ) {
            attachments.computeIfAbsent( pageName, k -> new ArrayList<>() )
                    .add( new StubAttachment( pageName, fileName, size, lastModified ) );
        }

        @Override
        public boolean attachmentsEnabled() { return true; }

        @Override
        public Attachment getAttachmentInfo( final Context context, final String attachmentname, final int version ) {
            return null;
        }

        @Override
        public String getAttachmentInfoName( final Context context, final String attachmentname ) {
            return null;
        }

        @Override
        public List< Attachment > listAttachments( final Page wikipage ) {
            return attachments.getOrDefault( wikipage.getName(), List.of() );
        }

        @Override
        public boolean forceDownload( final String name ) { return false; }

        @Override
        public InputStream getAttachmentStream( final Context ctx, final Attachment att ) { return null; }

        @Override
        public void storeDynamicAttachment( final Context ctx, final DynamicAttachment att ) { }

        @Override
        public DynamicAttachment getDynamicAttachment( final String name ) { return null; }

        @Override
        public void storeAttachment( final Attachment att, final InputStream in ) { }

        @Override
        public List< Attachment > getVersionHistory( final String attachmentName ) { return List.of(); }

        @Override
        public Collection< Attachment > getAllAttachments() { return List.of(); }

        @Override
        public Collection< Attachment > getAllAttachmentsSince( final Date since ) { return List.of(); }

        @Override
        public AttachmentProvider getCurrentProvider() { return null; }

        @Override
        public void deleteVersion( final Attachment att ) { }

        @Override
        public void deleteAttachment( final Attachment att ) { }
    }

    /**
     * Minimal Attachment implementation for testing.
     */
    private static class StubAttachment implements Attachment {
        private final String parentName;
        private final String fileName;
        private final long size;
        private final Date lastModified;

        StubAttachment( final String parentName, final String fileName, final long size, final Date lastModified ) {
            this.parentName = parentName;
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
        }

        @Override public String getFileName() { return fileName; }
        @Override public void setFileName( final String name ) { }
        @Override public String getParentName() { return parentName; }
        @Override public boolean isCacheable() { return true; }
        @Override public void setCacheable( final boolean value ) { }
        @Override public String getName() { return parentName + "/" + fileName; }
        @Override public String getWiki() { return "test"; }
        @Override public Date getLastModified() { return lastModified; }
        @Override public void setLastModified( final Date date ) { }
        @Override public int getVersion() { return 1; }
        @Override public void setVersion( final int version ) { }
        @Override public long getSize() { return size; }
        @Override public void setSize( final long size ) { }
        @Override public String getAuthor() { return null; }
        @Override public void setAuthor( final String author ) { }
        @Override public void invalidateMetadata() { }
        @Override public boolean hasMetadata() { return false; }
        @Override public void setHasMetadata() { }
        @Override @SuppressWarnings( "unchecked" ) public < T > T getAttribute( final String key ) { return null; }
        @Override public void setAttribute( final String key, final Object attribute ) { }
        @Override public Map< String, Object > getAttributes() { return Map.of(); }
        @Override @SuppressWarnings( "unchecked" ) public < T > T removeAttribute( final String key ) { return null; }
        @Override public com.wikantik.api.core.Acl getAcl() { return null; }
        @Override public void setAcl( final com.wikantik.api.core.Acl acl ) { }
        @Override public Page clone() { return this; }
        @Override public int compareTo( final Page o ) { return getName().compareTo( o.getName() ); }
    }
}
