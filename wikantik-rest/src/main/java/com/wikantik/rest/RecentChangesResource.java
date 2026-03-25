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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST servlet for recent changes.
 * <p>
 * Mapped to {@code /api/recent-changes}. Handles:
 * <ul>
 *   <li>{@code GET /api/recent-changes?limit=50} - List recently changed pages</li>
 * </ul>
 */
public class RecentChangesResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( RecentChangesResource.class );

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        LOG.debug( "GET recent changes" );

        int limit = DEFAULT_LIMIT;
        final String limitParam = request.getParameter( "limit" );
        if ( limitParam != null ) {
            try {
                limit = Integer.parseInt( limitParam );
            } catch ( final NumberFormatException e ) {
                // use default
            }
        }
        limit = Math.min( limit, MAX_LIMIT );
        limit = Math.max( limit, 1 );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Set< Page > recentChanges = pm.getRecentChanges();

        final List< Map< String, Object > > changeList = recentChanges.stream()
                .limit( limit )
                .map( page -> {
                    final Map< String, Object > entry = new LinkedHashMap<>();
                    entry.put( "name", page.getName() );
                    entry.put( "author", page.getAuthor() );
                    entry.put( "lastModified", page.getLastModified() );
                    entry.put( "version", Math.max( page.getVersion(), 1 ) );
                    return entry;
                } )
                .toList();

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "changes", changeList );
        result.put( "total", changeList.size() );

        sendJson( response, result );
    }

}
