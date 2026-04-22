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

import com.google.gson.JsonObject;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.blog.BlogAlreadyExistsException;
import com.wikantik.blog.BlogInfo;
import com.wikantik.blog.BlogManager;
import com.wikantik.render.RenderingManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST servlet for blog operations.
 * <p>
 * Mapped to {@code /api/blog/*}. Handles:
 * <ul>
 *   <li>{@code POST /api/blog} - Create the current user's blog</li>
 *   <li>{@code GET  /api/blog} - List all blogs</li>
 *   <li>{@code GET  /api/blog/{username}} - Get blog metadata</li>
 *   <li>{@code DELETE /api/blog/{username}} - Delete a blog (owner or admin)</li>
 *   <li>{@code POST /api/blog/{username}/entries} - Create a new blog entry</li>
 *   <li>{@code GET  /api/blog/{username}/entries} - List entries</li>
 *   <li>{@code GET  /api/blog/{username}/entries/{name}} - Get entry content + metadata</li>
 *   <li>{@code PUT  /api/blog/{username}/entries/{name}} - Update entry content</li>
 *   <li>{@code DELETE /api/blog/{username}/entries/{name}} - Delete an entry</li>
 * </ul>
 *
 * @since 3.0.8
 */
public class BlogResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( BlogResource.class );

    // ----- Path parsing -----

    /**
     * Parsed path from the request pathInfo. Possible shapes:
     * <ul>
     *   <li>{@code null / empty} — blog list or create blog</li>
     *   <li>{@code username} — single blog metadata or delete</li>
     *   <li>{@code username/entries} — list or create entries</li>
     *   <li>{@code username/entries/name} — single entry CRUD</li>
     * </ul>
     */
    private record BlogPath( String username, boolean isEntries, String entryName ) {
    }

    /**
     * Parses the request path info into a structured {@link BlogPath}.
     *
     * @param request the HTTP request
     * @return the parsed path, or {@code null} if the path is the root (no path info)
     */
    private BlogPath parsePath( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.equals( "/" ) || pathInfo.isEmpty() ) {
            return null;
        }

        // Strip leading slash, then split
        final String path = pathInfo.substring( 1 );
        final String[] parts = path.split( "/" );

        if ( parts.length == 1 ) {
            return new BlogPath( parts[ 0 ], false, null );
        }
        if ( parts.length == 2 && "entries".equals( parts[ 1 ] ) ) {
            return new BlogPath( parts[ 0 ], true, null );
        }
        if ( parts.length == 3 && "entries".equals( parts[ 1 ] ) ) {
            return new BlogPath( parts[ 0 ], true, parts[ 2 ] );
        }

        // Unrecognized path shape
        return new BlogPath( parts[ 0 ], false, "INVALID" );
    }

    /**
     * Resolves the wiki session from the HTTP request. Package-visible so that
     * tests can override via a Mockito spy.
     *
     * @param request the HTTP request
     * @return the wiki session
     */
    Session resolveSession( final HttpServletRequest request ) {
        return Wiki.session().find( getEngine(), request );
    }

    /**
     * Resolves the session and sends a 401 if not authenticated.
     *
     * @return the authenticated session, or {@code null} if a 401 was sent
     */
    private Session requireAuthenticated( final HttpServletRequest request,
                                           final HttpServletResponse response ) throws IOException {
        final Session session = resolveSession( request );
        if ( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return null;
        }
        return session;
    }

    // ----- HTTP method handlers -----

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final BlogPath blogPath = parsePath( request );

        if ( blogPath == null ) {
            // GET /api/blog — list all blogs
            handleListBlogs( response );
        } else if ( !blogPath.isEntries() ) {
            // GET /api/blog/{username}
            handleGetBlog( request, response, blogPath.username() );
        } else if ( blogPath.entryName() == null ) {
            // GET /api/blog/{username}/entries
            handleListEntries( response, blogPath.username() );
        } else {
            // GET /api/blog/{username}/entries/{name}
            handleGetEntry( request, response, blogPath.username(), blogPath.entryName() );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final BlogPath blogPath = parsePath( request );

        if ( blogPath == null ) {
            // POST /api/blog — create blog for current user
            handleCreateBlog( request, response );
        } else if ( blogPath.isEntries() && blogPath.entryName() == null ) {
            // POST /api/blog/{username}/entries — create entry
            handleCreateEntry( request, response, blogPath.username() );
        } else {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid blog endpoint" );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final BlogPath blogPath = parsePath( request );

        if ( blogPath != null && !blogPath.isEntries() ) {
            // PUT /api/blog/{username} — update Blog.md
            handleUpdateBlogHome( request, response, blogPath.username() );
        } else if ( blogPath != null && blogPath.isEntries() && blogPath.entryName() != null ) {
            // PUT /api/blog/{username}/entries/{name}
            handleUpdateEntry( request, response, blogPath.username(), blogPath.entryName() );
        } else {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid blog endpoint" );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final BlogPath blogPath = parsePath( request );

        if ( blogPath == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Blog username is required" );
        } else if ( !blogPath.isEntries() && blogPath.entryName() == null ) {
            // DELETE /api/blog/{username}
            handleDeleteBlog( request, response, blogPath.username() );
        } else if ( blogPath.isEntries() && blogPath.entryName() != null ) {
            // DELETE /api/blog/{username}/entries/{name}
            handleDeleteEntry( request, response, blogPath.username(), blogPath.entryName() );
        } else {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid blog endpoint" );
        }
    }

    // ----- Blog operations -----

    /**
     * Handles GET /api/blog — lists all blogs.
     */
    private void handleListBlogs( final HttpServletResponse response ) throws IOException {
        try {
            final BlogManager blogManager = getEngine().getManager( BlogManager.class );
            final List< BlogInfo > blogs = blogManager.listBlogs();

            final List< Map< String, Object > > result = new ArrayList<>();
            for ( final BlogInfo info : blogs ) {
                final Map< String, Object > blogMap = new LinkedHashMap<>();
                blogMap.put( "username", info.username() );
                blogMap.put( "title", info.title() );
                blogMap.put( "description", info.description() );
                blogMap.put( "entryCount", info.entryCount() );
                blogMap.put( "authorFullName", info.authorFullName() );
                result.add( blogMap );
            }
            sendJson( response, result );
        } catch ( final ProviderException e ) {
            LOG.error( "Error listing blogs: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error listing blogs: " + e.getMessage() );
        }
    }

    /**
     * Handles GET /api/blog/{username} — returns blog metadata.
     */
    private void handleGetBlog( final HttpServletRequest request, final HttpServletResponse response,
                                final String username ) throws IOException {
        try {
            final BlogManager blogManager = getEngine().getManager( BlogManager.class );
            final BlogInfo blogInfo = blogManager.getBlogInfo( username );

            if ( blogInfo == null ) {
                sendNotFound( response, "Blog not found for user: " + username );
                return;
            }

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "username", blogInfo.username() );
            result.put( "title", blogInfo.title() );
            result.put( "description", blogInfo.description() );
            result.put( "entryCount", blogInfo.entryCount() );

            // Return body and metadata separately (consistent with entry endpoint)
            final Page blogPage = blogManager.getBlog( username );
            if ( blogPage != null ) {
                final PageManager pm = getEngine().getManager( PageManager.class );
                final String rawText = pm.getPureText( blogPage.getName(), PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );
                result.put( "content", parsed.body() );
                result.put( "metadata", parsed.metadata() );
                result.put( "version", blogPage.getVersion() );

                if ( "true".equals( request.getParameter( "render" ) ) ) {
                    final Context ctx = Wiki.context().create( getEngine(), request, blogPage );
                    final RenderingManager rm = getEngine().getManager( RenderingManager.class );
                    result.put( "contentHtml", rm.textToHTML( ctx, parsed.body() ) );
                }
            }

            sendJson( response, result );
        } catch ( final Exception e ) {
            LOG.warn( "Error getting blog for {}: {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error getting blog: " + e.getMessage() );
        }
    }

    /**
     * Handles POST /api/blog — creates a blog for the current user.
     */
    private void handleCreateBlog( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        try {
            final BlogManager blogManager = getEngine().getManager( BlogManager.class );
            final Page blogPage = blogManager.createBlog( session );

            final String username = session.getLoginPrincipal().getName().toLowerCase( Locale.ROOT );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "username", username );
            result.put( "page", blogPage.getName() );

            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, result );

        } catch ( final BlogAlreadyExistsException e ) {
            sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final WikiException e ) {
            LOG.error( "Error creating blog: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error creating blog: " + e.getMessage() );
        }
    }

    /**
     * Handles DELETE /api/blog/{username} — deletes a blog.
     */
    private void handleDeleteBlog( final HttpServletRequest request, final HttpServletResponse response,
                                    final String username ) throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        try {
            final BlogManager blogManager = getEngine().getManager( BlogManager.class );

            if ( !blogManager.blogExists( username ) ) {
                sendNotFound( response, "Blog not found for user: " + username );
                return;
            }

            blogManager.deleteBlog( session, username );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "username", username );

            sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error deleting blog for {}: {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_FORBIDDEN, e.getMessage() );
        }
    }

    // ----- Entry operations -----

    /**
     * Handles GET /api/blog/{username}/entries — lists all entries.
     */
    private void handleListEntries( final HttpServletResponse response, final String username ) throws IOException {
        try {
            final Engine engine = getEngine();
            final BlogManager blogManager = engine.getManager( BlogManager.class );

            if ( !blogManager.blogExists( username ) ) {
                sendNotFound( response, "Blog not found for user: " + username );
                return;
            }

            final PageManager pm = engine.getManager( PageManager.class );
            final List< Page > entries = blogManager.listEntries( username );

            final List< Map< String, Object > > result = new ArrayList<>();
            for ( final Page entry : entries ) {
                final String rawText = pm.getPureText( entry.getName(), PageProvider.LATEST_VERSION );
                final ParsedPage parsed = FrontmatterParser.parse( rawText );
                final Map< String, Object > metadata = parsed.metadata();

                final Map< String, Object > entryMap = new LinkedHashMap<>();
                entryMap.put( "name", entry.getName().substring( entry.getName().lastIndexOf( '/' ) + 1 ) );
                entryMap.put( "title", metadata.getOrDefault( "title", "" ) );
                entryMap.put( "date", metadata.getOrDefault( "date", "" ) );
                entryMap.put( "author", metadata.getOrDefault( "author", entry.getAuthor() ) );

                // Use synopsis from frontmatter if available, otherwise truncate body
                final String body = parsed.body().trim();
                final Object synopsis = metadata.get( "synopsis" );
                final String excerpt;
                if ( synopsis != null ) {
                    excerpt = synopsis.toString();
                } else {
                    excerpt = body.length() > 200 ? body.substring( 0, 200 ) + "..." : body;
                }
                entryMap.put( "excerpt", excerpt );

                result.add( entryMap );
            }

            sendJson( response, result );

        } catch ( final ProviderException e ) {
            LOG.error( "Error listing entries for {}: {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error listing entries: " + e.getMessage() );
        }
    }

    /**
     * Handles GET /api/blog/{username}/entries/{name} — returns entry content and metadata.
     */
    private void handleGetEntry( final HttpServletRequest request, final HttpServletResponse response,
                                  final String username, final String entryName ) throws IOException {
        final Engine engine = getEngine();
        final BlogManager blogManager = engine.getManager( BlogManager.class );

        if ( !blogManager.blogExists( username ) ) {
            sendNotFound( response, "Blog not found for user: " + username );
            return;
        }

        final String pageName = BlogManager.blogPagePath( username, entryName );
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Blog entry not found: " + entryName );
            return;
        }

        final String rawText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( rawText );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", entryName );
        result.put( "content", parsed.body() );

        // Rendered HTML option
        if ( "true".equalsIgnoreCase( request.getParameter( "render" ) ) ) {
            try {
                final RenderingManager renderingManager = engine.getManager( RenderingManager.class );
                final Context context = Wiki.context().create( engine, request, page );
                final String html = renderingManager.textToHTML( context, rawText );
                result.put( "contentHtml", html );
            } catch ( final Exception e ) {
                LOG.warn( "Failed to render entry {}: {}", entryName, e.getMessage() );
                result.put( "contentHtml", null );
            }
        }

        result.put( "metadata", parsed.metadata() );
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", page.getAuthor() );
        result.put( "lastModified", page.getLastModified() );

        sendJson( response, result );
    }

    /**
     * Handles POST /api/blog/{username}/entries — creates a new entry.
     */
    private void handleCreateEntry( final HttpServletRequest request, final HttpServletResponse response,
                                     final String username ) throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        final JsonObject body;
        try {
            body = readJsonBody( request );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        if ( !body.has( "topic" ) || body.get( "topic" ).getAsString().isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "topic is required and must not be blank" );
            return;
        }

        final String topic = body.get( "topic" ).getAsString().trim();
        final String content = body.has( "content" ) && !body.get( "content" ).isJsonNull()
            ? body.get( "content" ).getAsString() : null;

        try {
            final BlogManager blogManager = getEngine().getManager( BlogManager.class );
            final Page entryPage = blogManager.createEntry( session, topic, content );

            final String entrySlug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", entrySlug );
            result.put( "page", entryPage.getName() );

            response.setStatus( HttpServletResponse.SC_CREATED );
            sendJson( response, result );

        } catch ( final WikiException e ) {
            LOG.error( "Error creating entry for {}: {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error creating entry: " + e.getMessage() );
        }
    }

    /**
     * Handles PUT /api/blog/{username} — updates Blog.md content.
     */
    private void handleUpdateBlogHome( final HttpServletRequest request, final HttpServletResponse response,
                                        final String username ) throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        final String pageName = BlogManager.blogPagePath( username, BlogManager.BLOG_HOME_PAGE );
        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Blog not found for user: " + username );
            return;
        }

        final JsonObject body;
        try {
            body = readJsonBody( request );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        final String content = body.has( "content" ) ? body.get( "content" ).getAsString() : "";

        try {
            pm.putPageText( page, content );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", BlogManager.BLOG_HOME_PAGE );
            result.put( "version", Math.max( page.getVersion(), 1 ) );

            sendJson( response, result );

        } catch ( final ProviderException e ) {
            LOG.warn( "Error updating blog home for {}: {}", username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error updating blog: " + e.getMessage() );
        }
    }

    /**
     * Handles PUT /api/blog/{username}/entries/{name} — updates entry content.
     */
    private void handleUpdateEntry( final HttpServletRequest request, final HttpServletResponse response,
                                     final String username, final String entryName ) throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        final String pageName = BlogManager.blogPagePath( username, entryName );
        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Blog entry not found: " + entryName );
            return;
        }

        final JsonObject body;
        try {
            body = readJsonBody( request );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        final String content = body.has( "content" ) ? body.get( "content" ).getAsString() : "";

        try {
            pm.putPageText( page, content );

            // Evict blog homepage cache so plugins reflect updated entry metadata
            final RenderingManager rm = engine.getManager( RenderingManager.class );
            rm.evictRenderCache( BlogManager.blogPagePath( username, BlogManager.BLOG_HOME_PAGE ) );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", entryName );
            result.put( "version", Math.max( page.getVersion(), 1 ) );

            sendJson( response, result );

        } catch ( final ProviderException e ) {
            LOG.error( "Error updating entry {} for {}: {}", entryName, username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error updating entry: " + e.getMessage() );
        }
    }

    /**
     * Handles DELETE /api/blog/{username}/entries/{name} — deletes an entry.
     */
    private void handleDeleteEntry( final HttpServletRequest request, final HttpServletResponse response,
                                     final String username, final String entryName ) throws IOException {
        final Session session = requireAuthenticated( request, response );
        if ( session == null ) {
            return;
        }

        final String pageName = BlogManager.blogPagePath( username, entryName );
        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Blog entry not found: " + entryName );
            return;
        }

        try {
            pm.deletePage( pageName );

            // Evict blog homepage cache so plugins reflect the deleted entry
            final RenderingManager rm = engine.getManager( RenderingManager.class );
            rm.evictRenderCache( BlogManager.blogPagePath( username, BlogManager.BLOG_HOME_PAGE ) );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", entryName );

            sendJson( response, result );

        } catch ( final ProviderException e ) {
            LOG.error( "Error deleting entry {} for {}: {}", entryName, username, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error deleting entry: " + e.getMessage() );
        }
    }

}
