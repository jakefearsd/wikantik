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
package com.wikantik.kgpolicy;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StructuralIndexFrontmatterOverrideReaderTest {

    private static PageDescriptor pdWithKgInclude( final Optional< Boolean > kgInclude ) {
        return new PageDescriptor( "01HAA", "Foo", "Foo", PageType.ARTICLE,
                "java", List.of(), null, Instant.now(), kgInclude, false );
    }

    @Test
    void empty_when_page_does_not_resolve() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
        assertTrue( new StructuralIndexFrontmatterOverrideReader( svc ).kgInclude( "Foo" ).isEmpty() );
    }

    @Test
    void empty_when_field_absent() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.resolveCanonicalIdFromSlug( "Foo" ) ).thenReturn( Optional.of( "01HAA" ) );
        when( svc.getByCanonicalId( "01HAA" ) ).thenReturn( Optional.of( pdWithKgInclude( Optional.empty() ) ) );
        assertTrue( new StructuralIndexFrontmatterOverrideReader( svc ).kgInclude( "Foo" ).isEmpty() );
    }

    @Test
    void returns_value_when_present() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.resolveCanonicalIdFromSlug( "Foo" ) ).thenReturn( Optional.of( "01HAA" ) );
        when( svc.getByCanonicalId( "01HAA" ) ).thenReturn( Optional.of( pdWithKgInclude( Optional.of( false ) ) ) );
        assertEquals( Optional.of( false ),
                new StructuralIndexFrontmatterOverrideReader( svc ).kgInclude( "Foo" ) );
    }
}
