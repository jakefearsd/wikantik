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

import com.wikantik.api.structure.RelationDirection;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TraversalSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TraverseRelationsToolTest {

    @Test
    void requires_from_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final var result = new TraverseRelationsTool( svc ).execute( Map.of() );
        assertTrue( result.isError() );
    }

    @Test
    void forwards_direction_type_filter_and_depth_to_service() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.traverse( any(), any() ) ).thenReturn( List.of() );
        final ArgumentCaptor< TraversalSpec > cap = ArgumentCaptor.forClass( TraversalSpec.class );

        new TraverseRelationsTool( svc ).execute( Map.of(
                "from", "01A",
                "direction", "in",
                "type_filter", "part-of",
                "depth_cap", 3 ) );

        verify( svc ).traverse( eq( "01A" ), cap.capture() );
        final TraversalSpec spec = cap.getValue();
        assertEquals( RelationDirection.IN, spec.direction() );
        assertEquals( Optional.of( RelationType.PART_OF ), spec.typeFilter() );
        assertEquals( 3, spec.depthCap() );
    }

    @Test
    void returns_edges_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.traverse( any(), any() ) ).thenReturn( List.of(
                new RelationEdge( "01A", "A", "01B", "B", "B", RelationType.PART_OF, 1 ) ) );
        final var result = new TraverseRelationsTool( svc ).execute( Map.of( "from", "01A" ) );
        assertFalse( result.isError() );
        final var content = ( io.modelcontextprotocol.spec.McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "\"count\"" ) );
        assertTrue( content.text().contains( "01B" ) );
    }
}
