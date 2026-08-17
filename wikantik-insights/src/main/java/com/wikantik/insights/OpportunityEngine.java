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
 * two rules that need data jakemon cannot see: {@link #evaluateAgentGap} (rule 1, needs MCP
 * traffic) and {@link #evaluateEngineDivergence} (rule 2, needs retained multi-engine history).
 * The five jakemon-imported types ({@code striking_distance}, {@code ctr_gap},
 * {@code content_gap}, {@code cannibalization}, {@code decay}) arrive pre-built as
 * {@link Opportunity} instances and only pass through {@link #suppressDivergenceAffected}.</p>
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

            out.add( new Opportunity( AGENT_GAP, entry.getKey(), occurrences * 2.0, evidence,
                    "Curate the Knowledge Graph relations, or write the missing section.",
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
     * another across a shared query set. This rule is diagnostic, not corrective -- see the class
     * comment and {@link #suppressDivergenceAffected} for why it exists.
     *
     * <p>Only rows carrying <em>both</em> a non-blank {@link VisibilityRow#pagePath()} and a
     * non-blank {@link VisibilityRow#queryText()} (the per-page-per-query grain) are considered;
     * page-rollup rows ({@code queryText = ""}) and query-rollup rows ({@code pagePath = ""})
     * carry no per-engine query set to compare and are ignored.</p>
     *
     * @param rows   visibility rows for the evaluation window
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds; the global impression floor always applies even if
     *               {@link OpportunityEngineConfig#engineDivergenceMinImpressions()} is
     *               configured lower
     * @return diagnostic opportunities for page/engine pairs meeting the minimum support
     */
    public List<Opportunity> evaluateEngineDivergence( final List<VisibilityRow> rows, final LocalDate today,
                                                        final OpportunityEngineConfig config ) {
        final Map<String, Map<String, EngineAccumulator>> byPageThenEngine = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank()
                    || row.queryText() == null || row.queryText().isBlank() ) {
                continue;
            }
            byPageThenEngine
                    .computeIfAbsent( row.pagePath(), key -> new LinkedHashMap<>() )
                    .computeIfAbsent( row.engine(), key -> new EngineAccumulator() )
                    .add( row );
        }

        final int minImpressions = config.effectiveEngineDivergenceMinImpressions();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Map<String, EngineAccumulator>> pageEntry : byPageThenEngine.entrySet() ) {
            final String pagePath = pageEntry.getKey();
            final List<String> engines = new ArrayList<>( pageEntry.getValue().keySet() );

            for ( int i = 0; i < engines.size(); i++ ) {
                for ( int j = i + 1; j < engines.size(); j++ ) {
                    final EngineAccumulator a = pageEntry.getValue().get( engines.get( i ) );
                    final EngineAccumulator b = pageEntry.getValue().get( engines.get( j ) );

                    final Double posA = a.averagePosition();
                    final Double posB = b.averagePosition();
                    if ( posA == null || posB == null ) {
                        continue; // can't compare rank without a position on both sides
                    }

                    final boolean aStronger = posA < posB;
                    final EngineAccumulator strong = aStronger ? a : b;
                    final EngineAccumulator weak = aStronger ? b : a;
                    final String strongEngine = aStronger ? engines.get( i ) : engines.get( j );
                    final String weakEngine = aStronger ? engines.get( j ) : engines.get( i );

                    final double positionGap = weak.averagePosition() - strong.averagePosition();
                    final int sharedQueries = sharedQueryCount( strong.queries, weak.queries );

                    if ( strong.impressions < minImpressions
                            || sharedQueries < config.engineDivergenceMinSharedQueries()
                            || positionGap < config.engineDivergenceMinPositionGap() ) {
                        continue;
                    }

                    final Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put( "strongEngine", strongEngine );
                    evidence.put( EVIDENCE_WEAK_ENGINE, weakEngine );
                    evidence.put( "strongEngineImpressions", strong.impressions );
                    evidence.put( "weakEngineImpressions", weak.impressions );
                    evidence.put( "strongEngineClicks", strong.clicks );
                    evidence.put( "strongEnginePosition", strong.averagePosition() );
                    evidence.put( "weakEnginePosition", weak.averagePosition() );
                    evidence.put( "positionGap", positionGap );
                    evidence.put( "sharedQueries", sharedQueries );

                    out.add( new Opportunity( ENGINE_DIVERGENCE, pagePath, strong.clicks * 0.5, evidence,
                            "Diagnostic, usually \"change nothing on this page.\"", today, false ) );
                }
            }
        }
        return out;
    }

    private static int sharedQueryCount( final Set<String> a, final Set<String> b ) {
        final Set<String> shared = new HashSet<>( a );
        shared.retainAll( b );
        return shared.size();
    }

    /** Per-(page, engine) running totals accumulated while scanning {@link VisibilityRow}s. */
    private static final class EngineAccumulator {
        private final Set<String> queries = new LinkedHashSet<>();
        private long impressions;
        private long clicks;
        private double positionSum;
        private int positionCount;

        void add( final VisibilityRow row ) {
            queries.add( row.queryText() );
            impressions += row.impressions();
            clicks += row.clicks();
            if ( row.position() != null ) {
                positionSum += row.position();
                positionCount++;
            }
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

                out.add( new Opportunity( VOCABULARY_GAP, pagePath, acc.impressions * 0.05, evidence,
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

            out.add( new Opportunity( STALE_HIGH_TRAFFIC, pagePath, impressions * 0.02, evidence,
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
     * Runs the full Phase 2 pipeline: all four native rules, divergence suppression of the
     * imported backlog and (separately -- see {@link #suppressVocabularyGapForDivergentPages})
     * of {@code VOCABULARY_GAP}, cooldown, snooze filtering, then a descending-priority sort of
     * the merged result. Each step is also exposed individually above for isolated testing.
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
        final List<Opportunity> agentGap = evaluateAgentGap( demandRows, today, config );
        final List<Opportunity> divergence = evaluateEngineDivergence( visibilityRows, today, config );
        final List<Opportunity> vocabularyGap = evaluateVocabularyGap( visibilityRows, pageFacts, today, config );
        final List<Opportunity> staleHighTraffic = evaluateStaleHighTraffic( visibilityRows, pageFacts, today, config );

        final List<Opportunity> mergedImported = suppressDivergenceAffected( divergence, importedOpportunities );
        final List<Opportunity> survivingVocabularyGap =
                suppressVocabularyGapForDivergentPages( divergence, vocabularyGap );

        final List<Opportunity> all = new ArrayList<>( agentGap );
        all.addAll( mergedImported );
        all.addAll( survivingVocabularyGap );
        all.addAll( staleHighTraffic );

        final List<Opportunity> afterCooldown = applyCooldown( all, lastChangeByTarget, today, config.cooldownDays() );
        final List<Opportunity> afterSnooze = filterSnoozed( afterCooldown, snoozes, today );

        final List<Opportunity> sorted = new ArrayList<>( afterSnooze );
        sorted.sort( Comparator.comparingDouble( Opportunity::priority ).reversed() );
        return sorted;
    }
}
