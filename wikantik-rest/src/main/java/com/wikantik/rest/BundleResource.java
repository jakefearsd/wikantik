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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.ContextBundle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * {@code GET /api/bundle?q=&lt;query&gt;} — returns an assembled RAG context bundle as JSON.
 * No answer synthesis; serialises the ranked, cited sections directly (ADR-0001).
 */
public class BundleResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( BundleResource.class );

    private static final Gson BUNDLE_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Resolves the bundle assembly service from the knowledge subsystem.
     * Package-visible for test overrides via anonymous subclass.
     */
    protected BundleAssemblyService bundleService() {
        final com.wikantik.WikiSubsystems subs = getSubsystems();
        return subs == null ? null : subs.knowledge().bundleAssemblyService();
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String q = req.getParameter( "q" );
        if ( q == null || q.isBlank() ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"q (query) parameter required\"}" );
            return;
        }

        final BundleAssemblyService svc = bundleService();
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"bundle assembly service unavailable\"}" );
            return;
        }

        final ContextBundle bundle;
        try {
            bundle = svc.assemble( q );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Bundle assembly failed for query '{}': {}", q, e.getMessage(), e );
            resp.setStatus( 500 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"bundle assembly failed\"}" );
            return;
        }

        resp.setStatus( 200 );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.getWriter().write( BUNDLE_GSON.toJson( bundle ) );
    }
}
