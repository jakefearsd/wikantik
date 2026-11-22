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

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.wikantik.WikiEngine;
import com.wikantik.util.TextUtil;
import com.wikantik.work.CodeEvent;
import com.wikantik.work.HookDelivery;
import com.wikantik.work.WorkCodeHostService;
import com.wikantik.work.WorkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * The inbound code-host webhook: {@code POST /api/work/hooks/{productKey}/{github|generic}}. It has no session, no API
 * key and no principal: the only credential is the HMAC of the raw body under the product's hook secret. Every refusal
 * (work off, endpoint off, wrong method or path, unknown product, no or disabled hook, missing or wrong signature) is
 * the same 404 with the same bytes, so a caller learns nothing about which hooks exist. Order of checks: body size
 * (413, before anything is checked), the hook and its signature over the raw bytes, content type (415), then parse.
 * Once a delivery verifies it is always applied, even a ping or an unparseable body, so the hook's "last delivery"
 * moves; the answer is 204 whatever the body held. Receive only: the wiki never calls the code host. The secret, the
 * signature header and the body are never logged.
 */
public class WorkHookResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( WorkHookResource.class );

    /** Declared in {@code ini/wikantik.properties}. */
    static final String PROP_ENABLED = "wikantik.work.codehost.enabled";
    static final String PROP_MAX_BODY = "wikantik.work.codehost.maxBodyBytes";
    static final long DEFAULT_MAX_BODY = 1_048_576L;
    static final long MIN_MAX_BODY = 1024L;
    private static final int ID_LIMIT = 64;

    private static final String GITHUB = "github";
    private static final String GENERIC = "generic";
    private static final String SIGNATURE_GITHUB = "X-Hub-Signature-256";
    private static final String SIGNATURE_GENERIC = "X-Wikantik-Signature";
    private static final String NOT_FOUND_BODY = WorkJson.GSON.toJson( WorkJson.errorBody( 404, "Not found." ) );
    private static final String TOO_LARGE_BODY = WorkJson.GSON.toJson( WorkJson.errorBody( 413, "Payload too large." ) );
    private static final String UNSUPPORTED_BODY = WorkJson.GSON.toJson(
        WorkJson.errorBody( 415, "Send the payload as application/json." ) );
    private static final String UNAVAILABLE_BODY = WorkJson.GSON.toJson(
        WorkJson.errorBody( 503, "Work is unavailable right now." ) );

    // ---- seams (overridden by unit tests)

    /** The work service, or null when {@code wikantik.work.enabled} is false. */
    protected WorkService workService() {
        return getEngine() instanceof WikiEngine we ? we.workService() : null;
    }

    /** Whether {@code wikantik.work.codehost.enabled} is true. */
    protected boolean codeHostEnabled() {
        return TextUtil.getBooleanProperty( properties(), PROP_ENABLED, false );
    }

    /** The largest body accepted, in bytes: the configured value, at least 1024. */
    protected long maxBodyBytes() {
        final long configured = TextUtil.getIntegerProperty( properties(), PROP_MAX_BODY, (int) DEFAULT_MAX_BODY );
        return Math.max( MIN_MAX_BODY, configured );
    }

    protected Properties properties() {
        return getEngine().getWikiProperties();
    }

    // ---- the one entry point: no CORS, no session, any method but POST is the plain 404

    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String[] route = "POST".equals( request.getMethod() ) ? route( request ) : null;
        final WorkService svc = route == null ? null : workService();
        if ( svc == null || !codeHostEnabled() ) {
            reply( response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_BODY );
            return;
        }
        final long max = maxBodyBytes();
        if ( request.getContentLengthLong() > max ) {
            reply( response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, TOO_LARGE_BODY );
            return;
        }
        final byte[] body = request.getInputStream().readNBytes( (int) Math.min( max + 1, Integer.MAX_VALUE ) );
        if ( body.length > max ) {
            reply( response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, TOO_LARGE_BODY );
            return;
        }
        deliver( svc, route[ 0 ], route[ 1 ], body, request, response );
    }

    /** {@code {product, format}} for {@code /<product>/<github|generic>}, else null. */
    private static String[] route( final HttpServletRequest request ) {
        final String path = request.getPathInfo();
        if ( path == null || !path.startsWith( "/" ) ) {
            return null;
        }
        final String[] parts = path.substring( 1 ).split( "/", -1 );
        if ( parts.length != 2 || parts[ 0 ].isEmpty() || parts[ 0 ].length() > ID_LIMIT ) {
            return null;
        }
        final String format = parts[ 1 ].toLowerCase( Locale.ROOT );
        return GITHUB.equals( format ) || GENERIC.equals( format ) ? new String[] { parts[ 0 ], format } : null;
    }

    private void deliver( final WorkService svc, final String product, final String format, final byte[] body,
                          final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final WorkCodeHostService host = svc.features().codeHost();
        try {
            final String signature = request.getHeader( GITHUB.equals( format ) ? SIGNATURE_GITHUB : SIGNATURE_GENERIC );
            final Optional< HookDelivery > verified = host.authenticate( product, format, body, signature );
            if ( verified.isEmpty() ) {
                host.rejected();
                LOG.info( "work codehost delivery product={} format={} event=- delivery=- outcome=rejected applied=0 duplicate=0 ignored=0",
                    HookIntake.safe( product ), format );
                reply( response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_BODY );
                return;
            }
            final String type = request.getContentType();
            if ( type != null && type.toLowerCase( Locale.ROOT ).startsWith( "application/x-www-form-urlencoded" ) ) {
                LOG.info( "work codehost delivery product={} format={} event=- delivery=- outcome=ignored applied=0 duplicate=0 ignored=0 reason=form-encoded",
                    HookIntake.safe( product ), format );
                reply( response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_BODY );
                return;
            }
            HookIntake.accept( host, verified.get(), format, body, request );
            response.setStatus( HttpServletResponse.SC_NO_CONTENT );
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "work codehost delivery failed product={} format={} cause={} sqlState={}", HookIntake.safe( product ), format,
                e.getClass().getSimpleName(), e instanceof SQLException s ? s.getSQLState() : "-" );
            reply( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_BODY );
        }
    }

    private static void reply( final HttpServletResponse response, final int status, final String body ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.setHeader( "Cache-Control", "no-store" );
        response.getWriter().write( body );
    }
}
