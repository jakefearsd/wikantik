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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.wikantik.api.ontology.OntologyQueryService;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;

/** Jena-backed {@link OntologyQueryService} over the materialized ontology (inference snapshot). */
public final class JenaOntologyQueryService implements OntologyQueryService {

    private static final String SKOS_NARROWER   = "http://www.w3.org/2004/02/skos/core#narrower";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private final OntologyModelManager manager;

    public JenaOntologyQueryService( final OntologyModelManager manager ) {
        this.manager = manager;
    }

    @Override
    public List< String > expandQuery( final String query ) {
        if ( query == null || query.isBlank() ) {
            return List.of();
        }
        final Model model = manager.inferenceSnapshot();
        final String normQuery = normalize( query );
        final Set< String > expansion = new LinkedHashSet<>();

        // Index every labelled resource (class or concept) by its label; on a match, expand.
        for ( final StmtIterator it = model.listStatements( null, RDFS.label, (RDFNode) null ); it.hasNext(); ) {
            final Statement st = it.next();
            collectIfMatched( model, st.getSubject(), st.getObject(), normQuery, expansion );
        }
        for ( final StmtIterator it = model.listStatements( null,
                model.createProperty( SKOS_PREF_LABEL ), (RDFNode) null ); it.hasNext(); ) {
            final Statement st = it.next();
            collectIfMatched( model, st.getSubject(), st.getObject(), normQuery, expansion );
        }

        // Drop anything already literally present in the query.
        final Set< String > queryTokens = new HashSet<>( List.of( normQuery.split( " " ) ) );
        final List< String > out = new ArrayList<>();
        for ( final String term : expansion ) {
            if ( !queryTokens.contains( normalize( term ) ) ) {
                out.add( term );
            }
        }
        return out;
    }

    private void collectIfMatched( final Model model, final Resource subject, final RDFNode labelNode,
                                   final String normQuery, final Set< String > expansion ) {
        if ( !labelNode.isLiteral() ) {
            return;
        }
        if ( !containsAllTokens( normQuery, normalize( labelNode.asLiteral().getString() ) ) ) {
            return;
        }
        // Matched: transitive subclasses (RDFS-entailed in the inference snapshot) + SKOS narrower (BFS).
        for ( final StmtIterator sub = model.listStatements( null, RDFS.subClassOf, subject ); sub.hasNext(); ) {
            final Resource subClass = sub.next().getSubject();
            if ( !subClass.equals( subject ) ) {   // skip reflexive subClassOf
                addLabels( model, subClass, expansion );
            }
        }
        narrowerClosure( model, subject, expansion );
    }

    private void narrowerClosure( final Model model, final Resource start, final Set< String > expansion ) {
        final Deque< Resource > queue = new ArrayDeque<>();
        final Set< String > visited = new HashSet<>();
        queue.add( start );
        visited.add( start.toString() );
        while ( !queue.isEmpty() ) {
            final Resource cur = queue.poll();
            for ( final StmtIterator it = model.listStatements( cur,
                    model.createProperty( SKOS_NARROWER ), (RDFNode) null ); it.hasNext(); ) {
                final RDFNode o = it.next().getObject();
                if ( o.isResource() && visited.add( o.toString() ) ) {
                    final Resource narrower = o.asResource();
                    addLabels( model, narrower, expansion );
                    queue.add( narrower );
                }
            }
        }
    }

    private void addLabels( final Model model, final Resource r, final Set< String > expansion ) {
        for ( final StmtIterator it = model.listStatements( r, RDFS.label, (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isLiteral() ) { expansion.add( o.asLiteral().getString() ); }
        }
        for ( final StmtIterator it = model.listStatements( r,
                model.createProperty( SKOS_PREF_LABEL ), (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isLiteral() ) { expansion.add( o.asLiteral().getString() ); }
        }
    }

    private static String normalize( final String s ) {
        return s.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", " " ).trim();
    }

    /** True if every whitespace token of {@code label} appears as a token in {@code haystack}. */
    private static boolean containsAllTokens( final String haystack, final String label ) {
        if ( label.isBlank() ) {
            return false;
        }
        final String padded = " " + haystack + " ";
        for ( final String tok : label.split( " " ) ) {
            if ( !padded.contains( " " + tok + " " ) ) {
                return false;
            }
        }
        return true;
    }
}
