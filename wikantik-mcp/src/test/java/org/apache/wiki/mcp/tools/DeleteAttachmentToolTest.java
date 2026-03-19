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
import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.attachment.AttachmentManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeleteAttachmentToolTest {

    private TestEngine engine;
    private DeleteAttachmentTool tool;
    private AttachmentManager attachmentManager;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        attachmentManager = engine.getManager( AttachmentManager.class );
        tool = new DeleteAttachmentTool( attachmentManager );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDeleteAttachmentSucceeds() throws Exception {
        engine.saveText( "DelAttPage", "Content" );
        final Attachment att = Wiki.contents().attachment( engine, "DelAttPage", "todelete.txt" );
        att.setAuthor( "TestUser" );
        attachmentManager.storeAttachment( att, new ByteArrayInputStream( "data".getBytes() ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "DelAttPage" );
        args.put( "fileName", "todelete.txt" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertNull( attachmentManager.getAttachmentInfo( "DelAttPage/todelete.txt" ) );
    }

    @Test
    void testDeleteWithoutConfirmFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "SomePage" );
        args.put( "fileName", "file.txt" );
        args.put( "confirm", false );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testDeleteNonexistentAttachmentFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NoPage" );
        args.put( "fileName", "nofile.txt" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "delete_attachment", def.name() );
        assertTrue( def.annotations().destructiveHint() );
    }
}
