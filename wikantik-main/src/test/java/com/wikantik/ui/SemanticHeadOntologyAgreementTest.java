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
package com.wikantik.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 cross-layer agreement test (the spec's "until then they coexist; a test asserts they
 * agree"). The SEO JSON-LD ({@link SemanticHeadRenderer}) and the interop RDF graph
 * ({@link PageProjector} + RDFS inference) are two faces of one model; both derive a page's
 * schema.org type from the same {@code NodeTypeMapping}. This test renders a page and projects
 * the same page, then asserts the renderer's emitted schema.org {@code @type} is among the
 * schema.org types the ontology infers for that page — proving they cannot silently diverge.
 *
 * <p>Covered for the four page types the mapping distinguishes ({@code hub}, {@code article},
 * {@code runbook}, {@code design}). {@code reference} is a documented coexistence: the renderer
 * keeps the more-specific {@code Article} for SEO while the T-Box models it as the broader
 * {@code CreativeWork}; not asserted for strict equality here.</p>
 */
class SemanticHeadOntologyAgreementTest {

    private static final String SCHEMA = "https://schema.org/";
    private static final String CID = "01KTESTPAGE0000000000000000";

    @Test
    void rendererTypeAgreesWithOntologyForEachPageType() {
        for ( final String pageType : List.of( "hub", "article", "runbook", "design" ) ) {
            final String body = "---\ntype: " + pageType + "\ncanonical_id: " + CID
                    + "\nsummary: agreement fixture\n---\n# T\n\nBody.\n";

            // Renderer side: the schema.org @type emitted in the main JSON-LD.
            final String html = SemanticHeadRenderer.renderHead( "AgreementPage", body,
                    "http://example.com", "Wikantik" );
            final String rendererType = firstJsonLdType( html );

            // Ontology side: schema.org rdf:types the projection + RDFS inference assign the page.
            final Set< String > ontologySchemaTypes = inferredSchemaTypes( pageType );

            assertTrue( ontologySchemaTypes.contains( SCHEMA + rendererType ),
                    pageType + ": renderer @type '" + rendererType
                            + "' must be among the ontology's inferred schema.org types "
                            + ontologySchemaTypes );
        }
    }

    /** Project a page of the given type and return the schema.org rdf:types inference assigns it. */
    private static Set< String > inferredSchemaTypes( final String pageType ) {
        final PageRecord page = new PageRecord( CID, "AgreementPage", "T", pageType,
                null, List.of(), "agreement fixture", null, null );
        final Model graph = PageProjector.project( page );

        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        mgr.replaceNamedGraph( Iris.page( CID ), graph );
        final Model inferred = mgr.inferenceSnapshot();

        final Set< String > types = new HashSet<>();
        final StmtIterator it = inferred.listStatements(
                inferred.getResource( Iris.page( CID ) ), RDF.type, (RDFNode) null );
        while ( it.hasNext() ) {
            final Statement s = it.next();
            if ( s.getObject().isURIResource()
                    && s.getObject().asResource().getURI().startsWith( SCHEMA ) ) {
                types.add( s.getObject().asResource().getURI() );
            }
        }
        return types;
    }

    /** Local name of the {@code @type} in the first {@code application/ld+json} block. */
    private static String firstJsonLdType( final String html ) {
        final Matcher m = Pattern.compile(
                "<script type=\"application/ld\\+json\">\\s*(\\{.*?\\})\\s*</script>",
                Pattern.DOTALL ).matcher( html );
        if ( !m.find() ) {
            throw new AssertionError( "no JSON-LD block found in head" );
        }
        final JsonObject obj = JsonParser.parseString( m.group( 1 ) ).getAsJsonObject();
        return obj.get( "@type" ).getAsString();
    }
}
