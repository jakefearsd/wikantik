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

import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Bulk RDF dumps for ingestion: /export/ontology.ttl (T-Box) + /export/graph.nt (public A-Box+T-Box). */
public class OntologyExportResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        if ( path.endsWith( "ontology.ttl" ) ) {
            resp.setContentType( "text/turtle" );
            RDFDataMgr.write( resp.getOutputStream(), mgr.tboxSnapshot(), Lang.TURTLE );
        } else if ( path.endsWith( "graph.nt" ) ) {
            resp.setContentType( "application/n-triples" );
            RDFDataMgr.write( resp.getOutputStream(), mgr.unionSnapshot(), Lang.NTRIPLES );
        } else {
            sendError( resp, HttpServletResponse.SC_NOT_FOUND, "use /export/ontology.ttl or /export/graph.nt" );
        }
    }
}
