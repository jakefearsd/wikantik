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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;

/**
 * {@code GET /admin/frontmatter-issues} — admin-only audit listing of pages whose
 * YAML frontmatter fails strict parsing. Use this to find and fix existing dirty
 * pages before relying on {@code FrontmatterValidationPageFilter} (default-enabled
 * via {@code wikantik.frontmatter.enforcement.enabled=true}) to reject every
 * subsequent save of those pages.
 *
 * <p>Typical workflow when adopting the validator on an existing wiki:</p>
 * <ol>
 *   <li>{@code GET /admin/frontmatter-issues} → list of broken pages with the
 *       SnakeYAML message and best-effort line/column.</li>
 *   <li>Fix each page in the editor or via the MCP {@code update_page} tool — the
 *       MCP path also auto-normalizes via {@code FrontmatterNormalizer}, so a fix
 *       may be as simple as re-saving with quoted values.</li>
 *   <li>Re-run the audit; once the list is empty, the validator is fully consistent
 *       with the corpus.</li>
 * </ol>
 *
 * <p>Scan cost is O(N) page reads, executed synchronously on the request thread.
 * Calling this on a large wiki should be infrequent — it's a migration tool, not
 * a dashboard polling target.</p>
 */
public class AdminFrontmatterIssuesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminFrontmatterIssuesResource.class );

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final PageManager pm = engine().getManager( PageManager.class );
        if ( pm == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"page manager unavailable\"}" );
            return;
        }

        final JsonArray issues = new JsonArray();
        final Collection< Page > allPages;
        try {
            allPages = pm.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.error( "frontmatter audit: getAllPages failed: {}", e.getMessage(), e );
            resp.setStatus( 500 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"page enumeration failed: "
                    + e.getMessage().replace( "\"", "'" ) + "\"}" );
            return;
        }
        int scanned = 0;
        int errors = 0;
        for ( final Page page : allPages ) {
            scanned++;
            final String name = page.getName();
            final String text;
            try {
                text = pm.getPureText( page );
            } catch ( final RuntimeException e ) {
                // A page that can't even be read is an issue worth surfacing —
                // the operator wanted to know about broken pages and this counts.
                LOG.warn( "frontmatter audit: failed to read '{}': {}", name, e.getMessage() );
                final JsonObject issue = new JsonObject();
                issue.addProperty( "pageName", name );
                issue.addProperty( "error", "page read failed: " + e.getMessage() );
                issues.add( issue );
                errors++;
                continue;
            }
            if ( text == null || text.isEmpty() ) {
                continue;
            }
            if ( !text.startsWith( "---\n" ) && !text.startsWith( "---\r\n" ) ) {
                // No frontmatter block — nothing to validate.
                continue;
            }
            try {
                FrontmatterParser.parseStrict( text );
            } catch ( final FrontmatterParseException fpe ) {
                final JsonObject issue = new JsonObject();
                issue.addProperty( "pageName", name );
                issue.addProperty( "error", fpe.getMessage() );
                if ( fpe.line() > 0 ) {
                    issue.addProperty( "line", fpe.line() );
                }
                if ( fpe.column() > 0 ) {
                    issue.addProperty( "column", fpe.column() );
                }
                issues.add( issue );
                errors++;
            }
        }

        final JsonObject data = new JsonObject();
        data.add( "issues", issues );
        data.addProperty( "issue_count", issues.size() );
        data.addProperty( "scanned", scanned );
        data.addProperty( "error_count", errors );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }
}
