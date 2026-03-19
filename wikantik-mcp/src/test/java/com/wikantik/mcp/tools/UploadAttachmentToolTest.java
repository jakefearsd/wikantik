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
import com.wikantik.TestEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UploadAttachmentToolTest {

    private TestEngine engine;
    private UploadAttachmentTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new UploadAttachmentTool( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testUploadSucceeds() throws Exception {
        engine.saveText( "AttachPage", "Page for attachments" );

        final String content = Base64.getEncoder().encodeToString( "Hello World".getBytes() );
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "AttachPage" );
        args.put( "fileName", "test.txt" );
        args.put( "content", content );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertEquals( "AttachPage", data.get( "pageName" ) );
        assertEquals( "test.txt", data.get( "fileName" ) );
        assertEquals( 11.0, ( ( Number ) data.get( "size" ) ).doubleValue() );
    }

    @Test
    void testUploadToNonexistentPageFails() {
        final String content = Base64.getEncoder().encodeToString( "data".getBytes() );
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NoSuchPage" );
        args.put( "fileName", "file.txt" );
        args.put( "content", content );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testUploadInvalidBase64Fails() throws Exception {
        engine.saveText( "AttachPage2", "Content" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "AttachPage2" );
        args.put( "fileName", "file.txt" );
        args.put( "content", "not-valid-base64!!!" );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "upload_attachment", def.name() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
