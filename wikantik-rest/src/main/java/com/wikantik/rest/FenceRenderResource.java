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
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.markdown.extensions.fences.ServerDrawing;
import com.wikantik.markdown.extensions.fences.ServerFenceDrawer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST servlet that draws server-rendered fences (PlantUML) for editor previews.
 * <p>
 * Mapped to {@code /api/fences/*}. Handles {@code POST /api/fences/render} with body
 * {@code {language, source, page?}} ({@code page} is reserved and ignored). A drawing returns
 * {@code {lightSvg, darkSvg}}; a refusal returns {@code {error, message?, line?}}, both with HTTP 200.
 * Responses are never cacheable.
 */
public class FenceRenderResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( FenceRenderResource.class );

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        response.setHeader( "Cache-Control", "no-store" );

        final String action = extractPathParam( request );
        if( !"render".equals( action ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown fence action: '" + action + "'. Supported: render" );
            return;
        }

        final Session session = Wiki.session().find( getEngine(), request );
        if( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return;
        }

        final JsonObject body;
        try( BufferedReader reader = request.getReader() ) {
            body = JsonParser.parseReader( reader ).getAsJsonObject();
        } catch( final IOException | JsonParseException | IllegalStateException e ) {
            LOG.warn( "Fence render request rejected: invalid JSON body: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return;
        }

        final String language = getJsonString( body, "language" );
        final String source = getJsonString( body, "source" );
        if( language == null || language.isBlank() || source == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "'language' and 'source' are required" );
            return;
        }

        final Optional< ServerFenceDrawer > drawer =
                getSubsystems().rendering().renderingManager().serverFenceDrawer( language );
        if( drawer.isEmpty() ) {
            response.setStatus( HttpServletResponse.SC_BAD_REQUEST );
            sendJson( response, Map.of( "error", "unsupported-language" ) );
            return;
        }

        final ServerDrawing drawing = drawer.get().draw( source, drawer.get().defaultBudgetMs() );
        final Map< String, Object > out = new LinkedHashMap<>();
        if( drawing.isDrawn() ) {
            out.put( "lightSvg", drawing.lightSvg() );
            out.put( "darkSvg", drawing.darkSvg() );
        } else {
            out.put( "error", drawing.reason() );
            if( drawing.message() != null ) {
                out.put( "message", drawing.message() );
            }
            if( drawing.line() != 0 ) {
                out.put( "line", drawing.line() );
            }
        }
        sendJson( response, out );
    }
}
