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
package com.wikantik.derived;

import com.google.gson.JsonObject;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.connectors.config.ConnectorConfigCodec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Dry-run "test connection" probe backing the connector wizard's Test step
 * ({@code POST /admin/connectors/test} and {@code POST /admin/connectors/{id}/test} in
 * {@code wikantik-rest}). Two entry points:
 *
 * <ul>
 *   <li>{@link #testUnsaved} — assembles a throwaway {@link SourceConnector} from an unsaved
 *       {@code {type, config}} payload (dry-run-capped via {@link ConnectorConfigCodec#toConfigForTest})
 *       and polls it once.</li>
 *   <li>{@link #testSaved} — polls an already-assembled, already-registered connector once, at its
 *       live (uncapped) config. Used post-OAuth for {@code gdrive}, where caps are already
 *       user-chosen.</li>
 * </ul>
 *
 * <p>Both apply the same judgment to the resulting {@link SyncBatch}: an empty batch that is also
 * <em>not</em> a trustworthy full snapshot ({@code items.isEmpty() && !complete}) means the probe
 * could not confirm reachability — {@code ok=false}. Anything else (including a genuinely empty
 * but healthy source, {@code items.isEmpty() && complete}) is {@code ok=true}.
 *
 * <p><b>Secret hygiene:</b> {@link #testUnsaved}'s {@code transientCredentials} are layered over
 * the real {@link CredentialStore} for the duration of one poll via an in-memory overlay — never
 * persisted, never logged, never echoed back in the {@link TestResult}. This is a read-only probe:
 * neither caller nor this class writes any state (no audit entry, no sync-state row, no page).
 */
public final class ConnectorTestService {

    private static final Logger LOG = LogManager.getLogger( ConnectorTestService.class );

    private ConnectorTestService() {}

    /** Outcome of one dry-run probe. {@code sample} holds at most the first 3 {@code sourceUri}s
     *  of the polled batch — never full item content, never credentials. */
    public record TestResult( boolean ok, int found, List< String > sample, boolean complete, String message ) {}

    /**
     * Builds a throwaway {@link SourceConnector} for {@code type}/{@code config} (dry-run-capped)
     * and polls it once. {@code id} is a throwaway assembly id (it only feeds the per-connector
     * credential-supplier lambdas {@link com.wikantik.derived.ConnectorAssembler#build} constructs,
     * e.g. {@code credStore.get( id, "token" )} — the credential overlay below matches on
     * credential NAME, not on {@code id}, so any id works). {@code transientCredentials} overlay
     * {@code realStore}: a name present in the map wins; otherwise the real store is consulted
     * (null/disabled-safe — a {@code null} or disabled {@code realStore} yields {@link
     * Optional#empty()} for that name).
     */
    public static TestResult testUnsaved( final String id, final String type, final JsonObject config,
            final Map< String, String > transientCredentials, final CredentialStore realStore ) {
        return testUnsaved( id, type, config, transientCredentials, realStore,
            ( cfg, store ) -> ConnectorAssembler.build( id, type, cfg, store ) );
    }

    /** Package-visible seam: {@code assembler} is injectable so unit tests can supply a stub
     *  {@link SourceConnector} instead of exercising {@link ConnectorAssembler}'s real (network-
     *  making) connector construction. Production callers always go through the public overload
     *  above, which fixes {@code assembler} to {@code ConnectorAssembler::build}. */
    static TestResult testUnsaved( final String id, final String type, final JsonObject config,
            final Map< String, String > transientCredentials, final CredentialStore realStore,
            final BiFunction< Object, CredentialStore, Optional< SourceConnector > > assembler ) {
        final Object configForTest = ConnectorConfigCodec.toConfigForTest( type, config );
        final CredentialStore overlay = overlayStore( transientCredentials, realStore );
        final Optional< SourceConnector > connector = assembler.apply( configForTest, overlay );
        if ( connector.isEmpty() ) {
            LOG.warn( "connector test probe: could not assemble a connector for type '{}'", type );
            return new TestResult( false, 0, List.of(), false, "unable to build a connector for type: " + type );
        }
        return probe( connector.get() );
    }

    /** Polls an already-assembled, already-registered connector once at its live (uncapped)
     *  config — used for the {@code POST /admin/connectors/{id}/test} route. */
    public static TestResult testSaved( final SourceConnector connector ) {
        return probe( connector );
    }

    private static TestResult probe( final SourceConnector connector ) {
        final SyncBatch batch;
        try {
            batch = connector.poll( null );
        } catch ( final RuntimeException e ) {
            // SourceConnector#poll's contract says it must never throw, but this is a dry-run
            // probe running in the request thread — degrade rather than trust the contract blindly.
            // Log only the connector id and the exception's CLASS — never its message or a stack
            // trace (both would print e.toString(), i.e. the message, as their first line). A
            // connector's HTTP-client exception can echo request internals (URL, headers) that may
            // carry credential material, and this class's contract is that no credential material —
            // transient or otherwise — is ever logged, so this errs on the side of under-logging.
            LOG.warn( "connector test probe for '{}' threw despite the poll()-never-throws contract ({})",
                connector.connectorId(), e.getClass().getSimpleName() );
            return new TestResult( false, 0, List.of(), false, "test probe failed: an unexpected error occurred" );
        }

        final List< SourceItem > items = batch.items();
        if ( items.isEmpty() && !batch.complete() ) {
            return new TestResult( false, 0, List.of(), false, "source unreachable or not authorized — no items returned" );
        }
        final List< String > sample = items.stream().limit( 3 ).map( SourceItem::sourceUri ).toList();
        return new TestResult( true, items.size(), sample, batch.complete(),
            "reachable — found " + items.size() + " item(s) in a capped probe" );
    }

    /** In-memory {@link CredentialStore} overlay: {@code get} returns the transient value when
     *  {@code name} is present in {@code transientCredentials} (matched on name alone — {@code
     *  connectorId} is ignored, since the throwaway assembly id in {@link #testUnsaved} need not
     *  match anything the caller supplied), else delegates to {@code realStore} (null/disabled-safe).
     *  {@code put}/{@code delete} are no-ops — a dry-run probe never mutates real credential state;
     *  transient values are held only in this short-lived overlay, never persisted. */
    private static CredentialStore overlayStore( final Map< String, String > transientCredentials,
            final CredentialStore realStore ) {
        final Map< String, String > overlay = transientCredentials == null ? Map.of() : Map.copyOf( transientCredentials );
        return new CredentialStore() {
            @Override public boolean enabled() {
                return !overlay.isEmpty() || ( realStore != null && realStore.enabled() );
            }
            @Override public void put( final String connectorId, final String name, final String secret ) {
                LOG.warn( "connector test probe: ignoring unexpected credential write for '{}' — dry-run probes never persist secrets", name );
            }
            @Override public Optional< String > get( final String connectorId, final String name ) {
                if ( overlay.containsKey( name ) ) return Optional.of( overlay.get( name ) );
                if ( realStore == null || !realStore.enabled() ) return Optional.empty();
                return realStore.get( connectorId, name );
            }
            @Override public List< String > list( final String connectorId ) {
                return realStore == null ? List.of() : realStore.list( connectorId );
            }
            @Override public void delete( final String connectorId, final String name ) {
                LOG.warn( "connector test probe: ignoring unexpected credential delete for '{}' — dry-run probes never mutate real credential state", name );
            }
        };
    }
}
