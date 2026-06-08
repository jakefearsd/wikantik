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

import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Per-resource RDF dereferencing: GET /id/{type}/{id} -> the resource's named graph. */
public class OntologyResourceResource extends PublicRdfServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final OntologyModelManager mgr = modelManager();
        if ( mgr == null ) {
            sendError( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ontology service not available" );
            return;
        }
        // pathInfo: /{type}/{id}  e.g. /entity/<uuid>, /page/<canonicalId>, /concept/<slug...>
        final String pathInfo = req.getPathInfo();
        if ( pathInfo == null || pathInfo.length() < 2 ) {
            sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "expected /id/{type}/{id}" );
            return;
        }
        final String iri = Iris.ID + pathInfo.substring( 1 );   // ID base + "type/id"
        final Model g = mgr.namedGraphSnapshot( iri );
        if ( g.isEmpty() ) {
            sendError( resp, HttpServletResponse.SC_NOT_FOUND, "no public resource at " + iri );
            return;
        }
        final boolean json = req.getHeader( "Accept" ) != null && req.getHeader( "Accept" ).contains( "json" );
        final Lang lang = json ? Lang.JSONLD : Lang.TURTLE;
        resp.setContentType( json ? "application/ld+json" : "text/turtle" );
        RDFDataMgr.write( resp.getOutputStream(), g, lang );
    }
}
