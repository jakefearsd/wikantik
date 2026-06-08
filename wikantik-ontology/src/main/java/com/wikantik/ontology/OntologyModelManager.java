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

import java.io.InputStream;
import java.util.Iterator;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Owns the Jena {@link Dataset} backing the ontology: the T-Box in the default
 * graph and one named graph per materialized resource. All mutations run in
 * write transactions (required by TDB2; harmless for the in-memory dataset).
 */
public final class OntologyModelManager {

    private static final Logger LOG = LogManager.getLogger( OntologyModelManager.class );
    private static final String TBOX_RESOURCE = "/ontology/wikantik.ttl";

    private final Dataset dataset;

    private OntologyModelManager( final Dataset dataset ) {
        this.dataset = dataset;
    }

    /** In-memory transactional dataset (tests). */
    public static OntologyModelManager inMemory() {
        return new OntologyModelManager( DatasetFactory.createTxnMem() );
    }

    /** Persistent TDB2-backed dataset at {@code dir} (production). */
    public static OntologyModelManager tdb2( final String dir ) {
        return new OntologyModelManager( TDB2Factory.connectDataset( dir ) );
    }

    /** Loads the bundled T-Box into the default graph (replacing any prior T-Box). */
    public void loadTBox() {
        dataset.begin( ReadWrite.WRITE );
        try ( InputStream in = OntologyModelManager.class.getResourceAsStream( TBOX_RESOURCE ) ) {
            if ( in == null ) {
                throw new IllegalStateException( TBOX_RESOURCE + " not found on classpath" );
            }
            final Model def = dataset.getDefaultModel();
            def.removeAll();
            RDFDataMgr.read( def, in, Lang.TURTLE );
            dataset.commit();
        } catch ( final Exception e ) {
            dataset.abort();
            LOG.warn( "failed loading T-Box {}: {}", TBOX_RESOURCE, e.getMessage(), e );
            throw new IllegalStateException( "T-Box load failed", e );
        } finally {
            dataset.end();
        }
    }

    /** Atomically replaces (or creates) the named graph at {@code graphIri} with {@code triples}. */
    public void replaceNamedGraph( final String graphIri, final Model triples ) {
        dataset.begin( ReadWrite.WRITE );
        try {
            dataset.replaceNamedModel( graphIri, triples );
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            LOG.warn( "replaceNamedGraph failed for {}: {}", graphIri, e.getMessage(), e );
            throw e;
        } finally {
            dataset.end();
        }
    }

    /** Removes the named graph at {@code graphIri} (no-op if absent). */
    public void removeNamedGraph( final String graphIri ) {
        dataset.begin( ReadWrite.WRITE );
        try {
            dataset.removeNamedModel( graphIri );
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            LOG.warn( "removeNamedGraph failed for {}: {}", graphIri, e.getMessage(), e );
            throw e;
        } finally {
            dataset.end();
        }
    }

    public boolean namedGraphExists( final String graphIri ) {
        dataset.begin( ReadWrite.READ );
        try {
            return dataset.containsNamedModel( graphIri )
                    && !dataset.getNamedModel( graphIri ).isEmpty();
        } finally {
            dataset.end();
        }
    }

    /** Removes every named graph (A-Box), leaving the default-graph T-Box intact. */
    public void clearAbox() {
        dataset.begin( ReadWrite.WRITE );
        try {
            final java.util.List< String > names = new java.util.ArrayList<>();
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                names.add( it.next() );
            }
            for ( final String name : names ) {
                dataset.removeNamedModel( name );
            }
            dataset.commit();
        } catch ( final RuntimeException e ) {
            dataset.abort();
            throw e;
        } finally {
            dataset.end();
        }
    }

    /** Detached copy of the T-Box (default graph). */
    public Model tboxSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            return ModelFactory.createDefaultModel().add( dataset.getDefaultModel() );
        } finally {
            dataset.end();
        }
    }

    /**
     * Detached RDFS inference model over (T-Box default graph union all named graphs).
     * Sized for the current corpus; callers query it outside any transaction.
     */
    public Model inferenceSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            final Model union = ModelFactory.createDefaultModel();
            union.add( dataset.getDefaultModel() );
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                union.add( dataset.getNamedModel( it.next() ) );
            }
            final InfModel inf = ModelFactory.createRDFSModel( union );
            return ModelFactory.createDefaultModel().add( inf );
        } finally {
            dataset.end();
        }
    }

    /** For Phase 1b shutdown wiring. */
    public void close() {
        dataset.close();
    }
}
