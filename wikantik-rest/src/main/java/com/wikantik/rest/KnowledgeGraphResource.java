package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class KnowledgeGraphResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( KnowledgeGraphResource.class );

    @Override
    protected void doGet( final HttpServletRequest request,
                          final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        // D27: knowledge graph reads are now public to match the rest of /api/structure/*.
        // The graph contains only canonical ids and relationship types — no page bodies
        // or ACL-restricted content — so anonymous readers and agents can use it freely.
        try {
            final KnowledgeGraphService svc =
                    engine.getManager( KnowledgeGraphService.class );
            final GraphSnapshot snapshot = svc.snapshotGraph( session );
            sendJson( response, snapshot );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to build knowledge-graph snapshot", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       "Failed to build graph snapshot" );
        }
    }
}
