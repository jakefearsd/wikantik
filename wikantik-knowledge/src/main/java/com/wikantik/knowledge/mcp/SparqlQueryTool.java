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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import com.wikantik.ontology.OntologyModelManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read-only SPARQL over the materialized public ontology (same Jena model the public
 * {@code /sparql} endpoint serves, with RDFS subClassOf entailment). SELECT/ASK return
 * SPARQL-results-JSON; CONSTRUCT/DESCRIBE return Turtle. SPARQL UPDATE is rejected.
 */
public class SparqlQueryTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( SparqlQueryTool.class );
    public static final String TOOL_NAME = "sparql_query";
    private static final long RESULT_CAP = 10_000;
    private static final long TIMEOUT_MS = 30_000;

    private final OntologyModelManager manager;

    public SparqlQueryTool( final OntologyModelManager manager ) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final McpSchema.JsonSchema input = new McpSchema.JsonSchema( "object",
                Map.of( "query", Map.of( "type", "string",
                        "description", "A read-only SPARQL SELECT, ASK, CONSTRUCT or DESCRIBE query "
                                + "over the Wikantik ontology (wk: namespace). UPDATE is rejected." ) ),
                List.of( "query" ), null, null, null );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Runs a read-only SPARQL query over the materialized ontology (classes, "
                        + "entities, page/concept metadata, with subClassOf entailment). Use get_ontology "
                        + "first to learn the schema. SELECT/ASK -> SPARQL-results-JSON; CONSTRUCT -> Turtle." )
                .inputSchema( input )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String queryString = McpToolUtils.getString( arguments, "query" );
        if ( queryString == null || queryString.isBlank() ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "missing 'query'" );
        }
        final Query query;
        try {
            query = QueryFactory.create( queryString );   // rejects SPARQL UPDATE syntactically
        } catch ( final QueryParseException e ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "invalid SPARQL query (note: UPDATE is not allowed): " + e.getMessage() );
        }
        if ( !query.hasLimit() || query.getLimit() > RESULT_CAP ) {
            query.setLimit( RESULT_CAP );
        }
        final Model data = manager.inferenceSnapshot();
        try ( QueryExecution qe = QueryExecution.create().query( query ).model( data )
                .timeout( TIMEOUT_MS, TimeUnit.MILLISECONDS ).build() ) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            if ( query.isSelectType() ) {
                ResultSetFormatter.outputAsJSON( out, qe.execSelect() );
            } else if ( query.isAskType() ) {
                ResultSetFormatter.outputAsJSON( out, qe.execAsk() );
            } else if ( query.isConstructType() || query.isDescribeType() ) {
                final Model m = query.isConstructType() ? qe.execConstruct() : qe.execDescribe();
                RDFDataMgr.write( out, m, Lang.TURTLE );
            } else {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "unsupported query form" );
            }
            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( out.toString( StandardCharsets.UTF_8 ) ) ) )
                    .build();
        } catch ( final RuntimeException e ) {
            LOG.warn( "sparql_query execution failed: {}", e.getMessage() );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "query execution failed: " + e.getMessage() );
        }
    }
}
