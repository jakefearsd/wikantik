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

import com.wikantik.parser.markdown.ReaderText;
import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.agent.HeadingOutline;
import com.wikantik.api.agent.KeyFact;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.agent.RecentChange;
import com.wikantik.api.citation.CitationRef;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.cache.CachingManager;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import com.wikantik.auth.acl.AclDirectiveText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
 *
 * <p>The cached projection is the same for every caller, so it holds every page the fields derived from OTHER pages
 * could name; each call then cuts those fields down to what that caller may view (see {@code forCaller}). Building
 * the cache from guest-viewable pages only was rejected: a viewer of a private cluster would then get no hints at all
 * for that cluster's own pages.</p>
 */
public class DefaultForAgentProjectionService implements ForAgentProjectionService {

    private static final Logger LOG = LogManager.getLogger( DefaultForAgentProjectionService.class );

    private static final int RECENT_CHANGES_LIMIT = 5;

    private final StructuralIndexService index;
    private final PageManager pageManager;
    private final CachingManager cache;
    private final ForAgentMetrics metrics;
    private final AgentHintsDeriver     hintsDeriver;
    private final HubSummarySynthesizer hubSynth;
    private final CitationRepository    citationRepository;

    private final HeadingsOutlineExtractor headings;
    private final KeyFactsExtractor        keyFacts;
    private final McpToolHintsResolver     toolHints;
    private final RecentChangesAdapter     recents;

    public DefaultForAgentProjectionService(
            final StructuralIndexService index,
            final PageManager pageManager,
            final CachingManager cache,
            final ForAgentMetrics metrics,
            final AgentHintsDeriver hintsDeriver,
            final HubSummarySynthesizer hubSynth,
            final CitationRepository citationRepository ) {
        this( index, pageManager, cache, metrics, hintsDeriver, hubSynth, citationRepository,
              new Extractors( new HeadingsOutlineExtractor(), new KeyFactsExtractor(),
                              new McpToolHintsResolver(), new RecentChangesAdapter( pageManager ) ) );
    }

    /**
     * Parameter object for the four inner extractor/adapter helpers, so the
     * test-only seam constructor below stays under the project's
     * {@code ExcessiveParameterList} complexity-gate threshold (8).
     */
    record Extractors(
            HeadingsOutlineExtractor headings,
            KeyFactsExtractor keyFacts,
            McpToolHintsResolver toolHints,
            RecentChangesAdapter recents ) {
    }

    /**
     * Test-only seam: lets tests substitute the four inner extractor/adapter helpers
     * directly to exercise per-field graceful-degradation branches without having to
     * force a real parse failure through {@link PageManager}. The public constructor
     * always builds the real defaults, so production wiring is unaffected.
     */
    DefaultForAgentProjectionService(
            final StructuralIndexService index,
            final PageManager pageManager,
            final CachingManager cache,
            final ForAgentMetrics metrics,
            final AgentHintsDeriver hintsDeriver,
            final HubSummarySynthesizer hubSynth,
            final CitationRepository citationRepository,
            final Extractors extractors ) {
        this.index = index;
        this.pageManager = pageManager;
        this.cache = cache;
        this.metrics = metrics;
        this.hintsDeriver = hintsDeriver;
        this.hubSynth = hubSynth;
        this.citationRepository = citationRepository;
        this.headings = extractors.headings();
        this.keyFacts = extractors.keyFacts();
        this.toolHints = extractors.toolHints();
        this.recents = extractors.recents();
    }

    @Override
    public Optional< ForAgentProjection > project( final String canonicalId, final Predicate< String > viewable ) {
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

        final Shared cached = readFromCache( key );
        final Shared shared = cached != null ? cached : build( d );
        if ( cached == null ) {
            writeToCache( key, shared );
            recordMetric( shared.base() );
        }
        return Optional.of( forCaller( shared, viewable, cached == null ) );
    }

