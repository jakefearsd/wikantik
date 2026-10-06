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
package com.wikantik.auth.apikeys;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports, at WARN, every client still using the pre-2.4.54 scope name {@code "mcp"} (now
 * {@code "mcp_admin"}), so an operator can find and migrate them. All reports go to this one logger
 * category. A request authenticated by a key stored as {@code "mcp"} is reported at most once per
 * {@link #INTERVAL} for each key and client address; a mint request naming {@code "mcp"} is always
 * reported. The key keeps working either way.
 */
public final class LegacyScopeUseLog {

    /** How long the same key used from the same address stays quiet after a report. */
    static final Duration INTERVAL = Duration.ofHours( 1 );

    /** Above this many remembered (key, address) pairs, expired ones are dropped. */
    private static final int PRUNE_THRESHOLD = 1_000;

    private static final Logger LOG = LogManager.getLogger( LegacyScopeUseLog.class );

    private final Clock clock;
    private final Map< String, Instant > lastReported = new ConcurrentHashMap<>();

    public LegacyScopeUseLog( final Clock clock ) {
        this.clock = clock;
    }

    /** Reports a request on {@code surface} authenticated by {@code key}, if its scope is stored under the legacy name. */
    public void keyUsed( final String surface, final ApiKeyService.Record key, final HttpServletRequest request ) {
        if ( !key.legacyScope() ) {
            return;
        }
        final Instant now = clock.instant();
        final String ip = request.getRemoteAddr();
        final Instant previous = lastReported.get( key.id() + "|" + ip );
        if ( previous != null && now.isBefore( previous.plus( INTERVAL ) ) ) {
            return;
        }
        lastReported.put( key.id() + "|" + ip, now );
        if ( lastReported.size() > PRUNE_THRESHOLD ) {
            lastReported.values().removeIf( at -> !now.isBefore( at.plus( INTERVAL ) ) );
        }
        LOG.warn( "{} request used an API key with the deprecated scope 'mcp': key id={} label='{}' principal={} "
                        + "ip={} user-agent='{}'. It still has full admin access; mint a replacement with scope "
                        + "'mcp_admin' and revoke this one. Repeats for this key and address at most every {} minutes.",
                surface, key.id(), key.label(), key.principalLogin(), ip, request.getHeader( "User-Agent" ),
                INTERVAL.toMinutes() );
    }

    /** Reports a request to {@code endpoint}, made by {@code login}, that minted a key naming the scope {@code "mcp"}. */
    public static void mintRequested( final String endpoint, final String login, final HttpServletRequest request ) {
        LOG.warn( "API key requested with the deprecated scope 'mcp' at {} by {} from ip={} user-agent='{}'; "
                        + "minted as 'mcp_admin'. Update the client to send scope 'mcp_admin'.",
                endpoint, login, request.getRemoteAddr(), request.getHeader( "User-Agent" ) );
    }
}
