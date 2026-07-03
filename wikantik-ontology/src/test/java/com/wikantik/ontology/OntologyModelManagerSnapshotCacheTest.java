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

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyModelManagerSnapshotCacheTest {

    private OntologyModelManager mgr;

    @BeforeEach
    void setUp() {
        mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
    }

    @Test
    void inferenceSnapshotIsCachedBetweenWrites() {
        assertSame( mgr.inferenceSnapshot(), mgr.inferenceSnapshot(),
            "repeated reads with no intervening write must return the cached snapshot" );
    }

    @Test
    void unionSnapshotIsCachedBetweenWrites() {
        assertSame( mgr.unionSnapshot(), mgr.unionSnapshot() );
    }

    @Test
    void replaceNamedGraphInvalidatesSnapshots() {
        final Model before = mgr.inferenceSnapshot();
        final Model unionBefore = mgr.unionSnapshot();

        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "cache-invalidation-probe" );
        mgr.replaceNamedGraph( "urn:test:graph", g );

        final Model after = mgr.inferenceSnapshot();
        assertNotSame( before, after );
        assertTrue( after.contains( after.createResource( "urn:test:subject" ), RDFS.label ) );
        assertNotSame( unionBefore, mgr.unionSnapshot() );
    }

    @Test
    void removeNamedGraphInvalidatesSnapshots() {
        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "to-be-removed" );
        mgr.replaceNamedGraph( "urn:test:graph", g );

        final Model withGraph = mgr.inferenceSnapshot();
        mgr.removeNamedGraph( "urn:test:graph" );
        final Model without = mgr.inferenceSnapshot();

        assertNotSame( withGraph, without );
        assertFalse( without.contains( without.createResource( "urn:test:subject" ), RDFS.label ) );
    }

    @Test
    void clearAboxInvalidatesSnapshots() {
        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "abox" );
        mgr.replaceNamedGraph( "urn:test:graph", g );
        final Model before = mgr.inferenceSnapshot();

        mgr.clearAbox();
        assertNotSame( before, mgr.inferenceSnapshot() );
    }
}
