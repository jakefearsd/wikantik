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

import com.wikantik.ontology.Iris;
import com.wikantik.ontology.NodeTypeMapping;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/** Projects a page (canonical_id + frontmatter) into its per-page named graph. */
public final class PageProjector {

    private static final String DCT       = "http://purl.org/dc/terms/";
    private static final String SCHEMA    = "https://schema.org/";
    private static final String WIKI_BASE = "https://wiki.wikantik.com/wiki/";

    private PageProjector() {}

    public static Model project( final PageRecord page ) {
        final Model m = ModelFactory.createDefaultModel();
        final Resource subject = m.createResource( Iris.page( page.canonicalId() ) );

        // type: reuses NodeTypeMapping (hub/article/reference/runbook/design -> content class).
        m.add( subject, RDF.type, m.createResource( Iris.term( NodeTypeMapping.classLocalName( page.type() ) ) ) );
        if ( page.title() != null ) {
            m.add( subject, m.createProperty( DCT + "title" ), m.createLiteral( page.title() ) );
        }
        if ( page.slug() != null ) {
            m.add( subject, m.createProperty( SCHEMA + "url" ), m.createResource( WIKI_BASE + page.slug() ) );
        }
        if ( page.summary() != null ) {
            m.add( subject, m.createProperty( DCT + "description" ), m.createLiteral( page.summary() ) );
        }
        if ( page.isoDate() != null ) {
            m.add( subject, m.createProperty( DCT + "created" ), m.createLiteral( page.isoDate() ) );
        }
        if ( page.author() != null ) {
            m.add( subject, m.createProperty( DCT + "creator" ), m.createLiteral( page.author() ) );
        }
        // dct:subject links to tag + cluster concepts.
        for ( final String tag : page.tags() ) {
            m.add( subject, m.createProperty( DCT + "subject" ), m.createResource( Iris.concept( tag ) ) );
        }
        if ( page.cluster() != null && !page.cluster().isBlank() ) {
            m.add( subject, m.createProperty( DCT + "subject" ), m.createResource( Iris.concept( page.cluster() ) ) );
        }
        return m;
    }
}
