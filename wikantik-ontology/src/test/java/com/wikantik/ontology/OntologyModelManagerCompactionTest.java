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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduces the TDB2 dead-space defect: copy-on-write B+Trees never shrink
 * on their own, so a store that is repeatedly rewritten (nightly rebuild +
 * per-save incremental sync) only ever grows. {@link OntologyModelManager#compact()}
 * must actually free that dead space on disk, not merely be callable.
 */
class OntologyModelManagerCompactionTest {

    private Model triple( final String s, final String p, final String o ) {
        final Model m = ModelFactory.createDefaultModel();
        m.add( ResourceFactory.createResource( s ), RDF.type, ResourceFactory.createResource( o ) );
        return m;
    }

    @Test
    void inMemoryDatasetIsNotTdb2Backed() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        assertFalse( mgr.isTdb2Backed() );
    }

    @Test
    void inMemoryDatasetSizeIsZero() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        assertEquals( 0L, mgr.tdb2SizeBytes(), "in-memory dataset reports no disk footprint" );
    }

    @Test
    void compactIsANoOpOnTheInMemoryTestDataset() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        assertDoesNotThrow( mgr::compact, "compact() must be safe to call on a non-TDB2 backing" );
    }

    @Test
    void tdb2DatasetIsTdb2Backed( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            assertTrue( mgr.isTdb2Backed() );
        } finally {
            mgr.close();
        }
    }

    @Test
    void compactionShrinksDeadSpaceLeftByRepeatedRewrites( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();

            // Simulate the production churn pattern: the same resource graphs get
            // replaced over and over (nightly full rebuild + per-save incremental
            // sync), each replace freeing the old B+Tree blocks without reclaiming
            // the disk space they occupied.
            for ( int cycle = 0; cycle < 40; cycle++ ) {
                for ( int g = 0; g < 15; g++ ) {
                    final String iri = Iris.entity( new java.util.UUID( 0, g ) );
                    mgr.replaceNamedGraph( iri,
                            triple( iri + "#s" + cycle, "urn:p", "urn:o" + cycle ) );
                }
            }

            final long before = mgr.tdb2SizeBytes();
            assertTrue( before > 0, "TDB2 store should have a nonzero footprint after churn" );

            mgr.compact();

            final long after = mgr.tdb2SizeBytes();
            assertTrue( after < before,
                    "compact() should shrink the store: before=" + before + " after=" + after );
        } finally {
            mgr.close();
        }
    }

    @Test
    void compactionPreservesLiveData( @TempDir final Path dir ) {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();
            final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000c1" ) );
            mgr.replaceNamedGraph( iri, triple( iri, "urn:p", "urn:o" ) );
            mgr.replaceNamedGraph( iri, triple( iri, "urn:p", "urn:o-final" ) );

            mgr.compact();

            assertTrue( mgr.namedGraphExists( iri ), "named graph survives compaction" );
            assertTrue( mgr.namedGraphSnapshot( iri ).contains(
                    ResourceFactory.createResource( iri ), RDF.type,
                    ResourceFactory.createResource( "urn:o-final" ) ),
                    "the LATEST content survives — not a stale prior generation" );
        } finally {
            mgr.close();
        }
    }

    @Test
    void compactionReplacesTheOnDiskGenerationDirectory( @TempDir final Path dir ) throws Exception {
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir.toString() );
        try {
            mgr.loadTBox();
            final String before;
            try ( Stream< Path > kids = java.nio.file.Files.list( dir ) ) {
                before = kids.filter( p -> p.getFileName().toString().startsWith( "Data-" ) )
                        .map( p -> p.getFileName().toString() )
                        .findFirst().orElseThrow();
            }

            mgr.compact();

            try ( Stream< Path > kids = java.nio.file.Files.list( dir ) ) {
                final long dataDirs = kids.filter( p -> p.getFileName().toString().startsWith( "Data-" ) ).count();
                assertEquals( 1, dataDirs, "old generation must be deleted, not left behind (deleteOld=true)" );
            }
            try ( Stream< Path > kids = java.nio.file.Files.list( dir ) ) {
                final String after = kids.filter( p -> p.getFileName().toString().startsWith( "Data-" ) )
                        .map( p -> p.getFileName().toString() )
                        .findFirst().orElseThrow();
                assertFalse( after.equals( before ), "compaction must move to a NEW generation directory" );
            }
        } finally {
            mgr.close();
        }
    }
}
