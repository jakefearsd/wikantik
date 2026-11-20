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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rule 3 -- {@code VOCABULARY_GAP}: a query with real click support to a page whose own metadata does not mention its content words. Traffic-gated (§7.3.0).
 *
 * <p>Package-private: callers reach it through {@link OpportunityEngine}, which delegates its
 * public {@code evaluate*} method here and runs it as part of the rule list.</p>
 */
final class VocabularyGapRule implements OpportunityRule {

    /**
     * Minimal English stopword list for {@link #detect}'s query-vs-frontmatter
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

    @Override
    public String type() {
        return OpportunityEngine.VOCABULARY_GAP;
    }

    @Override
    public boolean gated() {
        return true;
    }

    @Override
    public List<Opportunity> evaluate( final EvaluationInput in ) {
        return detect( in.visibilityRows(), in.pageFacts(), in.today(), in.config() );
    }

    /**
     * Rule 3 -- {@code OpportunityEngine.VOCABULARY_GAP}: a query with real click support to a page whose content
     * words the page's own metadata does not mention. Only case (a) from §7.3 rule 3 (Search
     * Console clicks) is implemented here -- the reformulation-pair case (b) needs
     * {@code session_hash} pairing that has no reader in this engine yet.
     *
     * <p>Rows are grouped by {@code (pagePath, queryText)} -- the per-page-per-query grain, like
     * {@link OpportunityEngine#evaluateEngineDivergence} -- and clicks/impressions summed across engines and
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
    static List<Opportunity> detect( final List<VisibilityRow> rows, final PageFacts pageFacts,
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

                out.add( new Opportunity( OpportunityEngine.VOCABULARY_GAP, pagePath,
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
}
