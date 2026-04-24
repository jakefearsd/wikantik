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

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListPagesByFilterToolTest {

    @Test
    void forwards_filter_arguments_to_service() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of() );
        final ArgumentCaptor< StructuralFilter > cap = ArgumentCaptor.forClass( StructuralFilter.class );

        new ListPagesByFilterTool( svc ).execute( Map.of(
                "type", "article",
                "cluster", "wikantik-development",
                "tag", List.of( "retrieval" ),
                "limit", 5 ) );

        verify( svc ).listPagesByFilter( cap.capture() );
        final StructuralFilter f = cap.getValue();
        assertEquals( PageType.ARTICLE,       f.type().orElseThrow() );
        assertEquals( "wikantik-development", f.cluster().orElseThrow() );
        assertEquals( List.of( "retrieval" ),  f.tags() );
        assertEquals( 5,                       f.limit() );
    }

    @Test
    void returns_pages_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(),
                        "summary", Instant.EPOCH ) ) );

        final var result = new ListPagesByFilterTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "\"slug\"" ) && content.text().contains( "X" ) );
    }
}
