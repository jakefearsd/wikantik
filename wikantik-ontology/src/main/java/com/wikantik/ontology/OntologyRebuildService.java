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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.wikantik.ontology.projection.ConceptProjector;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Materializes the full A-Box from in-memory inputs: projects every page and concept into its own named graph and prunes every graph it did not write.
 * All of it is ONE write transaction ({@link OntologyModelManager#replaceAbox}), each graph
 * projected as it is streamed in, so readers see the previous A-Box until the commit.
 * Pure orchestration — the caller supplies the lists from the repositories.
 */
public final class OntologyRebuildService {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildService.class );

    /** @return the number of named graphs the rebuilt A-Box holds. */
    public int rebuild( final OntologyModelManager mgr, final List< PageRecord > pages ) {
        final AtomicInteger written = new AtomicInteger();
        mgr.replaceAbox( abox -> {
            for ( final PageRecord page : pages ) {
                abox.replaceNamedGraph( Iris.page( page.canonicalId() ), PageProjector.project( page ) );
                written.incrementAndGet();
            }
            for ( final Map.Entry< String, Model > c : ConceptProjector.project( pages ).entrySet() ) {
                abox.replaceNamedGraph( c.getKey(), c.getValue() );
                written.incrementAndGet();
            }
        } );
        LOG.info( "ontology rebuild: {} named graphs ({} pages)", written.get(), pages.size() );
        return written.get();
    }
}
