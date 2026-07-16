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

import com.wikantik.api.config.GenAiMode;
import com.wikantik.util.TextUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * REST servlet exposing which optional subsystems are enabled for this
 * deployment, so the React SPA can gate its own navigation/UI before the
 * user has even logged in.
 *
 * <p>Mapped to <code>/api/changes</code>&mdash;style public endpoint
 * <code>GET /api/capabilities</code>. Deliberately anonymous: capability
 * booleans are not secrets, and the SPA needs them pre-login to avoid a
 * flash of Knowledge Graph navigation it will immediately have to hide.
 *
 * <p>Every field is computed fresh from {@link com.wikantik.api.core.Engine#getWikiProperties()}
 * on each request&mdash;this is a config read, not a subsystem probe, so
 * there is no caching to invalidate and no risk of the response drifting
 * from a property that was just changed.
 *
 * <p>Unlike the other flags (raw property pass-throughs), {@code hybridSearch}
 * is a <em>derived</em> value: the raw flag ANDed with the
 * {@link GenAiMode} ceiling, mirroring
 * {@code com.wikantik.search.embedding.EmbeddingConfig#fromProperties}'s
 * effective-enablement formula (a parity test in
 * {@code CapabilitiesResourceTest} guards against the two drifting apart).
 */
public class CapabilitiesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( CapabilitiesResource.class );

    /** Config property: {@code wikantik.knowledge.enabled} (default {@code true}). */
    public static final String PROP_KNOWLEDGE_ENABLED = "wikantik.knowledge.enabled";

    /**
     * Config property: {@code wikantik.search.hybrid.enabled} (default {@code true}).
     * The {@code hybridSearch} response field also reflects the
     * {@code wikantik.genai.mode} ceiling, not just this flag — see {@code doGet}.
     */
    public static final String PROP_HYBRID_SEARCH_ENABLED = "wikantik.search.hybrid.enabled";

    /** Config property: {@code wikantik.ontology.enabled} (default {@code true}). */
    public static final String PROP_ONTOLOGY_ENABLED = "wikantik.ontology.enabled";

    /** Config property: {@code wikantik.connectors.enabled} (default {@code true}). */
    public static final String PROP_CONNECTORS_ENABLED = "wikantik.connectors.enabled";

    /** Config property: {@code wikantik.citations.enabled} (default {@code true}). */
    public static final String PROP_CITATIONS_ENABLED = "wikantik.citations.enabled";

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        try {
            final Properties props = getEngine().getWikiProperties();
            final GenAiMode mode = GenAiMode.fromProperties( props );

            // hybridSearch is the EFFECTIVE value: the raw flag ANDed with the
            // genai.mode ceiling, mirroring EmbeddingConfig.fromProperties (which
            // forces the embedding client off when the mode disallows embeddings).
            // We keep the explicit default-true read of the raw property here
            // instead of delegating to EmbeddingConfig: its code-level default
            // for the flag is false (the shipped ini overrides it to true), and
            // this endpoint's contract is defaults-all-true on bare properties.
            final boolean hybridSearch =
                    TextUtil.getBooleanProperty( props, PROP_HYBRID_SEARCH_ENABLED, true )
                    && mode.allowsEmbeddings();

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "knowledgeGraph", TextUtil.getBooleanProperty( props, PROP_KNOWLEDGE_ENABLED, true ) );
            result.put( "hybridSearch", hybridSearch );
            result.put( "genaiMode", toToken( mode ) );
            result.put( "ontology", TextUtil.getBooleanProperty( props, PROP_ONTOLOGY_ENABLED, true ) );
            result.put( "connectors", TextUtil.getBooleanProperty( props, PROP_CONNECTORS_ENABLED, true ) );
            result.put( "citations", TextUtil.getBooleanProperty( props, PROP_CITATIONS_ENABLED, true ) );

            response.setContentType( "application/json" );
            response.setCharacterEncoding( "UTF-8" );
            response.getWriter().write( GSON.toJson( result ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Error handling /api/capabilities: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error" );
        }
    }

    /**
     * Serializes a {@link GenAiMode} as the lowercase, hyphenated token the
     * JSON contract specifies ({@code full}/{@code embeddings-only}/{@code none}),
     * rather than the enum's {@code name()}.
     */
    static String toToken( final GenAiMode mode ) {
        return mode.name().toLowerCase( Locale.ROOT ).replace( '_', '-' );
    }
}
