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
package com.wikantik.knowledge.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import com.wikantik.ontology.OntologyModelManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MCP tool returning the FORMAL ontology T-Box — the class hierarchy (with subClassOf),
 * object properties (with domain/range + public subPropertyOf mappings), and SKOS concept
 * schemes. Complements {@code discover_schema}, which reports the EMPIRICAL ABox shape
 * (the types/properties actually present in the data) rather than the formal declarations.
 */
public class GetOntologyTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetOntologyTool.class );
    public static final String TOOL_NAME = "get_ontology";
    private static final String SKOS_CONCEPT_SCHEME = "http://www.w3.org/2004/02/skos/core#ConceptScheme";

    private final OntologyModelManager manager;

    public GetOntologyTool( final OntologyModelManager manager ) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Returns the formal ontology T-Box: classes (with subClassOf), object "
                        + "properties (with domain/range and public subPropertyOf mappings to "
                        + "schema.org/SKOS/Dublin Core), and SKOS concept schemes. Use it to plan "
                        + "SPARQL queries or to understand how query terms relate. Complements "
                        + "discover_schema (which reports the empirical data shape). "
                        + "Use this for authoritative counts/lists of classes and predicates rather than free-text search." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final Model t = manager.tboxSnapshot();
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "namespace", "https://wiki.wikantik.com/ns/wikantik#" );

            final List< Map< String, Object > > classes = new ArrayList<>();
            for ( final StmtIterator it = t.listStatements( null, RDF.type, OWL.Class ); it.hasNext(); ) {
                final Resource c = it.next().getSubject();
                if ( c.getURI() == null ) { continue; }
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "iri", c.getURI() );
                entry.put( "label", firstLabel( t, c ) );
                entry.put( "subClassOf", objectsOf( t, c, RDFS.subClassOf ) );
                classes.add( entry );
            }
            payload.put( "classes", classes );

            final List< Map< String, Object > > props = new ArrayList<>();
            for ( final StmtIterator it = t.listStatements( null, RDF.type, OWL.ObjectProperty ); it.hasNext(); ) {
                final Resource p = it.next().getSubject();
                if ( p.getURI() == null ) { continue; }
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "iri", p.getURI() );
                entry.put( "label", firstLabel( t, p ) );
                entry.put( "domain", first( objectsOf( t, p, RDFS.domain ) ) );
                entry.put( "range", first( objectsOf( t, p, RDFS.range ) ) );
                entry.put( "subPropertyOf", objectsOf( t, p, RDFS.subPropertyOf ) );
                props.add( entry );
            }
            payload.put( "objectProperties", props );

            final List< String > schemes = new ArrayList<>();
            for ( final StmtIterator it = t.listStatements( null, RDF.type,
                    t.createResource( SKOS_CONCEPT_SCHEME ) ); it.hasNext(); ) {
                final Resource s = it.next().getSubject();
                if ( s.getURI() != null ) { schemes.add( s.getURI() ); }
            }
            payload.put( "conceptSchemes", schemes );

            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final RuntimeException e ) {
            LOG.error( "get_ontology failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    private static String firstLabel( final Model t, final Resource r ) {
        for ( final StmtIterator it = t.listStatements( r, RDFS.label, (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isLiteral() ) { return o.asLiteral().getString(); }
        }
        return null;
    }

    private static List< String > objectsOf( final Model t, final Resource s, final org.apache.jena.rdf.model.Property p ) {
        final List< String > out = new ArrayList<>();
        for ( final StmtIterator it = t.listStatements( s, p, (RDFNode) null ); it.hasNext(); ) {
            final RDFNode o = it.next().getObject();
            if ( o.isResource() && o.asResource().getURI() != null ) { out.add( o.asResource().getURI() ); }
        }
        return out;
    }

    private static String first( final List< String > xs ) {
        return xs.isEmpty() ? null : xs.get( 0 );
    }
}
