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

import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListTagsToolTest {

    @Test
    void min_pages_defaults_to_1() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( 1 ) ).thenReturn( List.of( new TagSummary( "retrieval", 5, List.of() ) ) );
        final ListTagsTool tool = new ListTagsTool( svc );
        final var result = tool.execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "retrieval" ) );
        verify( svc ).listTags( 1 );
    }

    @Test
    void respects_min_pages_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( 5 ) ).thenReturn( List.of() );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", 5 ) );
        verify( svc ).listTags( 5 );
    }
}
