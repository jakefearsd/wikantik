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
package com.wikantik.rest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Public read-only SPARQL endpoint over the materialized public ontology dataset. */
public class OntologySparqlResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( OntologySparqlResource.class );
    private static final long RESULT_CAP = 10_000;
    private static final long TIMEOUT_MS = 30_000;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        runQuery( req.getParameter( "query" ), req, resp );
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        String q = req.getParameter( "query" );
        if ( q == null && req.getContentType() != null
                && req.getContentType().startsWith( "application/sparql-query" ) ) {
            q = new String( req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
        }
        runQuery( q, req, resp );
    }

    private void runQuery( final String queryString, final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        if ( queryString == null || queryString.isBlank() ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "missing 'query'" );
            return;
        }
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final Query query;
        try {
            query = QueryFactory.create( queryString );   // rejects SPARQL UPDATE syntactically
        } catch ( final QueryParseException e ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "invalid SPARQL query: " + e.getMessage() );
            return;
        }
        if ( !query.hasLimit() || query.getLimit() > RESULT_CAP ) {
            query.setLimit( RESULT_CAP );
        }
        final Model data = mgr.inferenceSnapshot();   // serve with subClassOf entailment
        try ( QueryExecution qe = QueryExecution.create().query( query ).model( data )
                .timeout( TIMEOUT_MS, TimeUnit.MILLISECONDS ).build() ) {
            final OutputStream out = resp.getOutputStream();
            if ( query.isSelectType() ) {
                resp.setContentType( "application/sparql-results+json" );
                final ResultSet rs = qe.execSelect();
                ResultSetFormatter.outputAsJSON( out, rs );
            } else if ( query.isAskType() ) {
                resp.setContentType( "application/sparql-results+json" );
                ResultSetFormatter.outputAsJSON( out, qe.execAsk() );
            } else if ( query.isConstructType() || query.isDescribeType() ) {
                final Model m = query.isConstructType() ? qe.execConstruct() : qe.execDescribe();
                final Lang lang = negotiateRdf( req );
                resp.setContentType( lang == Lang.JSONLD ? "application/ld+json" : "text/turtle" );
                RDFDataMgr.write( out, m, lang );
            } else {
                sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "unsupported query form" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "SPARQL execution failed: {}", e.getMessage() );
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "query execution failed: " + e.getMessage() );
        }
    }

    private static Lang negotiateRdf( final HttpServletRequest req ) {
        final String accept = req.getHeader( "Accept" );
        if ( accept != null && accept.contains( "json" ) ) {
            return Lang.JSONLD;
        }
        return Lang.TURTLE;
    }
}
