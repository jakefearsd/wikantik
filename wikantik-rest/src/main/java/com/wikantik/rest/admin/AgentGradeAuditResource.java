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
package com.wikantik.rest.admin;

import com.google.gson.Gson;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.*;
import com.wikantik.pagegraph.spine.ConfidenceComputer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GET /admin/agent-grade-audit?limit=N&offset=M — paginated weak-signal report
 * helping authors find pages worth manual improvement without imposing a
 * frontmatter schema. Behind {@code AdminAuthFilter} (AllPermission) — no
 * additional auth check inside this resource.
 *
 * <p>Companion to the derived {@code agent_hints} block on the /for-agent
 * projection: the projection auto-fills hints, this endpoint flags pages
 * whose underlying signals are too weak to derive useful hints from.</p>
 */
public final class AgentGradeAuditResource {

    private static final Logger LOG = LoggerFactory.getLogger( AgentGradeAuditResource.class );

    private static final Gson GSON = new Gson();
    private static final Pattern GENERIC = Pattern.compile(
            "^\\s*(an?\\s+)?index of (pages?|articles?|content)\\s+(on|about|covering|for)\\b",
            Pattern.CASE_INSENSITIVE );
    /**
     * Filter limit cap from {@link StructuralFilter}; v1 audit covers up to this many pages.
     * TODO: if the corpus exceeds 1000 pages, cursor-based pagination will be needed in a follow-up.
     */
    private static final int SCAN_LIMIT = 1000;

    private final StructuralIndexService index;
    private final ReferenceManager refs;
    private final ConfidenceComputer confidence;

    public AgentGradeAuditResource( final StructuralIndexService index,
                                    final ReferenceManager refs,
                                    final ConfidenceComputer confidence ) {
        this.index = index;
        this.refs = refs;
        this.confidence = confidence;
    }

    /** JSON body for {@code GET /admin/agent-grade-audit}. */
    public String audit( int limit, int offset ) {
        if ( limit < 1 ) limit = 50;
        if ( limit > 200 ) limit = 200;
        if ( offset < 0 ) offset = 0;

        final StructuralFilter filter = new StructuralFilter( null, null, null, null, SCAN_LIMIT, null );

        final List< Map< String, Object > > weakPages = new ArrayList<>();
        for ( final PageDescriptor p : index.listPagesByFilter( filter ) ) {
            final List< String > flags = collectFlags( p );
            if ( flags.isEmpty() ) continue;
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "canonical_id", p.canonicalId() );
            row.put( "title", p.title() );
            row.put( "cluster", p.cluster() );
            row.put( "weaknesses", flags );
            weakPages.add( row );
        }
        // Sort: more flags first, then canonical_id ascending.
        weakPages.sort( Comparator.< Map< String, Object > >comparingInt(
                m -> -( ( List< ? > ) m.get( "weaknesses" ) ).size() )
                .thenComparing( m -> m.get( "canonical_id" ).toString() ) );

        final int total = weakPages.size();
        final int from = Math.min( offset, total );
        final int to = Math.min( from + limit, total );
        final List< Map< String, Object > > page = weakPages.subList( from, to );

        return GSON.toJson( Map.of(
                "total", total,
                "limit", limit,
                "offset", offset,
                "pages", page ) );
    }

    private List< String > collectFlags( final PageDescriptor p ) {
        final List< String > flags = new ArrayList<>();

        // no_cluster — frontmatter cluster absent.
        if ( p.cluster() == null || p.cluster().isBlank() ) flags.add( "no_cluster" );

        // Hub-aware checks (need cluster lookup).
        Optional< ClusterDetails > cluster = Optional.empty();
        boolean isHub = false;
        if ( p.cluster() != null && !p.cluster().isBlank() ) {
            try {
                cluster = index.getCluster( p.cluster() );
                isHub = cluster.map( c -> c.hubPage() != null && c.hubPage().slug().equals( p.slug() ) )
                               .orElse( false );
            } catch ( final Exception e ) {
                // treat as no cluster details available
                LOG.warn( "cluster lookup failed for page {} (cluster {}); omitting hub-aware flags: {}",
                        p.slug(), p.cluster(), e.toString() );
            }
        }

        // no_inbound_cluster_links — non-hubs only.
        if ( !isHub && cluster.isPresent() ) {
            try {
                final Set< String > clusterSlugs = cluster.get().articles().stream()
                        .map( PageDescriptor::slug )
                        .collect( Collectors.toSet() );
                final Set< String > referrers = refs.findReferrers( p.slug() );
                final boolean hasIntra = referrers != null && referrers.stream().anyMatch( clusterSlugs::contains );
                if ( !hasIntra ) flags.add( "no_inbound_cluster_links" );
            } catch ( final Exception e ) {
                // omit flag on lookup failure rather than mis-flagging
                LOG.warn( "referrer lookup failed for page {}; omitting no_inbound_cluster_links flag: {}",
                        p.slug(), e.toString() );
            }
        }

        // generic_hub_summary — hubs whose authored summary matches the generic pattern.
        if ( isHub && p.summary() != null && GENERIC.matcher( p.summary() ).find() ) {
            flags.add( "generic_hub_summary" );
        }

        // no_verified_at + stale_verification — checked separately to avoid double-flagging.
        try {
            final Optional< Verification > v = index.verificationOf( p.canonicalId() );
            if ( v.isEmpty() || v.get().verifiedAt() == null ) {
                flags.add( "no_verified_at" );
            } else {
                final Confidence conf = confidence.compute(
                        v.get().verifiedAt(), v.get().verifiedBy(), Optional.empty(), Instant.now() );
                if ( conf == Confidence.STALE ) {
                    flags.add( "stale_verification" );
                }
            }
        } catch ( final Exception e ) {
            // omit verification-related flags on failure
            LOG.warn( "verification lookup failed for page {}; omitting verification flags: {}",
                    p.canonicalId(), e.toString() );
        }

        return flags;
    }
}
