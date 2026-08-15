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

import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pagegraph.spine.ClusterRenamePlan;
import com.wikantik.pagegraph.spine.ClusterRenameResult;
import com.wikantik.pagegraph.spine.ClusterRenameService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *  Admin endpoint for cluster curation — ClusterDeclarationDesign Phase 4.
 *
 *  <ul>
 *    <li>{@code POST /admin/clusters/rename?from=X&to=Y} — returns the <b>plan</b>:
 *        which pages would change, and any conflicts. Writes nothing.</li>
 *    <li>{@code POST /admin/clusters/rename?from=X&to=Y&confirm=true} — applies it,
 *        rewriting {@code cluster:} in every member's frontmatter, sub-clusters included.</li>
 *  </ul>
 *
 *  <p>Cluster membership is a path in frontmatter, so reorganizing the taxonomy is
 *  O(member pages) by construction. This endpoint is what keeps that from becoming the
 *  curation overhead the design exists to minimize. It renames <i>nothing else</i>: no
 *  page names, no {@code canonical_id}s, no URLs — each member simply gains one more
 *  ordinary, revertable revision.</p>
 *
 *  <p>Protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).</p>
 */
public class AdminClusterResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminClusterResource.class );

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "rename".equals( action ) ) {
            handleRename( request, response );
        } else {
            sendNotFound( response, "Unknown cluster endpoint: " + action );
        }
    }

    private void handleRename( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String from = request.getParameter( "from" );
        final String to = request.getParameter( "to" );
        final boolean confirm = Boolean.parseBoolean( request.getParameter( "confirm" ) );

        final ClusterRenameService service = buildRenameService();
        if ( service == null ) {
            sendError( response, 503, "The structural index is unavailable, so cluster members "
                    + "cannot be resolved. Rebuild the structural index and retry." );
            return;
        }

        final ClusterRenamePlan plan;
        try {
            plan = service.plan( from, to );
        } catch ( final IllegalArgumentException iae ) {
            sendError( response, 400, iae.getMessage() );
            return;
        }

        if ( plan.hasConflicts() ) {
            // 409, not 400: the request is well-formed, the corpus is what refuses it.
            sendError( response, 409, "Cannot rename '" + from + "' to '" + to + "': "
                    + String.join( "; ", plan.conflicts() ) );
            return;
        }

        if ( !confirm ) {
            sendJson( response, previewOf( plan ) );
            return;
        }

        final String actor = resolveAdminAuthor( request );
        final ClusterRenameResult result = service.apply( from, to, actor );
        LOG.info( "AdminClusterResource: renamed cluster '{}' -> '{}' by '{}' — {} page(s), {} failed",
                  from, to, actor, result.renamed().size(), result.failures().size() );

        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "applied", true );
        payload.put( "from", result.from() );
        payload.put( "to", result.to() );
        payload.put( "pageCount", plan.changes().size() );
        payload.put( "renamed", result.renamed() );
        payload.put( "failures", result.failures() );
        payload.put( "complete", result.complete() );
        sendJson( response, payload );
    }

    private static Map< String, Object > previewOf( final ClusterRenamePlan plan ) {
        final List< Map< String, Object > > changes = new ArrayList<>();
        for ( final ClusterRenamePlan.PageChange change : plan.changes() ) {
            changes.add( Map.of( "slug", change.slug(),
                                 "fromCluster", change.fromCluster(),
                                 "toCluster", change.toCluster() ) );
        }
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "applied", false );
        payload.put( "from", plan.from() );
        payload.put( "to", plan.to() );
        payload.put( "pageCount", changes.size() );
        payload.put( "changes", changes );
        payload.put( "conflicts", plan.conflicts() );
        return payload;
    }

    /**
     *  Resolves the author recorded on the resulting page revisions. Always behind
     *  {@code AdminAuthFilter}, so the caller is an authenticated admin; falls back to
     *  {@code "system"} defensively. Protected so tests can override without engine
     *  infrastructure.
     *
     *  @param request the current request
     *  @return the login name to record as author
     */
    protected String resolveAdminAuthor( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( getEngine(), request );
        return session.isAuthenticated() ? session.getUserPrincipal().getName() : "system";
    }

    /**
     *  Builds the rename service from the live wiki managers, or {@code null} when the
     *  structural index is not wired. Protected so tests can inject one.
     *
     *  @return the service, or {@code null} when cluster membership cannot be resolved
     */
    protected ClusterRenameService buildRenameService() {
        final StructuralIndexService structuralIndex = getSubsystems().pageGraph().structuralIndexService();
        if ( structuralIndex == null ) {
            LOG.warn( "AdminClusterResource: structural index unavailable; cluster rename disabled" );
            return null;
        }
        final PageManager pageManager = getSubsystems().page().pages();
        return new ClusterRenameService( pageManager, structuralIndex,
                                          new PageSaveHelper( getEngine(), pageManager ) );
    }
}
