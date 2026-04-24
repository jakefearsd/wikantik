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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListMetadataValuesToolTest {

    @Test
    void name_isListMetadataValues() {
        assertEquals( "list_metadata_values",
            new ListMetadataValuesTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_requiresField() {
        final McpSchema.Tool def = new ListMetadataValuesTool( mock( ContextRetrievalService.class ) ).definition();
        assertTrue( def.inputSchema().required().contains( "field" ) );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listMetadataValues( "cluster" ) ).thenReturn( List.of(
            new MetadataValue( "search", 14 ),
            new MetadataValue( "kg", 8 ) ) );

        final McpSchema.CallToolResult result =
            new ListMetadataValuesTool( svc ).execute( Map.of( "field", "cluster" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"field\":\"cluster\"" ) );
        assertTrue( text.contains( "\"value\":\"search\"" ) );
        assertTrue( text.contains( "\"count\":14" ) );
        assertTrue( text.contains( "\"value\":\"kg\"" ) );
    }

    @Test
    void execute_returnsErrorOnBlankField() {
        final ListMetadataValuesTool t = new ListMetadataValuesTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "field", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_returnsErrorOnRuntimeExceptionFromService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listMetadataValues( anyString() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result = new ListMetadataValuesTool( svc )
            .execute( Map.of( "field", "cluster" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
