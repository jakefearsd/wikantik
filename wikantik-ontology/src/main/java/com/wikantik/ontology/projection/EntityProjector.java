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
package com.wikantik.ontology.projection;

import java.util.List;
import java.util.function.Function;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.NodeTypeMapping;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/** Projects a KG node (and its outgoing edges) into its per-entity named graph. */
public final class EntityProjector {

    private static final String PROV = "http://www.w3.org/ns/prov#";

    private EntityProjector() {}

    /**
     * @param node              the KG node
     * @param outgoingEdges     edges whose sourceId == node.id()
     * @param slugToCanonicalId resolves a page slug to its canonical_id (null if unknown)
     */
    public static Model project( final KgNode node, final List< KgEdge > outgoingEdges,
                                 final Function< String, String > slugToCanonicalId ) {
        final Model m = ModelFactory.createDefaultModel();
        final Resource subject = m.createResource( Iris.entity( node.id() ) );

        m.add( subject, RDF.type,
               m.createResource( Iris.term( NodeTypeMapping.classLocalName( node.nodeType() ) ) ) );
        if ( node.name() != null ) {
            m.add( subject, RDFS.label, m.createLiteral( node.name() ) );
        }

        // Provenance: attribution by tier, derivation from the source page (when resolvable).
        m.add( subject, m.createProperty( PROV + "wasAttributedTo" ),
               m.createResource( Iris.NS
                       + ( "machine".equalsIgnoreCase( node.tier() ) ? "MachineAgent" : "HumanAgent" ) ) );
        if ( node.sourcePage() != null ) {
            final String canonicalId = slugToCanonicalId.apply( node.sourcePage() );
            if ( canonicalId != null ) {
                m.add( subject, m.createProperty( PROV + "wasDerivedFrom" ),
                       m.createResource( Iris.page( canonicalId ) ) );
            }
        }

        for ( final KgEdge e : outgoingEdges ) {
            EdgeProjector.toStatement( e ).ifPresent( m::add );
        }
        return m;
    }
}
