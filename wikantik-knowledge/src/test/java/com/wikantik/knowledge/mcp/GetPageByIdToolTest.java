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
import com.wikantik.api.structure.StructuralIndexService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageByIdToolTest {

    @Test
    void returns_descriptor_for_known_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(), "s", Instant.EPOCH ) ) );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "01A" ) );
        assertFalse( result.isError() );
    }

    @Test
    void returns_error_for_missing_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "missing" ) );
        assertTrue( result.isError() );
    }

    @Test
    void requires_canonical_id_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final var result = new GetPageByIdTool( svc ).execute( Map.of() );
        assertTrue( result.isError() );
    }
}
