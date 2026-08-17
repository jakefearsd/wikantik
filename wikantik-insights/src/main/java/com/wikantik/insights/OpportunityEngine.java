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
package com.wikantik.insights;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The native rules of the content-opportunity engine (content-intelligence design §7.3).
 *
 * <p><strong>Pure function.</strong> No I/O and no clock reads happen anywhere in this class --
 * every method takes its inputs (rows, config, {@code today}) as parameters and returns a new
 * list. This is deliberate: it is what makes the rule engine testable without a wiki engine or a
 * database, and it is why {@link OpportunityEngineConfig} and {@code today} are threaded through
 * every entry point rather than read from a clock or a properties file.</p>
 *
 * <p>Wikantik does not recompute what jakemon already detects (§7.3). This class holds only the
 * four rules that need data jakemon cannot see: {@link #evaluateAgentGap} (rule 1, needs MCP
 * traffic), {@link #evaluateEngineDivergence} (rule 2, needs retained multi-engine history),
 * {@link #evaluateVocabularyGap} (rule 3, needs page frontmatter), and
 * {@link #evaluateStaleHighTraffic} (rule 4, needs verification age). The five jakemon-imported
 * types ({@code striking_distance}, {@code ctr_gap}, {@code content_gap}, {@code cannibalization},
 * {@code decay}) arrive pre-built as {@link Opportunity} instances and only pass through
 * {@link #suppressDivergenceAffected}.</p>
 *
 * <p><strong>The §7.3.0 traffic gate.</strong> {@link #evaluate} always runs all four native
 * rules (an always-open gate), matching its original contract. {@link #evaluateGated} is the
 * gate-aware entry point: below {@code config.gateImpressions28d()} site-wide impressions, the
 * three visibility-driven rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
 * {@code STALE_HIGH_TRAFFIC}) do not run at all and are reported in the returned
 * {@link Backlog#suppressed()} instead -- see §7.3.0. {@code AGENT_GAP} is never gated.</p>
 */
public final class OpportunityEngine {

    /** Rule 1 type identifier. */
    public static final String AGENT_GAP = "agent_gap";

    /** Rule 2 type identifier. */
    public static final String ENGINE_DIVERGENCE = "engine_divergence";

    /** Rule 3 type identifier. */
    public static final String VOCABULARY_GAP = "vocabulary_gap";

    /** Rule 4 type identifier. */
    public static final String STALE_HIGH_TRAFFIC = "stale_high_traffic";

    private static final Set<String> WEAK_COVERAGE = Set.of( "weak", "unknown" );

    private static final String CONFIDENCE_AUTHORITATIVE = "authoritative";

    /**
     * Minimal English stopword list for {@link #evaluateVocabularyGap}'s query-vs-frontmatter
     * comparison. Deliberately small and closed-class (articles, conjunctions, prepositions,
     * pronouns, auxiliary verbs) rather than a general-purpose NLP stopword corpus -- the failure
     * mode this guards against is a common word like "the" or "how" trivially "matching" almost
     * any page's title, which would make the rule silently under-fire on real gaps (see
     * {@code OpportunityEngineVocabularyGapTest#stopwordOnlyOverlapDoesNotCountAsTermPresent}).
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "for", "with",
            "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those",
            "it", "its", "as", "at", "by", "from", "how", "what", "when", "where", "who", "why",
            "which", "do", "does", "did", "not", "no", "so", "if", "than", "then", "them",
            "he", "she", "they", "we", "you", "your", "i", "my", "our", "us", "will", "can",
            "could", "would", "should", "about", "into", "over", "under", "again" );

    /**
     * Imported jakemon types that {@link #evaluateEngineDivergence} can explain away. A page
     * ranking well on one engine and badly on another looks, in Google's data alone, exactly like
     * a title/snippet failure -- but rewriting the page will not fix a domain-authority gap. See
     * §7.3 rule 2 and the class-level note on why this suppression exists.
     */
    private static final Set<String> SUPPRESSIBLE_BY_DIVERGENCE = Set.of( "ctr_gap", "striking_distance" );

    private static final String EVIDENCE_ENGINE = "engine";
    private static final String EVIDENCE_WEAK_ENGINE = "weakEngine";

    /** {@link SuppressedRule#reason()} for a rule suppressed by the §7.3.0 traffic gate. */
    private static final String TRAFFIC_GATE_REASON = "traffic_gate";

    /**
     * Rule 1 -- {@code AGENT_GAP}: a retrieval query that consistently comes back empty or weak
     * on agent surfaces. See DemandRow's javadoc for the input shape and grouping contract.
     *
     * <p>Rows are grouped by {@link DemandRow#queryText()}. A query only fires when
     * <strong>every</strong> row in its group is weak (zero results, or
     * {@code coverage ∈ {weak, unknown}}) -- a single non-weak row for that query means the gap
     * is not consistent and the query is left silent, matching §7.3 rule 1's "every occurrence"
     * wording.</p>
     *
     * @param rows   demand rows for the evaluation window; not queried here, supplied by the caller
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds; the global occurrence floor always applies even if
     *               {@link OpportunityEngineConfig#agentGapMinOccurrences()} is configured lower
     * @return opportunities for queries meeting the minimum support, in no particular order
     */
    public List<Opportunity> evaluateAgentGap( final List<DemandRow> rows, final LocalDate today,
                                                final OpportunityEngineConfig config ) {
        final Map<String, List<DemandRow>> byQuery = new LinkedHashMap<>();
        for ( final DemandRow row : rows ) {
            byQuery.computeIfAbsent( row.queryText(), key -> new ArrayList<>() ).add( row );
        }

        final int minOccurrences = config.effectiveAgentGapMinOccurrences();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, List<DemandRow>> entry : byQuery.entrySet() ) {
            final List<DemandRow> group = entry.getValue();

            final boolean everyRowWeak = group.stream().allMatch( OpportunityEngine::isWeak );
            if ( !everyRowWeak ) {
                continue;
            }

            final int occurrences = group.stream().mapToInt( DemandRow::occurrences ).sum();
            final int distinctSessions = group.stream().mapToInt( DemandRow::distinctSessions ).sum();
            if ( occurrences < minOccurrences || distinctSessions < config.agentGapMinDistinctSessions() ) {
                continue;
            }

            final long zeroResultOccurrences = group.stream()
                    .filter( r -> r.resultCount() <= 0 ).mapToInt( DemandRow::occurrences ).sum();
            final long weakCoverageOccurrences = group.stream()
                    .filter( r -> r.resultCount() > 0 && isWeakCoverage( r.coverage() ) )
                    .mapToInt( DemandRow::occurrences ).sum();

            final Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put( "queryText", entry.getKey() );
            evidence.put( "occurrences", occurrences );
            evidence.put( "distinctSessions", distinctSessions );
            evidence.put( "zeroResultOccurrences", zeroResultOccurrences );
            evidence.put( "weakCoverageOccurrences", weakCoverageOccurrences );

            out.add( new Opportunity( AGENT_GAP, entry.getKey(), occurrences * config.weightAgentGap(),
                    evidence, "Curate the Knowledge Graph relations, or write the missing section.",
                    today, false ) );
        }
        return out;
    }

    private static boolean isWeak( final DemandRow row ) {
        return row.resultCount() <= 0 || isWeakCoverage( row.coverage() );
    }

    private static boolean isWeakCoverage( final String coverage ) {
        return coverage != null && WEAK_COVERAGE.contains( coverage.toLowerCase( Locale.ROOT ) );
    }

    /**
     * Rule 2 -- {@code ENGINE_DIVERGENCE}: a page ranking materially better on one engine than
     * another. This rule is diagnostic, not corrective -- see the class comment and
     * {@link #suppressDivergenceAffected} for why it exists.
     *
     * <p><strong>Grain correction (2026-08-17, design §7.3 rule 2).</strong> This rule was
     * originally specified over a shared query set per page, but the ingest payload's
     * {@code by_page} and {@code by_query} rows are disjoint projections -- every query row lands
     * with {@code pagePath = ""}, so no query is attributed to a page (design §12.1 item J1). This
     * method therefore operates on <strong>page rollup rows only</strong> ({@code pagePath}
     * non-blank, {@code queryText} blank), comparing each page's per-engine average position
     * directly. Rows failing either condition -- including per-query rows, which carry a
     * non-blank {@code queryText} -- are ignored. A row with a {@code null} position is also
     * skipped: its impressions cannot be attributed to a position bucket, so it contributes
     * nothing to either side of the comparison.</p>
     *
     * <p>For each page present with a usable position on two or more distinct engines, the engine
     * with the best (lowest) average position is STRONG and the engine with the worst (highest)
     * is WEAK -- one candidate per page, not one per engine pair. Both floors are required:
     * {@code strong.impressions} against {@link OpportunityEngineConfig#effectiveEngineDivergenceMinImpressions()}
     * and {@code weak.impressions} against {@link OpportunityEngineConfig#divergenceMinImpressionsWeak()}
     * (deliberately <em>not</em> composed with the global floor -- see that field's doc), plus the
     * position gap against {@link OpportunityEngineConfig#engineDivergenceMinPositionGap()}. A
     * one-sided floor would compare a well-sampled position against one estimated from a single
     * impression.</p>
     *
     * <p>Priority is the expected-CTR uplift from moving the weak engine's traffic to the strong
     * engine's position, using {@code curve}: {@code weak.impressions x (curve.ctrAt(strong.position)
     * - curve.ctrAt(weak.position)) x config.weightEngineDivergence()}, floored at zero.</p>
     *
     * @param rows   visibility rows for the evaluation window
     * @param curve  the expected click-through-rate-by-position model driving the priority
     *               formula -- see {@link ExpectedCtrCurve#defaultCurve()}
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds
     * @return diagnostic opportunities for pages meeting the minimum support
     */
    public List<Opportunity> evaluateEngineDivergence( final List<VisibilityRow> rows,
                                                        final ExpectedCtrCurve curve, final LocalDate today,
                                                        final OpportunityEngineConfig config ) {
        final Map<String, Map<String, RollupAccumulator>> byPageThenEngine = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank() ) {
                continue; // no page to target -- this is a query-rollup row (D3)
            }
            if ( row.queryText() != null && !row.queryText().isBlank() ) {
                continue; // not a page-rollup row -- see the grain-correction note above
            }
            byPageThenEngine
                    .computeIfAbsent( row.pagePath(), key -> new LinkedHashMap<>() )
                    .computeIfAbsent( row.engine(), key -> new RollupAccumulator() )
                    .add( row );
        }

        final int minStrongImpressions = config.effectiveEngineDivergenceMinImpressions();
        final int minWeakImpressions = config.divergenceMinImpressionsWeak();
        final double minPositionGap = config.engineDivergenceMinPositionGap();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Map<String, RollupAccumulator>> pageEntry : byPageThenEngine.entrySet() ) {
            final String pagePath = pageEntry.getKey();

            String strongEngine = null;
            String weakEngine = null;
            Double strongPosition = null;
            Double weakPosition = null;
            RollupAccumulator strongAcc = null;
            RollupAccumulator weakAcc = null;

            for ( final Map.Entry<String, RollupAccumulator> engineEntry : pageEntry.getValue().entrySet() ) {
                final Double position = engineEntry.getValue().averagePosition();
                if ( position == null ) {
                    continue; // no usable position for this engine -- can't rank it
                }
                if ( strongPosition == null || position < strongPosition ) {
                    strongPosition = position;
                    strongEngine = engineEntry.getKey();
                    strongAcc = engineEntry.getValue();
                }
                if ( weakPosition == null || position > weakPosition ) {
                    weakPosition = position;
                    weakEngine = engineEntry.getKey();
                    weakAcc = engineEntry.getValue();
                }
            }

            if ( strongEngine == null || strongEngine.equals( weakEngine ) ) {
                continue; // fewer than two engines with a usable position -- nothing to compare
            }

            final long strongImpressions = strongAcc.impressions;
            final long weakImpressions = weakAcc.impressions;
            final double positionGap = weakPosition - strongPosition;

            if ( strongImpressions < minStrongImpressions || weakImpressions < minWeakImpressions
                    || positionGap < minPositionGap ) {
                continue;
            }

            final double ctrUplift = curve.ctrAt( strongPosition ) - curve.ctrAt( weakPosition );
            final double estimatedRecoverableClicks = weakImpressions * ctrUplift;
            final double priority = Math.max( 0.0, estimatedRecoverableClicks * config.weightEngineDivergence() );

            final Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put( "strongEngine", strongEngine );
            evidence.put( EVIDENCE_WEAK_ENGINE, weakEngine );
            evidence.put( "strongPosition", strongPosition );
            evidence.put( "weakPosition", weakPosition );
            evidence.put( "positionGap", positionGap );
            evidence.put( "strongImpressions", strongImpressions );
            evidence.put( "weakImpressions", weakImpressions );
            evidence.put( "estimatedRecoverableClicks", estimatedRecoverableClicks );

            out.add( new Opportunity( ENGINE_DIVERGENCE, pagePath, priority, evidence,
                    "Diagnostic, usually \"change nothing on this page.\"", today, false ) );
        }
        return out;
    }

    /** Per-(page, engine) running totals accumulated while scanning page-rollup rows. */
    private static final class RollupAccumulator {
        private long impressions;
        private long clicks;
        private double positionSum;
        private int positionCount;

        void add( final VisibilityRow row ) {
            if ( row.position() == null ) {
                return; // a row with no position can't be attributed to a position bucket
            }
            impressions += row.impressions();
            clicks += row.clicks();
            positionSum += row.position();
            positionCount++;
        }

        Double averagePosition() {
            return positionCount == 0 ? null : positionSum / positionCount;
        }
    }

    /**
     * Rule 3 -- {@code VOCABULARY_GAP}: a query with real click support to a page whose content
     * words the page's own metadata does not mention. Only case (a) from §7.3 rule 3 (Search
     * Console clicks) is implemented here -- the reformulation-pair case (b) needs
     * {@code session_hash} pairing that has no reader in this engine yet.
     *
     * <p>Rows are grouped by {@code (pagePath, queryText)} -- the per-page-per-query grain, like
     * {@link #evaluateEngineDivergence} -- and clicks/impressions summed across engines and
     * snapshots. Page-rollup and query-rollup rows (either dimension blank) carry no
     * page-plus-query pair to check vocabulary against and are ignored.</p>
     *
     * <p><strong>Stopwords never count as "term present."</strong> Before comparing, the query is
     * reduced to its content words (lowercased, punctuation stripped, {@link #STOPWORDS} removed).
     * A query that is stopwords-only ({@code "how to"}) carries no content word to check and stays
     * silent -- there is nothing actionable to add as a tag. Otherwise the rule fires only when
     * <em>none</em> of the content words appear anywhere in the page's title, summary, or tags; a
     * single matching content word means the page already covers enough of the reader's
     * vocabulary and the gap is not real.</p>
     *
     * <p><strong>Unknown pages are skipped, not flagged.</strong> {@link PageFacts#lookup} empty
     * means this engine has no page state to compare against -- treating that as "term absent"
     * would flag every page {@code pageFacts} doesn't know about (e.g. a non-Wikantik site under
     * D10) as a vocabulary gap, which is a false positive, not a finding.</p>
     *
     * @param rows       visibility rows for the evaluation window
     * @param pageFacts  the page-state port (title/summary/tags), backed by the live wiki
     * @param today      the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config     thresholds; the global occurrence floor always applies to the click count
     *                   even if {@link OpportunityEngineConfig#vocabularyGapMinClicks()} is
     *                   configured lower
     * @return opportunities for page/query pairs meeting the minimum support
     */
    public List<Opportunity> evaluateVocabularyGap( final List<VisibilityRow> rows, final PageFacts pageFacts,
                                                     final LocalDate today, final OpportunityEngineConfig config ) {
        final Map<String, Map<String, ClickAccumulator>> byPageThenQuery = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank()
                    || row.queryText() == null || row.queryText().isBlank() ) {
                continue;
            }
            byPageThenQuery
                    .computeIfAbsent( row.pagePath(), key -> new LinkedHashMap<>() )
                    .computeIfAbsent( row.queryText(), key -> new ClickAccumulator() )
                    .add( row );
        }

        final int minClicks = config.effectiveVocabularyGapMinClicks();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Map<String, ClickAccumulator>> pageEntry : byPageThenQuery.entrySet() ) {
            final String pagePath = pageEntry.getKey();

            final Optional<PageFacts.PageFact> fact = pageFacts.lookup( pagePath );
            if ( fact.isEmpty() ) {
                continue; // unknown page: skipped, never treated as "term absent"
            }
            final Set<String> pageVocabulary = pageVocabularyOf( fact.get() );

            for ( final Map.Entry<String, ClickAccumulator> queryEntry : pageEntry.getValue().entrySet() ) {
                final String queryText = queryEntry.getKey();
                final ClickAccumulator acc = queryEntry.getValue();
                if ( acc.clicks < minClicks ) {
                    continue;
                }

                final List<String> contentWords = contentWordsOf( queryText );
                if ( contentWords.isEmpty() ) {
                    continue; // stopwords-only query: nothing meaningful to check or to add
                }
                if ( contentWords.stream().anyMatch( pageVocabulary::contains ) ) {
                    continue; // at least one content word is already covered -- not a gap
                }

                final Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put( "queryText", queryText );
                evidence.put( "clicks", acc.clicks );
                evidence.put( "impressions", acc.impressions );
                evidence.put( "missingContentWords", contentWords );

                out.add( new Opportunity( VOCABULARY_GAP, pagePath,
                        acc.impressions * config.weightVocabularyGap(), evidence,
                        "Add the term as a tag or alias; tighten the summary to include the "
                        + "reader's vocabulary.", today, false ) );
            }
        }
        return out;
    }

    /** The page's own vocabulary: every word (stopwords included) across title, summary, tags. */
    private static Set<String> pageVocabularyOf( final PageFacts.PageFact fact ) {
        final Set<String> vocabulary = new LinkedHashSet<>();
        vocabulary.addAll( wordsOf( fact.title() ) );
        vocabulary.addAll( wordsOf( fact.summary() ) );
        if ( fact.tags() != null ) {
            for ( final String tag : fact.tags() ) {
                vocabulary.addAll( wordsOf( tag ) );
            }
        }
        return vocabulary;
    }

    /** Lowercased, punctuation-stripped words, stopwords included. */
    private static Set<String> wordsOf( final String text ) {
        if ( text == null || text.isBlank() ) {
            return Set.of();
        }
        final Set<String> words = new LinkedHashSet<>();
        for ( final String token : text.toLowerCase( Locale.ROOT ).split( "[^a-z0-9]+" ) ) {
            if ( !token.isEmpty() ) {
                words.add( token );
            }
        }
        return words;
    }

    /** Lowercased, punctuation-stripped, {@link #STOPWORDS}-filtered words. */
    private static List<String> contentWordsOf( final String text ) {
        final List<String> contentWords = new ArrayList<>();
        for ( final String word : wordsOf( text ) ) {
            if ( !STOPWORDS.contains( word ) ) {
                contentWords.add( word );
            }
        }
        return contentWords;
    }

    /** Per-(page, query) running click/impression totals accumulated while scanning rows. */
    private static final class ClickAccumulator {
        private int clicks;
        private int impressions;

        void add( final VisibilityRow row ) {
            clicks += row.clicks();
            impressions += row.impressions();
        }
    }

    /**
     * Rule 4 -- {@code STALE_HIGH_TRAFFIC}: a page with real traffic whose verification has aged
     * out (or never happened, or was never marked authoritative).
     *
     * <p>Impressions are read from the <strong>page-level rollup row</strong> only (real
     * {@code pagePath}, blank {@code queryText}) -- per-query rows are a lower bound once an
     * engine's low-volume-query privacy floor is applied, so summing them would undercount a
     * page's true traffic (see the design doc's "Known limitations" note in §3 and
     * {@code SnapshotPayloadParser}). A page can appear more than once across engines/snapshots;
     * rollup impressions are summed across all of them.</p>
     *
     * <p>A page unknown to {@code pageFacts} is skipped, not flagged -- there is no verification
     * state to judge staleness against. Otherwise the three trigger conditions are independent
     * ORs, matching §7.3 rule 4's literal wording: never verified, verified more than
     * {@link OpportunityEngineConfig#staleDays()} days ago, or confidence is anything other than
     * {@code authoritative} (which also catches an explicit non-authoritative override even on an
     * otherwise-fresh {@code verified_at}).</p>
     *
     * @param rows       visibility rows for the evaluation window
     * @param pageFacts  the page-state port ({@code verified_at}, confidence)
     * @param today      the date this evaluation runs, used both as
     *                   {@link Opportunity#firstSeen()} and as the staleness clock
     * @param config     thresholds; the global impression floor always applies even if
     *                   {@link OpportunityEngineConfig#staleHighTrafficMinImpressions()} is
     *                   configured lower
     * @return opportunities for pages meeting the minimum support
     */
    public List<Opportunity> evaluateStaleHighTraffic( final List<VisibilityRow> rows, final PageFacts pageFacts,
                                                        final LocalDate today, final OpportunityEngineConfig config ) {
        final Map<String, Long> impressionsByPage = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank() ) {
                continue;
            }
            if ( row.queryText() != null && !row.queryText().isBlank() ) {
                continue; // not the page-level rollup row -- per-query rows undercount (D3)
            }
            impressionsByPage.merge( row.pagePath(), ( long ) row.impressions(), Long::sum );
        }

        final int minImpressions = config.effectiveStaleHighTrafficMinImpressions();
        final Instant todayInstant = today.atStartOfDay( ZoneOffset.UTC ).toInstant();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Long> entry : impressionsByPage.entrySet() ) {
            final String pagePath = entry.getKey();
            final long impressions = entry.getValue();
            if ( impressions < minImpressions ) {
                continue;
            }

            final Optional<PageFacts.PageFact> fact = pageFacts.lookup( pagePath );
            if ( fact.isEmpty() ) {
                continue; // unknown page: no verification state to judge staleness against
            }
            final PageFacts.PageFact pf = fact.get();

            final Instant verifiedAt = pf.verifiedAt();
            final boolean neverVerified = verifiedAt == null;
            final Long daysSinceVerified = neverVerified ? null : ChronoUnit.DAYS.between( verifiedAt, todayInstant );
            final boolean agedOut = daysSinceVerified != null && daysSinceVerified > config.staleDays();
            final boolean notAuthoritative = !CONFIDENCE_AUTHORITATIVE.equalsIgnoreCase( pf.confidence() );

            if ( !( neverVerified || agedOut || notAuthoritative ) ) {
                continue;
            }

            final Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put( "impressions", impressions );
            evidence.put( "verifiedAt", verifiedAt == null ? null : verifiedAt.toString() );
            evidence.put( "daysSinceVerified", daysSinceVerified );
            evidence.put( "confidence", pf.confidence() );

            out.add( new Opportunity( STALE_HIGH_TRAFFIC, pagePath,
                    impressions * config.weightStaleHighTraffic(), evidence,
                    "Re-verify or refresh the page.", today, false ) );
        }
        return out;
    }

    /**
     * Cross-rule constraint: divergence suppression (§7.3). Where {@link #evaluateEngineDivergence}
     * fires for a {@code (page, weakEngine)} pair, the imported {@code ctr_gap} and
     * {@code striking_distance} opportunities for that same pair are dropped -- the weakness is
     * already explained by domain authority, not by anything on the page, so surfacing both would
     * train an agent to rewrite a page that doesn't need it.
     *
     * <p>An imported opportunity can only be matched and suppressed if it carries an
     * {@code "engine"} evidence entry identifying which engine's data triggered it; one without
     * that entry always passes through unsuppressed.</p>
     *
     * @param divergenceOpportunities  the result of {@link #evaluateEngineDivergence}
     * @param importedOpportunities    opportunities imported unchanged from jakemon
     * @return {@code divergenceOpportunities} followed by every {@code importedOpportunities}
     *         entry that was not suppressed
     */
    public List<Opportunity> suppressDivergenceAffected( final List<Opportunity> divergenceOpportunities,
                                                          final List<Opportunity> importedOpportunities ) {
        final Set<String> suppressedPairs = new HashSet<>();
        for ( final Opportunity divergence : divergenceOpportunities ) {
            final Object weakEngine = divergence.evidence().get( EVIDENCE_WEAK_ENGINE );
            if ( weakEngine != null ) {
                suppressedPairs.add( pairKey( divergence.target(), weakEngine.toString() ) );
            }
        }

        final List<Opportunity> out = new ArrayList<>( divergenceOpportunities );
        for ( final Opportunity imported : importedOpportunities ) {
            if ( SUPPRESSIBLE_BY_DIVERGENCE.contains( imported.type() ) ) {
                final Object engine = imported.evidence().get( EVIDENCE_ENGINE );
                if ( engine != null && suppressedPairs.contains( pairKey( imported.target(), engine.toString() ) ) ) {
                    continue; // suppressed: divergence already explains this page/engine pair
                }
            }
            out.add( imported );
        }
        return out;
    }

    private static String pairKey( final String target, final String engine ) {
        return target + ' ' + engine;
    }

    /**
     * Cross-rule constraint: divergence suppression, asymmetric case (design doc section 7.3).
     * Unlike {@link #suppressDivergenceAffected}, this is keyed on <strong>page only</strong>, not
     * {@code (page, engine)} -- {@link #evaluateVocabularyGap} aggregates clicks/impressions
     * across engines per query, so no single engine attribution survives to match against.
     *
     * <p><strong>Why {@code VOCABULARY_GAP} is suppressed here but {@code STALE_HIGH_TRAFFIC} is
     * not</strong> (this asymmetry is deliberate, not an oversight -- see
     * {@code OpportunityEngineCrossRuleTest#suppressionIsAsymmetricAcrossTheTwoNativeRules}):</p>
     * <ul>
     *   <li>{@code VOCABULARY_GAP} is a CTR-shaped fix -- its theory of change is "the reader's
     *       words aren't on the page, so they bounce off the snippet." On a page ENGINE_DIVERGENCE
     *       has already diagnosed as an authority problem (ranking ~60th, effectively unseen),
     *       nobody reaches the snippet to bounce off in the first place; adding a tag cannot move
     *       a page that isn't being shown. It belongs in the same suppressible family as the
     *       imported {@code ctr_gap} and {@code striking_distance} types.</li>
     *   <li>{@code STALE_HIGH_TRAFFIC} is not about rank at all -- its trigger is real traffic
     *       plus verification age, and a divergence diagnosis says nothing about whether the
     *       content is still accurate. A stale page stays stale (and worth re-verifying)
     *       regardless of which engine is delivering its clicks.</li>
     * </ul>
     *
     * @param divergenceOpportunities    the result of {@link #evaluateEngineDivergence}
     * @param vocabularyGapOpportunities the result of {@link #evaluateVocabularyGap}
     * @return {@code vocabularyGapOpportunities} with any entry targeting a page that
     *         {@code divergenceOpportunities} also targets removed
     */
    public List<Opportunity> suppressVocabularyGapForDivergentPages(
            final List<Opportunity> divergenceOpportunities,
            final List<Opportunity> vocabularyGapOpportunities ) {
        final Set<String> divergentPages = new HashSet<>();
        for ( final Opportunity divergence : divergenceOpportunities ) {
            divergentPages.add( divergence.target() );
        }

        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity candidate : vocabularyGapOpportunities ) {
            if ( !divergentPages.contains( candidate.target() ) ) {
                out.add( candidate );
            }
        }
        return out;
    }

    /**
     * Cross-rule constraint: 60-day per-page cooldown (§7.3). No target may generate an automatic
     * optimization more than once per {@code cooldownDays} -- two changes inside one measurement
     * window make the effect unattributable.
     *
     * @param opportunities        candidates to filter
     * @param lastChangeByTarget   the most recent {@code content_change_log.applied_at} date
     *                             recorded for each target, keyed by {@link Opportunity#target()}
     * @param today                the date this evaluation runs
     * @param cooldownDays         the cooldown window in days
     * @return {@code opportunities} with any target still inside its cooldown removed
     */
    public List<Opportunity> applyCooldown( final List<Opportunity> opportunities,
                                            final Map<String, LocalDate> lastChangeByTarget,
                                            final LocalDate today, final int cooldownDays ) {
        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity opportunity : opportunities ) {
            final LocalDate lastChange = lastChangeByTarget.get( opportunity.target() );
            if ( lastChange == null || ChronoUnit.DAYS.between( lastChange, today ) >= cooldownDays ) {
                out.add( opportunity );
            }
        }
        return out;
    }

    /**
     * Cross-rule constraint: snooze filtering (§7.3). A {@code (type, target)} pair under an
     * unexpired snooze is removed before scoring.
     *
     * @param opportunities candidates to filter
     * @param snoozes       snooze rows to check against
     * @param today         the date this evaluation runs; a snooze is active while
     *                      {@code today <= snoozedUntil}
     * @return {@code opportunities} with any snoozed {@code (type, target)} removed
     */
    public List<Opportunity> filterSnoozed( final List<Opportunity> opportunities,
                                            final List<OpportunitySnooze> snoozes, final LocalDate today ) {
        final Set<String> active = new HashSet<>();
        for ( final OpportunitySnooze snooze : snoozes ) {
            if ( !today.isAfter( snooze.snoozedUntil() ) ) {
                active.add( pairKey( snooze.target(), snooze.opportunityType() ) );
            }
        }

        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity opportunity : opportunities ) {
            if ( !active.contains( pairKey( opportunity.target(), opportunity.type() ) ) ) {
                out.add( opportunity );
            }
        }
        return out;
    }

    /**
     * Runs the full Phase 2 pipeline with an always-open traffic gate: all four native rules,
     * divergence suppression of the imported backlog and (separately -- see
     * {@link #suppressVocabularyGapForDivergentPages}) of {@code VOCABULARY_GAP}, cooldown,
     * snooze filtering, then a descending-priority sort of the merged result. Each step is also
     * exposed individually above for isolated testing.
     *
     * <p>Delegates to {@link #evaluateGated} with {@code siteImpressions28d} set to
     * {@link Integer#MAX_VALUE} (so the §7.3.0 gate never closes), an empty
     * {@code calibratedTypes} (so every {@link Opportunity#calibrated()} stays {@code false}, this
     * method's original contract), and {@link ExpectedCtrCurve#defaultCurve()} -- preserving this
     * method's original signature and behavior for existing callers.</p>
     *
     * @param demandRows            input for {@link #evaluateAgentGap}
     * @param visibilityRows        input for {@link #evaluateEngineDivergence},
     *                              {@link #evaluateVocabularyGap}, and
     *                              {@link #evaluateStaleHighTraffic}
     * @param importedOpportunities opportunities imported unchanged from jakemon
     * @param pageFacts             page-state port for {@link #evaluateVocabularyGap} and
     *                              {@link #evaluateStaleHighTraffic}
     * @param snoozes               active/inactive snooze rows; expiry is checked against {@code today}
     * @param lastChangeByTarget    most recent change date per target, for the cooldown
     * @param today                 the date this evaluation runs
     * @param config                thresholds
     * @return the merged, filtered, priority-sorted backlog
     */
    public List<Opportunity> evaluate( final List<DemandRow> demandRows,
                                       final List<VisibilityRow> visibilityRows,
                                       final List<Opportunity> importedOpportunities,
                                       final PageFacts pageFacts,
                                       final List<OpportunitySnooze> snoozes,
                                       final Map<String, LocalDate> lastChangeByTarget,
                                       final LocalDate today,
                                       final OpportunityEngineConfig config ) {
        return evaluateGated( demandRows, visibilityRows, importedOpportunities, pageFacts, snoozes,
                lastChangeByTarget, Integer.MAX_VALUE, Set.of(), ExpectedCtrCurve.defaultCurve(),
                today, config ).opportunities();
    }

    /**
     * Runs the full Phase 2 pipeline with the §7.3.0 site-level traffic gate applied: below
     * {@code config.gateImpressions28d()} site-wide impressions in 28 days, the three
     * visibility-driven rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
     * {@code STALE_HIGH_TRAFFIC}) do not run at all -- each is reported in
     * {@link Backlog#suppressed()} with reason {@code "traffic_gate"} rather than silently
     * returning nothing. {@code AGENT_GAP} always runs; it is never gated, since its denominator
     * is MCP retrieval traffic, not search impressions. Imported opportunities are never gated
     * either.
     *
     * <p>When the gate is open, behavior matches {@link #evaluate}: divergence suppression of the
     * imported backlog and of {@code VOCABULARY_GAP}, cooldown, snooze filtering, then a
     * descending-priority sort. Finally, every returned {@link Opportunity#calibrated()} is set
     * to {@code calibratedTypes.contains(opportunity.type())} -- see design §7.4.4.</p>
     *
     * @param demandRows            input for {@link #evaluateAgentGap}
     * @param visibilityRows        input for {@link #evaluateEngineDivergence},
     *                              {@link #evaluateVocabularyGap}, and
     *                              {@link #evaluateStaleHighTraffic}
     * @param importedOpportunities opportunities imported unchanged from jakemon; never gated
     * @param pageFacts             page-state port for {@link #evaluateVocabularyGap} and
     *                              {@link #evaluateStaleHighTraffic}
     * @param snoozes               active/inactive snooze rows; expiry is checked against {@code today}
     * @param lastChangeByTarget    most recent change date per target, for the cooldown
     * @param siteImpressions28d    total site-wide search-visibility impressions in the last 28
     *                              days, compared against {@code config.gateImpressions28d()}
     * @param calibratedTypes       opportunity types whose priority weight has been calibrated
     *                              against realized effect (design §7.4.4); drives
     *                              {@link Opportunity#calibrated()} on every returned opportunity
     * @param curve                 the expected click-through-rate-by-position model for
     *                              {@link #evaluateEngineDivergence}
     * @param today                 the date this evaluation runs
     * @param config                thresholds
     * @return the merged, filtered, priority-sorted backlog, plus any rules the traffic gate
     *         suppressed
     */
    public Backlog evaluateGated( final List<DemandRow> demandRows,
                                  final List<VisibilityRow> visibilityRows,
                                  final List<Opportunity> importedOpportunities,
                                  final PageFacts pageFacts,
                                  final List<OpportunitySnooze> snoozes,
                                  final Map<String, LocalDate> lastChangeByTarget,
                                  final int siteImpressions28d,
                                  final Set<String> calibratedTypes,
                                  final ExpectedCtrCurve curve,
                                  final LocalDate today,
                                  final OpportunityEngineConfig config ) {
        final List<Opportunity> agentGap = evaluateAgentGap( demandRows, today, config );

        final List<Opportunity> divergence;
        final List<Opportunity> vocabularyGap;
        final List<Opportunity> staleHighTraffic;
        final List<SuppressedRule> suppressed;

        if ( siteImpressions28d >= config.gateImpressions28d() ) {
            divergence = evaluateEngineDivergence( visibilityRows, curve, today, config );
            vocabularyGap = evaluateVocabularyGap( visibilityRows, pageFacts, today, config );
            staleHighTraffic = evaluateStaleHighTraffic( visibilityRows, pageFacts, today, config );
            suppressed = List.of();
        } else {
            divergence = List.of();
            vocabularyGap = List.of();
            staleHighTraffic = List.of();
            suppressed = List.of(
                    new SuppressedRule( ENGINE_DIVERGENCE, TRAFFIC_GATE_REASON,
                            siteImpressions28d, config.gateImpressions28d() ),
                    new SuppressedRule( VOCABULARY_GAP, TRAFFIC_GATE_REASON,
                            siteImpressions28d, config.gateImpressions28d() ),
                    new SuppressedRule( STALE_HIGH_TRAFFIC, TRAFFIC_GATE_REASON,
                            siteImpressions28d, config.gateImpressions28d() ) );
        }

        // Note the second-order effect of a closed gate: with no divergence opportunities, the
        // divergence SUPPRESSION below has nothing to match, so imported ctr_gap /
        // striking_distance opportunities flow through unchecked. That is deliberate. Below the
        // gate, divergence cannot fire reliably, so it cannot suppress reliably either, and a
        // suppression decision made from a single-impression position estimate would be worth
        // less than no decision. The imported types keep their own upstream floor
        // (jakemon's MIN_IMPRESSIONS = 50, stricter than anything here), which on this corpus
        // admits only the handful of pages above the 99th percentile of traffic -- precisely the
        // pages where acting on an imported suggestion is most defensible.
        final List<Opportunity> mergedImported = suppressDivergenceAffected( divergence, importedOpportunities );
        final List<Opportunity> survivingVocabularyGap =
                suppressVocabularyGapForDivergentPages( divergence, vocabularyGap );

        final List<Opportunity> all = new ArrayList<>( agentGap );
        all.addAll( mergedImported );
        all.addAll( survivingVocabularyGap );
        all.addAll( staleHighTraffic );

        final List<Opportunity> afterCooldown = applyCooldown( all, lastChangeByTarget, today, config.cooldownDays() );
        final List<Opportunity> afterSnooze = filterSnoozed( afterCooldown, snoozes, today );

        final List<Opportunity> calibrated = new ArrayList<>();
        for ( final Opportunity opportunity : afterSnooze ) {
            calibrated.add( new Opportunity( opportunity.type(), opportunity.target(), opportunity.priority(),
                    opportunity.evidence(), opportunity.suggestedAction(), opportunity.firstSeen(),
                    calibratedTypes.contains( opportunity.type() ) ) );
        }
        calibrated.sort( Comparator.comparingDouble( Opportunity::priority ).reversed() );

        return new Backlog( calibrated, suppressed );
    }
}
