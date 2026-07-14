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
package com.wikantik.connectors.web;

import com.wikantik.connectors.http.CappedBodySubscriber;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@link PageFetcher} backed by {@code java.net.http.HttpClient}. Never throws from {@link #fetch(String)}.
 *  Response bodies are capped at {@code maxBodyBytes} (default {@value #DEFAULT_MAX_BODY_BYTES}) —
 *  an oversized body fails closed (status 0) instead of buffering unboundedly (OOM defense). */
public final class HttpPageFetcher implements PageFetcher {
    private static final Logger LOG = LogManager.getLogger( HttpPageFetcher.class );

    /** 10 MiB — generous for HTML/XML pages, sitemaps, and feeds; a single page bigger than this is
     *  not something we want to mirror into a wiki page anyway. */
    public static final int DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final String userAgent;
    private final Duration timeout;
    private final int maxBodyBytes;
    private final HttpClient client;

    public HttpPageFetcher( final String userAgent, final Duration timeout ) {
        this( userAgent, timeout, DEFAULT_MAX_BODY_BYTES );
    }

    public HttpPageFetcher( final String userAgent, final Duration timeout, final int maxBodyBytes ) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.maxBodyBytes = maxBodyBytes;
        this.client = HttpClient.newBuilder()
            .followRedirects( HttpClient.Redirect.NORMAL )
            .connectTimeout( timeout )
            .build();
    }

    @Override
    public FetchResult fetch( final String url ) {
        try {
            final HttpRequest request = HttpRequest.newBuilder( URI.create( url ) )
                .header( "User-Agent", userAgent )
                .timeout( timeout )
                .GET()
                .build();
            final HttpResponse< byte[] > response = client.send( request,
                info -> new CappedBodySubscriber( maxBodyBytes ) );
            return new FetchResult(
                response.statusCode(),
                response.headers().firstValue( "content-type" ).orElse( null ),
                response.body(),
                response.uri().toString() );
        } catch ( final IOException e ) {
            LOG.warn( "fetch failed for {}: {}", url, e.getMessage() );
            return new FetchResult( 0, null, new byte[0], url );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "fetch interrupted for {}: {}", url, e.getMessage() );
            return new FetchResult( 0, null, new byte[0], url );
        } catch ( final RuntimeException e ) {
            // e.g. a malformed/non-http seed URL: URI.create / HttpRequest.newBuilder throw
            // IllegalArgumentException. Fail-closed — the fetcher MUST never throw (a bad seed
            // would otherwise escape poll() → 500 on the manual admin sync trigger).
            LOG.warn( "fetch skipped for malformed url {}: {}", url, e.getMessage() );
            return new FetchResult( 0, null, new byte[0], url );
        }
    }
}