    /**
     * What is built once per page version and cached for every caller. Its {@code agent_hints} rank every preferred
     * page with no cap, its summary is the authored one and its {@code stale_citations} name every target, whoever may
     * view them: {@link #forCaller} cuts all three down per call, so the cache never decides what a caller sees.
     */
    private record Shared( ForAgentProjection base, boolean isHub ) {}

    /**
     * The shared projection as one caller may see it: preferred pages they may view (best first, capped), a hub
     * summary synthesized from those titles only, and stale citations whose target is gone or viewable.
     */
    private ForAgentProjection forCaller( final Shared shared, final Predicate< String > viewable, final boolean fresh ) {
        final ForAgentProjection b = shared.base();
        final AgentHintsBlock hints = AgentHintsDeriver.visibleTo( b.agentHints(), id -> idViewable( id, viewable ) );
        final SummaryOverlay overlay = overlayHubSummary( b, hints, shared.isHub() );
        if ( fresh && overlay.synthesized() && metrics != null ) {
            metrics.incrementHubSummarySynthesis();
        }
        final List< CitationRef > stale = b.staleCitations().stream()
                .filter( c -> targetViewable( c.targetCanonicalId(), viewable ) )
                .toList();
        return new ForAgentProjection( b.canonicalId(), b.slug(), b.title(), b.type(), b.cluster(), b.audience(),
                b.confidence(), b.verifiedAt(), b.verifiedBy(), b.updated(), overlay.summary(), b.keyFacts(),
                b.headingsOutline(), b.recentChanges(), b.mcpToolHints(), b.runbook(), hints, overlay.synthesized(),
                b.fullBodyUrl(), b.rawMarkdownUrl(), b.degraded(), b.missingFields(), stale );
    }

    /** Whether the caller may view the page with this canonical id; an unknown id or a failed lookup is hidden. */
    private boolean idViewable( final String canonicalId, final Predicate< String > viewable ) {
        try {
            return index.resolveSlugFromCanonicalId( canonicalId ).map( viewable::test ).orElse( false );
        } catch ( final RuntimeException e ) {
            LOG.warn( "for-agent: could not resolve {} for a view check — leaving it out: {}", canonicalId, e.getMessage() );
            return false;
        }
    }

    /**
     * A citation names its target page and section: it is shown when the caller may view the target, or when the
     * target no longer exists (the citation is then stale because of that, and names nothing that can be read).
     */
    private boolean targetViewable( final String targetCanonicalId, final Predicate< String > viewable ) {
        if ( targetCanonicalId == null ) {
            return true;
        }
        try {
            return index.resolveSlugFromCanonicalId( targetCanonicalId ).map( viewable::test ).orElse( true );
        } catch ( final RuntimeException e ) {
            LOG.warn( "for-agent: could not resolve citation target {} — leaving it out: {}", targetCanonicalId,
                    e.getMessage() );
            return false;
        }
    }

    private Shared build( final PageDescriptor d ) {
        final List< String > missing = new ArrayList<>();

        // Verification — falls back to unverified() (provisional + both audiences) when absent.
        final Verification verification = index.verificationOf( d.canonicalId() ).orElseGet( Verification::unverified );

        final BodyAndFrontmatter bf = loadBodyAndFrontmatter( d, missing );
        final Map< String, Object > frontmatter = bf.frontmatter();
        final String body = bf.body();

        final List< HeadingOutline > outline = extractHeadings( body, d, missing );
        final List< KeyFact > facts = extractKeyFacts( frontmatter, body, d, missing );
        final List< RecentChange > changes = loadRecentChanges( d, missing );
        final List< McpToolHint > hints = resolveToolHints( frontmatter, d, missing );
        final Object runbook = buildRunbook( d, frontmatter, missing );

        // Derived agent_hints, ranked and uncapped — null on whole-block degradation, empty block on no-signal.
        final AgentHintsBlock agentHints = deriveAgentHints( d, missing );

        // Whether the hub-summary overlay may fire (per caller, in forCaller): only for a cluster hub with hints.
        final boolean isHub = agentHints != null && hubSynth != null && isClusterHub( d );

        final List< CitationRef > staleCitations = loadStaleCitations( d, missing );

        return new Shared( new ForAgentProjection(
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
                changes,
                hints,
                runbook,
                agentHints,
                false,
                "/api/pages/" + d.slug(),
                "/wiki/" + d.slug() + "?format=md",
                !missing.isEmpty(),
                missing,
                staleCitations ), isHub );
    }

