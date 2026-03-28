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
import com.google.gson.JsonParser;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST servlet for page comment operations.
 * <p>
 * Mapped to {@code /api/comments/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/comments/PageName} - List all comments on a page</li>
 *   <li>{@code POST /api/comments/PageName} - Add a new comment to a page</li>
 * </ul>
 * <p>
 * Comments are stored as Markdown sections appended to the page text using a special
 * HTML comment marker format:
 * <pre>
 * &lt;!-- comment:author=JohnDoe:date=2026-03-28T12:00:00Z --&gt;
 * &gt; **JohnDoe** &mdash; March 28, 2026:
 * &gt;
 * &gt; This is my comment text.
 * </pre>
 */
public class CommentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( CommentResource.class );

    /** Pattern to match comment marker blocks in page text. */
    static final Pattern COMMENT_MARKER_PATTERN = Pattern.compile(
            "<!-- comment:author=(.+?):date=(.+?) -->",
            Pattern.MULTILINE
    );

    /**
     * Pattern to extract quoted comment text lines following a marker.
     * Matches lines starting with {@code > } (blockquote) after the header line.
     */
    private static final Pattern BLOCKQUOTE_LINE = Pattern.compile( "^> (.*)$", Pattern.MULTILINE );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "view" ) ) return;

        LOG.debug( "GET comments: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        final String rawText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final List< Map< String, String > > comments = parseComments( rawText != null ? rawText : "" );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pageName", pageName );
        result.put( "comments", comments );

        sendJson( response, result );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pageName = extractPathParam( request );
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "comment" ) ) return;

        LOG.debug( "POST comment: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );

        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        // Parse JSON body
        final JsonObject body;
        try ( final BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body: " + e.getMessage() );
            return;
        }

        if ( !body.has( "text" ) || body.get( "text" ).getAsString().isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }

        final String commentText = body.get( "text" ).getAsString();

        // Resolve the user from the session
        final Session wikiSession = Wiki.session().find( engine, request );
        final String author = wikiSession.getUserPrincipal().getName();

        // Build the comment block
        final Instant now = Instant.now();
        final String dateStr = DateTimeFormatter.ISO_INSTANT.format( now );
        final String commentBlock = buildCommentBlock( author, dateStr, commentText );

        // Append comment to page text
        final String currentText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final String newText = ( currentText != null ? currentText : "" ) + commentBlock;

        try {
            final PageSaveHelper helper = new PageSaveHelper( engine );
            final SaveOptions options = SaveOptions.builder()
                    .changeNote( "Added comment" )
                    .author( author )
                    .build();
            helper.saveText( pageName, newText, options );

            final Map< String, String > comment = new LinkedHashMap<>();
            comment.put( "author", author );
            comment.put( "date", dateStr );
            comment.put( "text", commentText );

            sendJson( response, comment );

        } catch ( final WikiException e ) {
            LOG.error( "Error adding comment to page {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error adding comment: " + e.getMessage() );
        }
    }

    /**
     * Parses comment blocks from page text.
     *
     * @param pageText the raw page text
     * @return list of comment maps with author, date, and text keys
     */
    static List< Map< String, String > > parseComments( final String pageText ) {
        final List< Map< String, String > > comments = new ArrayList<>();
        final Matcher matcher = COMMENT_MARKER_PATTERN.matcher( pageText );

        while ( matcher.find() ) {
            final String author = matcher.group( 1 );
            final String date = matcher.group( 2 );

            // Extract the blockquoted text after the marker
            final int markerEnd = matcher.end();
            // Find the extent of blockquoted lines after the marker
            final String remaining = pageText.substring( markerEnd );
            final StringBuilder textBuilder = new StringBuilder();

            // Skip the header line (> **Author** -- Date:) and capture subsequent > lines
            final String[] lines = remaining.split( "\n" );
            boolean pastHeader = false;
            boolean inBlockquote = false;

            for ( final String line : lines ) {
                if ( line.startsWith( "> " ) || line.equals( ">" ) ) {
                    if ( !pastHeader ) {
                        // First blockquote line is the header — skip it
                        pastHeader = true;
                        inBlockquote = true;
                        continue;
                    }
                    inBlockquote = true;
                    // Extract the text after "> "
                    final String content = line.length() > 2 ? line.substring( 2 ) : "";
                    if ( !content.isEmpty() || textBuilder.length() > 0 ) {
                        if ( textBuilder.length() > 0 ) {
                            textBuilder.append( "\n" );
                        }
                        textBuilder.append( content );
                    }
                } else if ( line.trim().isEmpty() && !inBlockquote ) {
                    // Skip blank lines before blockquote starts
                    continue;
                } else if ( inBlockquote && line.trim().isEmpty() ) {
                    // End of blockquote
                    break;
                } else if ( pastHeader ) {
                    // Non-blockquote line after blockquote started — end of comment
                    break;
                }
            }

            final Map< String, String > comment = new LinkedHashMap<>();
            comment.put( "author", author );
            comment.put( "date", date );
            comment.put( "text", textBuilder.toString().trim() );
            comments.add( comment );
        }

        return comments;
    }

    /**
     * Builds a comment block in the standard Markdown format.
     *
     * @param author the comment author name
     * @param dateStr the ISO 8601 date string
     * @param text the comment text
     * @return the formatted comment block
     */
    static String buildCommentBlock( final String author, final String dateStr, final String text ) {
        // Format a human-readable date from the ISO string
        String humanDate;
        try {
            final Instant instant = Instant.parse( dateStr );
            humanDate = DateTimeFormatter.ofPattern( "MMMM d, yyyy" )
                    .withZone( ZoneOffset.UTC )
                    .format( instant );
        } catch ( final DateTimeParseException e ) {
            humanDate = dateStr;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( "\n\n<!-- comment:author=" ).append( author ).append( ":date=" ).append( dateStr ).append( " -->\n" );
        sb.append( "> **" ).append( author ).append( "** — " ).append( humanDate ).append( ":\n" );
        sb.append( "> \n" );

        // Handle multiline comment text
        final String[] lines = text.split( "\n" );
        for ( final String line : lines ) {
            sb.append( "> " ).append( line ).append( "\n" );
        }

        return sb.toString();
    }

}
