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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /* ---------- Phase 2: duplicate cluster declaration ---------- */

    private static Properties withDuplicateGate( final boolean on ) {
        final Properties p = enabled();
        p.setProperty( "wikantik.cluster_declaration.enforcement.enabled", String.valueOf( on ) );
        return p;
    }

    /** Wires the index so {@code cluster} is already declared by the named hub page. */
    private void clusterDeclaredBy( final String cluster, final String hubSlug, final String hubId ) {
        final PageDescriptor hub = new PageDescriptor( hubId, hubSlug, hubSlug, PageType.HUB,
                cluster, List.of(), null, Instant.now(), Optional.empty(), false );
        when( svc.getCluster( cluster ) ).thenReturn( Optional.of(
                new ClusterDetails( cluster, hub, List.of( hub ), Map.of(), Instant.now() ) ) );
    }

    private static String hubPage( final String canonicalId, final String cluster ) {
        return "---\ncanonical_id: " + canonicalId + "\ntype: hub\ncluster: " + cluster + "\n---\nBody.";
    }

    @Test
    void rejects_a_hub_declaring_a_cluster_another_page_already_declares() {
        clusterDeclaredBy( "machine-learning", "MLHub", "01HAA000000000000000000001" );
        final var filter = new StructuralSpinePageFilter( svc, n -> false, withDuplicateGate( true ) );

        final FrontmatterValidationException boom = assertThrows( FrontmatterValidationException.class,
                () -> filter.preSave( ctx, hubPage( "01HAA000000000000000000002", "machine-learning" ) ) );

        assertTrue( boom.getMessage().contains( "MLHub" ) || boom.toString().contains( "MLHub" ),
                    "the refusal must name the hub that already declares it: " + boom );
    }

    /**
     * The declaring hub must remain editable. Comparing only "is this cluster declared?"
     * would make every hub un-saveable the moment enforcement went live.
     */
    @Test
    void allows_the_declaring_hub_to_save_itself_again() throws Exception {
        clusterDeclaredBy( "machine-learning", "MyPage", "01HAA000000000000000000001" );
        final var filter = new StructuralSpinePageFilter( svc, n -> false, withDuplicateGate( true ) );

        assertNotNull( filter.preSave( ctx, hubPage( "01HAA000000000000000000001", "machine-learning" ) ) );
    }

    @Test
    void allows_a_non_hub_page_to_name_a_declared_cluster() throws Exception {
        clusterDeclaredBy( "machine-learning", "MLHub", "01HAA000000000000000000001" );
        final var filter = new StructuralSpinePageFilter( svc, n -> false, withDuplicateGate( true ) );

        assertNotNull( filter.preSave( ctx,
                "---\ncanonical_id: 01HAA000000000000000000009\ntype: article\ncluster: machine-learning\n---\nBody." ) );
    }

    /** Ships dark: the gate is off unless explicitly enabled, so Phase 2 can land before the flip. */
    @Test
    void duplicate_declaration_is_not_enforced_by_default() throws Exception {
        clusterDeclaredBy( "machine-learning", "MLHub", "01HAA000000000000000000001" );
        final var filter = new StructuralSpinePageFilter( svc, n -> false, enabled() );

        assertNotNull( filter.preSave( ctx, hubPage( "01HAA000000000000000000002", "machine-learning" ) ) );
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
        when( svc.resolveCanonicalIdFromSlug( anyString() ) ).thenReturn( Optional.empty() );
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
    void missing_canonical_id_reuses_slug_bound_id_instead_of_minting_new() throws Exception {
        // Regression: if the slug already has a canonical_id in the structural index,
        // honour it. Otherwise the DAO upsert blows up on the unique-slug constraint
        // because the on-disk frontmatter just won a fresh ULID for an already-bound slug.
        when( svc.resolveCanonicalIdFromSlug( "MyPage" ) )
                .thenReturn( Optional.of( "01KQ0P44GYQVKPXXFRY7TTW69C" ) );
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\ntitle: My Page\ntype: article\n---\nbody";
        final String out = f.preSave( ctx, input );
        assertTrue( out.contains( "canonical_id: 01KQ0P44GYQVKPXXFRY7TTW69C" ),
                "slug-bound canonical_id must be reused, not regenerated" );
    }

    @Test
    void existing_canonical_id_is_preserved() throws Exception {
        final var f = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String input = "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: X\n---\nbody";
        final String out = f.preSave( ctx, input );
        assertEquals( input, out );
    }

    @Test
    void rejects_invalid_kg_include_value() {
        final StructuralSpinePageFilter filter = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String content =
            "---\n" +
            "canonical_id: 01HAA000000000000000000000\n" +
            "kg_include: maybe\n" +
            "---\n" +
            "body";
        final FilterException ex = assertThrows( FilterException.class,
                () -> filter.preSave( ctx, content ) );
        assertTrue( ex.getMessage().contains( "kg_include" ) );
    }

    @Test
    void accepts_kg_include_true_or_false() throws Exception {
        final StructuralSpinePageFilter filter = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String contentTrue =
            "---\ncanonical_id: 01HAA000000000000000000000\nkg_include: true\n---\nbody";
        final String contentFalse =
            "---\ncanonical_id: 01HAA000000000000000000000\nkg_include: false\n---\nbody";
        assertEquals( contentTrue,  filter.preSave( ctx, contentTrue ) );
        assertEquals( contentFalse, filter.preSave( ctx, contentFalse ) );
    }

    @Test
    void absent_kg_include_is_fine() throws Exception {
        final StructuralSpinePageFilter filter = new StructuralSpinePageFilter( svc, name -> false, enabled() );
        final String content =
            "---\ncanonical_id: 01HAA000000000000000000000\n---\nbody";
        assertEquals( content, filter.preSave( ctx, content ) );
    }
}
