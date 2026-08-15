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
package com.wikantik.frontmatter.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaValidationPageFilterTest {

    private final SchemaValidationPageFilter filter = new SchemaValidationPageFilter(
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
            ValidationCtx.lenient(), true );

    @BeforeEach
    void clearSink() {
        FrontmatterWarningSink.clear();
    }

    @Test
    void malformedYamlThrowsWithYamlViolation() {
        final FrontmatterValidationException ex = assertThrows( FrontmatterValidationException.class,
                () -> filter.preSave( null, "---\ntags: [a, b\n---\n# body\n" ) );
        assertEquals( "__yaml__", ex.violations().get( 0 ).field() );
    }

    @Test
    void badAudienceThrowsValidationException() {
        final FrontmatterValidationException ex = assertThrows( FrontmatterValidationException.class,
                () -> filter.preSave( null, "---\ntype: article\naudience: robots\n---\n# body\n" ) );
        assertTrue( ex.violations().stream().anyMatch( v -> v.field().equals( "audience" ) ) );
    }

    @Test
    void nonCanonicalStatusDoesNotThrowAndStashesWarning() throws Exception {
        final String content = "---\nstatus: published\n---\n# body\n";
        assertSame( content, filter.preSave( null, content ) );
        final List< FieldViolation > warnings = FrontmatterWarningSink.drain( null );
        assertTrue( warnings.stream().anyMatch( v -> v.field().equals( "status" ) ),
                "non-canonical status should be stashed as a warning" );
    }

    @Test
    void cleanPagePassesThroughWithNoWarnings() throws Exception {
        final String content = "---\ntype: article\nstatus: active\n---\n# body\n";
        assertSame( content, filter.preSave( null, content ) );
        assertTrue( FrontmatterWarningSink.drain( null ).isEmpty() );
    }

    @Test
    void contentWithoutFrontmatterPassesThrough() throws Exception {
        final String content = "# just a body, no frontmatter\n";
        assertSame( content, filter.preSave( null, content ) );
    }

    @Test
    void disabledFilterIsNoOp() throws Exception {
        final SchemaValidationPageFilter disabled = new SchemaValidationPageFilter(
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                ValidationCtx.lenient(), false );
        final String content = "---\naudience: robots\n---\n# body\n";
        assertSame( content, disabled.preSave( null, content ) );
    }

    /* ---------- Phase 2: undeclared-cluster warning reaches the drift sweep ---------- */

    /**
     * The drift sweep counts validator codes, so the "cluster nobody declares" signal has to be
     * carried by the shared engine-backed context — not invented at the dashboard.
     */
    @org.junit.jupiter.api.Test
    void engineBackedCtx_warns_about_a_cluster_no_hub_declares() {
        final com.wikantik.api.managers.PageManager pm =
                org.mockito.Mockito.mock( com.wikantik.api.managers.PageManager.class );
        final com.wikantik.api.pagegraph.StructuralIndexService idx =
                org.mockito.Mockito.mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        org.mockito.Mockito.when( idx.getCluster( "van-life" ) ).thenReturn( java.util.Optional.empty() );

        final ValidationCtx ctx =
                SchemaValidationPageFilter.engineBackedCtx( new java.util.Properties(), pm, idx );

        org.junit.jupiter.api.Assertions.assertFalse( ctx.clusterIsDeclared().test( "van-life" ) );
    }

    @org.junit.jupiter.api.Test
    void engineBackedCtx_treats_a_cluster_with_a_hub_as_declared() {
        final com.wikantik.api.managers.PageManager pm =
                org.mockito.Mockito.mock( com.wikantik.api.managers.PageManager.class );
        final com.wikantik.api.pagegraph.StructuralIndexService idx =
                org.mockito.Mockito.mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        final com.wikantik.api.pagegraph.PageDescriptor hub =
                new com.wikantik.api.pagegraph.PageDescriptor( "01HAA000000000000000000001", "MLHub", "MLHub",
                        com.wikantik.api.pagegraph.PageType.HUB, "machine-learning", java.util.List.of(), null,
                        java.time.Instant.now(), java.util.Optional.empty(), false );
        org.mockito.Mockito.when( idx.getCluster( "machine-learning" ) ).thenReturn(
                java.util.Optional.of( new com.wikantik.api.pagegraph.ClusterDetails(
                        "machine-learning", hub, java.util.List.of( hub ), java.util.Map.of(),
                        java.time.Instant.now() ) ) );

        final ValidationCtx ctx =
                SchemaValidationPageFilter.engineBackedCtx( new java.util.Properties(), pm, idx );

        org.junit.jupiter.api.Assertions.assertTrue( ctx.clusterIsDeclared().test( "machine-learning" ) );
    }

    /** No index wired (early boot, degraded): stay silent rather than warn about every cluster. */
    @org.junit.jupiter.api.Test
    void engineBackedCtx_without_an_index_declares_every_cluster() {
        final com.wikantik.api.managers.PageManager pm =
                org.mockito.Mockito.mock( com.wikantik.api.managers.PageManager.class );

        final ValidationCtx ctx =
                SchemaValidationPageFilter.engineBackedCtx( new java.util.Properties(), pm, null );

        org.junit.jupiter.api.Assertions.assertTrue( ctx.clusterIsDeclared().test( "anything" ) );
    }
}