    /** Page body + parsed frontmatter, as loaded by {@link #loadBodyAndFrontmatter}. */
    private record BodyAndFrontmatter( Map< String, Object > frontmatter, String body ) {}

    private BodyAndFrontmatter loadBodyAndFrontmatter( final PageDescriptor d, final List< String > missing ) {
        Map< String, Object > frontmatter = Map.of();
        String body = "";
        try {
            final String raw = pageManager.getPureText( d.slug(), WikiProvider.LATEST_VERSION );
            if ( raw != null && !raw.isEmpty() ) {
                // key_facts and the heading outline are derived from the body's first paragraphs, so a directive
                // on its first lines would surface in them: access rules (people, by login) never enter the body.
                // Strip the FULL text and take the body of that: enforcement is judged on the full text.
                final ParsedPage parsed = AclDirectiveText.parseStripped( raw );
                frontmatter = parsed.metadata();
                // Derived text: block markers are source, not content (spec 1.11a). The runbook block is built from
                // frontmatter only, so this single point covers key_facts and the heading outline.
                body = ReaderText.derivedBody( parsed.body() );
            }
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: getPureText({}) failed — body & key_facts & headings will degrade: {}",
                    d.slug(), e.getMessage() );
            missing.add( "body" );
        }
        return new BodyAndFrontmatter( frontmatter, body );
    }

    private List< HeadingOutline > extractHeadings( final String body, final PageDescriptor d,
                                                      final List< String > missing ) {
        try {
            return headings.extract( body );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: headings extractor threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "headings_outline" );
            return List.of();
        }
    }

    private List< KeyFact > extractKeyFacts( final Map< String, Object > frontmatter, final String body,
                                              final PageDescriptor d, final List< String > missing ) {
        try {
            return keyFacts.extract( frontmatter, body );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: key_facts extractor threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "key_facts" );
            return List.of();
        }
    }

    private List< RecentChange > loadRecentChanges( final PageDescriptor d, final List< String > missing ) {
        try {
            return recents.recentChanges( d.slug(), RECENT_CHANGES_LIMIT );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: recent_changes threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "recent_changes" );
            return List.of();
        }
    }

    private List< McpToolHint > resolveToolHints( final Map< String, Object > frontmatter, final PageDescriptor d,
                                                   final List< String > missing ) {
        try {
            return toolHints.resolve( frontmatter, d.tags(), d.cluster() );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: mcp_tool_hints threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "mcp_tool_hints" );
            return List.of();
        }
    }

    /**
     * Runbook block — only when this page declares type: runbook.
     * Phase 3: re-runs the same validator the save-time filter uses, so
     * corpus drift (or saves made while enforcement was disabled) doesn't
     * pollute the projection. Invalid blocks degrade gracefully.
     */
    private Object buildRunbook( final PageDescriptor d, final Map< String, Object > frontmatter,
                                  final List< String > missing ) {
        if ( PageType.RUNBOOK != d.type() ) {
            return null;
        }
        try {
            final FrontmatterRunbookValidator.Result rbResult =
                    FrontmatterRunbookValidator.validate(
                            frontmatter,
                            id   -> index.getByCanonicalId( id ).isPresent(),
                            name -> {
                                try {
                                    return pageManager.pageExists( name );
                                } catch ( final Exception e ) {
                                    LOG.warn( "for-agent: pageExists({}) threw — treating as unresolved: {}",
                                            name, e.getMessage() );
                                    return false;
                                }
                            } );
            if ( rbResult.valid().isPresent() ) {
                return rbResult.valid().get();
            }
            if ( rbResult.hasIssues() ) {
                LOG.warn( "for-agent: runbook block invalid for {} — leaving null: {}",
                        d.slug(), rbResult.issues() );
                missing.add( "runbook" );
            }
            return null;
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: runbook validation threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "runbook" );
            return null;
        }
    }

    private AgentHintsBlock deriveAgentHints( final PageDescriptor d, final List< String > missing ) {
        if ( hintsDeriver == null ) {
            return AgentHintsBlock.empty();
        }
        try {
            return hintsDeriver.deriveRanked( d.canonicalId() );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: agent_hints derivation threw for {}: {}", d.slug(), e.getMessage() );
            missing.add( "agent_hints" );
            if ( metrics != null ) metrics.incrementHintsDerivationFailures();
            return null;
        }
    }

    /** Effective (possibly hub-synthesized) summary, as computed by {@link #overlayHubSummary}. */
    private record SummaryOverlay( String summary, boolean synthesized ) {}

    /** The authored summary, or — for a hub with a generic one — a summary naming the caller's preferred pages. */
    private SummaryOverlay overlayHubSummary( final ForAgentProjection base, final AgentHintsBlock agentHints,
                                              final boolean isHub ) {
        if ( agentHints == null || hubSynth == null ) {
            return new SummaryOverlay( base.summary(), false );
        }
        try {
            return hubSynth.maybeOverlay( base.summary(), agentHints, isHub )
                           .map( synthesized -> new SummaryOverlay( synthesized, true ) )
                           .orElseGet( () -> new SummaryOverlay( base.summary(), false ) );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: hub summary overlay threw for {}: {}", base.slug(), e.getMessage() );
            return new SummaryOverlay( base.summary(), false );
        }
    }

    /**
     * Stale citations — query CitationRepository filtered to non-CURRENT rows.
     * When the repository is null (citations disabled), produce an empty list.
     */
    private List< CitationRef > loadStaleCitations( final PageDescriptor d, final List< String > missing ) {
        if ( citationRepository == null ) {
            return List.of();
        }
        try {
            return citationRepository.findBySource( d.canonicalId() ).stream()
                    .filter( r -> r.status() != CitationStatus.CURRENT )
                    .map( DefaultForAgentProjectionService::toCitationRef )
                    .collect( Collectors.toList() );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: stale_citations query failed for {}: {}", d.slug(), e.getMessage() );
            missing.add( "stale_citations" );
            return List.of();
        }
    }

    private static CitationRef toCitationRef( final CitationRow r ) {
        return new CitationRef(
                r.sourceCanonicalId(),
                r.targetCanonicalId(),
                r.targetHeadingPath(),
                r.spanText(),
                r.claimText(),
                r.status(),
                r.pinnedTargetVersion() );
    }

    private boolean isClusterHub( final PageDescriptor d ) {
        if ( d.cluster() == null ) return false;
        try {
            return index.getCluster( d.cluster() )
                        .map( c -> c.hubPage() != null && c.hubPage().slug().equals( d.slug() ) )
                        .orElse( false );
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: hub lookup failed for {}: {}", d.slug(), e.getMessage() );
            return false;
        }
    }

    /* ----------------------------------------------------------------- caching */

    private Shared readFromCache( final Serializable key ) {
        if ( cache == null || !cache.enabled( CachingManager.CACHE_FOR_AGENT ) ) {
            return null;
        }
        try {
            // Supplier returns null — we want hits-only semantics here. The miss path
            // is handled below with an explicit put().
            final Object hit = cache.get( CachingManager.CACHE_FOR_AGENT, key, () -> null );
            return hit instanceof Shared shared ? shared : null;
        } catch ( final Exception e ) {
            LOG.warn( "for-agent: cache read failed for key {} — bypassing cache: {}", key, e.getMessage() );
            return null;
        }
    }

    private void writeToCache( final Serializable key, final Shared built ) {
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
