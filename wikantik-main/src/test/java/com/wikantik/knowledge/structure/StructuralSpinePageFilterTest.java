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
package com.wikantik.knowledge.structure;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StructuralSpinePageFilterTest {

    private StructuralIndexService svc;
    private Context ctx;
    private Page page;

    @BeforeEach
    void setUp() {
        svc = mock( StructuralIndexService.class );
        ctx = mock( Context.class );
        page = mock( Page.class );
        when( page.getName() ).thenReturn( "MyPage" );
        when( ctx.getPage() ).thenReturn( page );
    }

    private static Properties enabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.structural_spine.enforcement.enabled", "true" );
        return p;
    }

    private static Properties disabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.structural_spine.enforcement.enabled", "false" );
        return p;
    }

    @Test
    void disabled_filter_returns_content_unchanged() throws Exception {
        final var f = new StructuralSpinePageFilter( svc, name -> false, disabled() );
        final String input = "no frontmatter here";
        assertEquals( input, f.preSave( ctx, input ) );
        verifyNoInteractions( svc );
    }

    @Test
    void system_pages_are_exempt() throws Exception {
        final var f = new StructuralSpinePageFilter( svc, name -> "MyPage".equals( name ), enabled() );
        final String input = "no frontmatter, would otherwise need a canonical_id";
        assertEquals( input, f.preSave( ctx, input ) );
        verifyNoInteractions( svc );
    }

    @Test
    void missing_canonical_id_gets_auto_assigned_and_rewritten() throws Exception {
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\ntitle: My Page\ntype: article\n---\nbody";
        final String out = f.preSave( ctx, input );
        assertTrue( out.contains( "canonical_id:" ), "filter should inject canonical_id" );
        assertTrue( out.startsWith( "---\ncanonical_id:" ),
                   "canonical_id should land as first frontmatter key" );
        assertTrue( out.contains( "title: My Page" ) );
        assertTrue( out.contains( "body" ) );
    }

    @Test
    void existing_canonical_id_is_preserved() throws Exception {
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: X\n---\nbody";
        final String out = f.preSave( ctx, input );
        // No relations declared → output equals input (no rewrite).
        assertEquals( input, out );
    }

    @Test
    void valid_relations_pass() throws Exception {
        when( svc.getByCanonicalId( "01TARGETXXXXXXXXXXXXXXXXXX" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01TARGETXXXXXXXXXXXXXXXXXX", "Target", "Target",
                        PageType.HUB, null, List.of(), "summary", Instant.EPOCH ) ) );
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\n" +
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: X\n" +
                "relations:\n" +
                "  - {type: part-of, target: 01TARGETXXXXXXXXXXXXXXXXXX}\n" +
                "---\nbody";
        assertDoesNotThrow( () -> f.preSave( ctx, input ) );
    }

    @Test
    void invalid_relation_target_throws_FilterException() {
        when( svc.getByCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\n" +
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: X\n" +
                "relations:\n" +
                "  - {type: part-of, target: 01GHOSTGHOSTGHOSTGHOSTGHOST}\n" +
                "---\nbody";
        final FilterException ex = assertThrows( FilterException.class,
                () -> f.preSave( ctx, input ) );
        assertTrue( ex.getMessage().contains( "TARGET_MISSING" ) );
    }

    @Test
    void unknown_relation_type_throws_FilterException() {
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\n" +
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: X\n" +
                "relations:\n" +
                "  - {type: is-a, target: 01ANYTHINGXXXXXXXXXXXXXXXX}\n" +
                "---\nbody";
        assertThrows( FilterException.class, () -> f.preSave( ctx, input ) );
    }
}
