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
package com.wikantik.tools;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.permissions.PermissionFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Executes the {@code get_page} tool: fetches a single page by name, strips frontmatter,
 * truncates body text to a bounded length, and shapes the response with citation URL
 * plus summary/tags pulled from frontmatter.
 *
 * <p>The truncation bound keeps LLM context budgets predictable. Callers can override
 * the default via {@code maxChars} up to {@value #HARD_MAX_CHARS}.</p>
 *
 * <p>Enforces view-level ACLs via {@link PermissionFilter} — tool-server callers must
 * pass the same access check a logged-in REST client would see for the page, so inline
 * {@code [{ALLOW view ...}]} blocks and policy-level grants are honoured. ACL failures
 * raise {@link PageAccessDeniedException} before any page body is loaded, so restricted
 * pages don't even reach the frontmatter/text shaping stage.</p>
 */
class GetPageTool {

    private static final Logger LOG = LogManager.getLogger( GetPageTool.class );
    private static final int HARD_MAX_CHARS = 20_000;

    private final Engine engine;
    private final ToolsConfig config;

    GetPageTool( final Engine engine, final ToolsConfig config ) {
        this.engine = engine;
        this.config = config;
    }

    /**
     * @param pageName required non-blank page name
     * @param maxChars optional body truncation limit; when {@code <=0} the default is used,
     *                 and the value is clamped to {@value #HARD_MAX_CHARS} otherwise
     * @param request  HTTP request for building fallback citation URLs
     * @return ordered map ready for JSON serialization, or {@code null} when the page is missing
     */
    Map< String, Object > execute( final String pageName, final int maxChars, final HttpServletRequest request ) {
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page;
        try {
            page = pm.getPage( pageName );
        } catch ( final Exception e ) {
            LOG.warn( "get_page lookup failed for '{}': {}", pageName, e.getMessage() );
            return null;
        }
        if ( page == null ) {
            return null;
        }

        final String resolvedName = page.getName();
        if ( !canView( request, resolvedName ) ) {
            throw new PageAccessDeniedException( resolvedName );
        }

        final String raw;
        try {
            raw = pm.getPureText( resolvedName, -1 );
        } catch ( final Exception e ) {
            LOG.warn( "get_page body load failed for '{}': {}", pageName, e.getMessage() );
            return null;
        }

        final String body = ResultShaper.bodyOnly( raw );
        final int limit = resolveLimit( maxChars );
        final boolean truncated = ResultShaper.wasTruncated( body, limit );
        final String shown = ResultShaper.truncateBody( body, limit );

        final Map< String, Object > out = ResultShaper.orderedMap();
        out.put( "name", page.getName() );
        out.put( "url", ResultShaper.citationUrl( page.getName(), request, config.publicBaseUrl() ) );
        ResultShaper.applyFrontmatter( out, ResultShaper.frontmatter( raw ) );
        if ( page.getLastModified() != null ) {
            out.put( "lastModified", page.getLastModified().toInstant().toString() );
        }
        if ( page.getAuthor() != null ) {
            out.put( "author", page.getAuthor() );
        }
        out.put( "text", shown );
        out.put( "truncated", truncated );
        if ( truncated ) {
            out.put( "totalChars", body.length() );
            out.put( "truncatedAt", limit );
        }
        return out;
    }

    /**
     * Evaluates whether the principal bound to {@code request} may view {@code pageName}.
     * Package-visible so tests can override with a stub permission gate; production path
     * resolves the wiki session and asks {@link PermissionFilter}.
     */
    boolean canView( final HttpServletRequest request, final String pageName ) {
        final Session session = Wiki.session().find( engine, request );
        return new PermissionFilter( engine ).canAccess( session, pageName, "view" );
    }

    private static int resolveLimit( final int requested ) {
        if ( requested <= 0 ) {
            return ResultShaper.defaultBodyTruncation();
        }
        return Math.min( requested, HARD_MAX_CHARS );
    }
}
