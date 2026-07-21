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
package com.wikantik.connectors;

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * GoF Template Method for credential-backed, full-corpus connectors (Confluence,
 * GitHub, Google Drive). {@link #poll} owns the fail-closed contract ONCE —
 * previously each connector re-implemented it by hand, and a future connector
 * author could silently get one of the degrade branches wrong:
 *
 * <ul>
 *   <li>token lookup throws → skipped cycle, {@code complete=false}, input cursor;</li>
 *   <li>token absent/blank → same. An empty COMPLETE batch from a full-corpus
 *       connector would tombstone every previously-synced page, so "couldn't
 *       enumerate" must never read as "source is empty";</li>
 *   <li>{@link #fetchItems} throws → empty INCOMPLETE batch ({@code poll()} never throws);</li>
 *   <li>untrusted listing (API-side truncation/malformed entries) → items delivered
 *       but {@code complete=false} with the input cursor — no tombstone derivation.</li>
 * </ul>
 *
 * Subclasses implement only {@link #fetchItems} (the provider-specific enumeration)
 * plus the two log labels. Logging keeps the subclass's logger category.
 */
public abstract class TokenAuthenticatedSourceConnector implements SourceConnector {

    private final Logger log = LogManager.getLogger( getClass() );

    private final String connectorId;
    private final Supplier< Optional< String > > tokenSupplier;

    protected TokenAuthenticatedSourceConnector( final String connectorId,
            final Supplier< Optional< String > > tokenSupplier ) {
        this.connectorId = connectorId;
        this.tokenSupplier = tokenSupplier;
    }

    @Override public final String connectorId() { return connectorId; }
    @Override public final boolean reflectsFullCorpus() { return true; }

    /** Provider label used in log messages, e.g. {@code "confluence"}. */
    protected abstract String providerLabel();

    /** Credential name used in the missing-token log message, e.g. {@code "api_token"}. */
    protected abstract String credentialLabel();

    /**
     * Provider-specific enumeration with a live token. May throw — the template
     * degrades any exception to an empty INCOMPLETE batch. Return
     * {@code trusted=false} when the listing was truncated or partially malformed;
     * the template then withholds the cursor advance and completeness flag.
     */
    protected abstract FetchOutcome fetchItems( String token ) throws Exception;

    /** Result of one provider enumeration: the items plus whether the listing can be trusted. */
    public record FetchOutcome( List< SourceItem > items, boolean trusted ) {}

    @Override
    public final SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token;
        try {
            token = tokenSupplier.get();
        } catch ( final RuntimeException e ) {
            log.warn( "{} '{}': token lookup failed — skipping sync: {}",
                providerLabel(), connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( token.isEmpty() || token.get().isBlank() ) {
            log.warn( "{} '{}': no {} available (credential store disabled or token not set) — skipping sync",
                providerLabel(), connectorId, credentialLabel() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final FetchOutcome outcome;
        try {
            outcome = fetchItems( token.get() );
        } catch ( final Exception e ) {
            log.warn( "{} '{}': sync failed, skipping cycle: {}", providerLabel(), connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( !outcome.trusted() ) {
            return new SyncBatch( outcome.items(), List.of(), cursor, false );
        }
        return new SyncBatch( outcome.items(), List.of(),
            new SyncCursor( String.valueOf( outcome.items().size() ) ), true );
    }
}
