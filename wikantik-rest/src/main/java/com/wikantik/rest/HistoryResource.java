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

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.collab.adapter.CollabEngineAccess;

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
import java.util.TreeSet;

/**
 * REST servlet for page version history.
 * <p>
 * Mapped to {@code /api/history/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/history/PageName} - Get version history for a page</li>
 * </ul>
 */
public class HistoryResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( HistoryResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        LOG.debug( "GET history: {}", pageName );

        final PageManager pm = getSubsystems().page().pages();
        final PublicIdentity identity = getSubsystems().auth().publicIdentity();

        // Check if the page exists
        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        // Authorization: version history (authors, timestamps, change-notes) must honour
        // the page's view ACL — deny anonymous access to a restricted page's history.
        if ( !checkPagePermission( request, response, pageName, "view" ) ) {
            return;
        }

        final List< ? extends Page > versions = pm.getVersionHistory( pageName );
        final Map< Integer, List< String > > ledger =
                CollabEngineAccess.contributorsByVersion( getEngine(), canonicalIdOf( pm, pageName ) );

        final List< Map< String, Object > > versionList;
        if ( versions == null || versions.isEmpty() ) {
            // Page exists but no version history — return the current page as the sole version
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "version", Math.max( page.getVersion(), 1 ) );
            entry.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );
            entry.put( "lastModified", page.getLastModified() );
            entry.put( "changeNote", IdentityDisplay.changeNote( identity, page.getAttribute( Page.CHANGENOTE ) ) );
            entry.put( "contributors", contributors( identity, ledger, Math.max( page.getVersion(), 1 ), page.getAuthor() ) );
            versionList = List.of( entry );
        } else {
            versionList = versions.stream()
                    .map( v -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "version", Math.max( v.getVersion(), 1 ) );
                        entry.put( "author", IdentityDisplay.ofAuthor( identity, v.getAuthor() ) );
                        entry.put( "lastModified", v.getLastModified() );
                        entry.put( "changeNote", IdentityDisplay.changeNote( identity, v.getAttribute( Page.CHANGENOTE ) ) );
                        entry.put( "contributors", contributors( identity, ledger, Math.max( v.getVersion(), 1 ), v.getAuthor() ) );
                        return entry;
                    } )
                    .toList();
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", pageName );
        result.put( "versions", versionList );

        sendJson( response, result );
    }

    /** The page's {@code canonical_id} from its latest text's frontmatter, or null when it has none or cannot be read. */
    private static String canonicalIdOf( final PageManager pm, final String pageName ) {
        try {
            final Object id = FrontmatterParser.parse( pm.getPureText( pageName, -1 ) ).metadata().get( "canonical_id" );
            return id == null ? null : id.toString();
        } catch ( final RuntimeException e ) {
            LOG.warn( "History of {}: the canonical_id could not be read, contributors are omitted: {}", pageName, e.toString() );
            return null;
        }
    }

    /**
     * The display names of a version's contributors: the ledger's keys through {@link IdentityDisplay#ofAuthor} (a name
     * for an account, "former member" for a deleted one, "unknown contributor" for an unresolvable email), deduplicated
     * and sorted, with the version's own author, in the same form, first when it is among them. Never a raw key.
     */
    private static List< String > contributors( final PublicIdentity identity, final Map< Integer, List< String > > ledger,
                                                final int version, final String author ) {
        final TreeSet< String > names = new TreeSet<>();
        for ( final String key : ledger.getOrDefault( version, List.of() ) ) {
            final String shown = IdentityDisplay.ofAuthor( identity, key );
            if ( shown != null && !shown.isBlank() ) {
                names.add( shown );
            }
        }
        final String authorShown = IdentityDisplay.ofAuthor( identity, author );
        final List< String > out = new ArrayList<>();
        if ( authorShown != null && names.remove( authorShown ) ) {
            out.add( authorShown );
        }
        out.addAll( names );
        return out;
    }

}
