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

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.content.WikiToMarkdownConverter;
import com.wikantik.content.WikiToMarkdownConverter.ConversionResult;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST servlet for converting legacy JSPWiki wiki syntax to Markdown.
 * <p>
 * Mapped to {@code /api/convert/*}. Handles:
 * <ul>
 *   <li>{@code POST /api/convert/wiki-to-markdown} — Convert wiki syntax content to Markdown</li>
 * </ul>
 */
public class ConvertResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ConvertResource.class );

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );
        if( !"wiki-to-markdown".equals( action ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown conversion: " + action );
            return;
        }

        // Require authenticated user
        final Session session = Wiki.session().find( getEngine(), request );
        if( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return;
        }

        // Parse JSON body
        final JsonObject body;
        try( BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return;
        }

        final String content = body.has( "content" ) ? body.get( "content" ).getAsString() : "";

        LOG.debug( "Converting wiki syntax to markdown ({} chars)", content.length() );

        final ConversionResult result = WikiToMarkdownConverter.convert( content );

        final Map< String, Object > responseBody = new LinkedHashMap<>();
        responseBody.put( "markdown", result.markdown() );
        responseBody.put( "warnings", result.warnings() );

        sendJson( response, responseBody );
    }
}
