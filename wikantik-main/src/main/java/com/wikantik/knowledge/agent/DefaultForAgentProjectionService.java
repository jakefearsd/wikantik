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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.agent.HeadingOutline;
import com.wikantik.api.agent.KeyFact;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.agent.RecentChange;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.Verification;
import com.wikantik.cache.CachingManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of {@link ForAgentProjectionService}. Composes the
 * four extractors ({@link HeadingsOutlineExtractor}, {@link KeyFactsExtractor},
 * {@link RecentChangesAdapter}, {@link McpToolHintsResolver}) with per-field
 * graceful degradation: each extractor runs in a try/catch, and any failure is
 * recorded on the projection's {@code missingFields} list rather than
 * propagated. The result is always either {@link Optional#empty()} (canonical
 * id unknown) or a populated projection — never an exception.
 *
 * <p>Caches successful projections by {@code canonicalId + ":" + updatedAtMillis}
 * in {@link CachingManager#CACHE_FOR_AGENT}. A page update produces a new
 * {@code updatedAtMillis}, so the new request misses the cache and gets a
 * fresh build. Stale entries evict on heap pressure — no event listener is
 * required.</p>
 */
public class DefaultForAgentProjectionService implements ForAgentProjectionService {

    private static final Logger LOG = LogManager.getLogger( DefaultForAgentProjectionService.class );

    private static final int RECENT_CHANGES_LIMIT = 5;

    private final StructuralIndexService index;
    private final PageManager pageManager;
    private final CachingManager cache;
    private final ForAgentMetrics metrics;

    private final HeadingsOutlineExtractor headings  = new HeadingsOutlineExtractor();
    private final KeyFactsExtractor        keyFacts  = new KeyFactsExtractor();
    private final McpToolHintsResolver     toolHints = new McpToolHintsResolver();
    private final RecentChangesAdapter     recents;

    public DefaultForAgentProjectionService(
            final StructuralIndexService index,
            final PageManager pageManager,
            final CachingManager cache,
            final ForAgentMetrics metrics ) {
        this.index = index;
        this.pageManager = pageManager;
        this.cache = cache;
        this.metrics = metrics;
        this.recents = new RecentChangesAdapter( pageManager );
    }

    @Override
    public Optional< ForAgentProjection > project( final String canonicalId ) {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            return Optional.empty();
        }
        final Optional< PageDescriptor > maybe = index.getByCanonicalId( canonicalId );
        if ( maybe.isEmpty() ) {
            return Optional.empty();
        }
        final PageDescriptor d = maybe.get();
        final long updatedMs = d.updated() == null ? 0L : d.updated().toEpochMilli();
        final Serializable key = canonicalId + ":" + updatedMs;

        final ForAgentProjection cached = readFromCache( key );
        if ( cached != null ) {
            return Optional.of( cached );
        }
        final ForAgentProjection built = build( d );
        writeToCache( key, built );
        recordMetric( built );
        return Optional.of( built );
    }

    private ForAgentProjection build( final PageDescriptor d ) {
        final List< String > missing = new ArrayList<>();

        // Verification — falls back to unverified() (provisional + both audiences) when absent.
        final Verification verification = index.verificationOf( d.canonicalId() ).orElseGet( Verification::unverified );

        // Body + frontmatter.
        Map< String, Object > frontmatter = Map.of();
        String body = "";
        try {
            final String raw = pageManager.getPureText( d.slug(), WikiProvider.LATEST_VERSION );
            if ( raw != null && !raw.isEmpty() ) {
                final ParsedPage parsed = FrontmatterParser.parse( raw );
                frontmatter = parsed.metadata();
                body = parsed.body();
            }
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: getPureText({}) failed — body & key_facts & headings will degrade: {}",
                    d.slug(), e.getMessage() );
            missing.add( "body" );
        }

        // Headings.
        List< HeadingOutline > outline = List.of();
        try {
            outline = headings.extract( body );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: headings extractor threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "headings_outline" );
        }

        // Key facts.
        List< KeyFact > facts = List.of();
        try {
            facts = keyFacts.extract( frontmatter, body );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: key_facts extractor threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "key_facts" );
        }

        // Relations.
        List< RelationEdge > outgoing = List.of();
        List< RelationEdge > incoming = List.of();
        try {
            outgoing = index.outgoingRelations( d.canonicalId(), Optional.empty() );
            incoming = index.incomingRelations( d.canonicalId(), Optional.empty() );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: relations lookup threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "typed_relations" );
        }

        // Recent changes.
        List< RecentChange > changes;
        try {
            changes = recents.recentChanges( d.slug(), RECENT_CHANGES_LIMIT );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: recent_changes threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "recent_changes" );
            changes = List.of();
        }

        // MCP tool hints.
        List< McpToolHint > hints = List.of();
        try {
            hints = toolHints.resolve( frontmatter, d.tags(), d.cluster() );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: mcp_tool_hints threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "mcp_tool_hints" );
        }

        return new ForAgentProjection(
                d.canonicalId(),
                d.slug(),
                d.title(),
                d.type() == null ? null : d.type().asFrontmatterValue(),
                d.cluster(),
                verification.audience(),
                verification.confidence(),
                verification.verifiedAt(),
                verification.verifiedBy(),
                d.updated(),
                d.summary(),
                facts,
                outline,
                outgoing,
                incoming,
                changes,
                hints,
                /* runbook */ null,                          // Phase 3 fills this in.
                "/api/pages/" + d.slug(),
                "/wiki/" + d.slug() + "?format=md",
                !missing.isEmpty(),
                missing );
    }

    /* ----------------------------------------------------------------- caching */

    private ForAgentProjection readFromCache( final Serializable key ) {
        if ( cache == null || !cache.enabled( CachingManager.CACHE_FOR_AGENT ) ) {
            return null;
        }
        try {
            // Supplier returns null — we want hits-only semantics here. The miss path
            // is handled below with an explicit put().
            return ( ForAgentProjection ) cache.get( CachingManager.CACHE_FOR_AGENT, key, () -> null );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: cache read failed for key {} — bypassing cache: {}", key, e.getMessage() );
            return null;
        }
    }

    private void writeToCache( final Serializable key, final ForAgentProjection built ) {
        if ( cache == null || !cache.enabled( CachingManager.CACHE_FOR_AGENT ) ) {
            return;
        }
        try {
            cache.put( CachingManager.CACHE_FOR_AGENT, key, built );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: cache write failed for key {}: {}", key, e.getMessage() );
        }
    }

    private void recordMetric( final ForAgentProjection p ) {
        if ( metrics == null ) return;
        // Approximate: serialised size estimate based on lengths of variable-length fields.
        // The REST/MCP layer can record the actual serialised byte count for hot paths;
        // both feed the same histogram so cached-only callers and live-build callers
        // both contribute samples.
        final int estimate = 256
                + nullSafeLen( p.summary() )
                + nullSafeLen( p.title() )
                + p.keyFacts().stream().mapToInt( kf -> kf.text().length() ).sum()
                + p.headingsOutline().stream().mapToInt( h -> h.text().length() ).sum();
        metrics.recordBytes( estimate );
    }

    private static int nullSafeLen( final String s ) {
        return s == null ? 0 : s.length();
    }
}
