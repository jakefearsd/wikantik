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
package com.wikantik.search;

import com.wikantik.WikiPage;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.spi.Wiki;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;


/**
 * SearchMatcher performs the task of matching a search query to a page's contents. This utility class is isolated to simplify
 * WikiPageProvider implementations and to offer an easy target for upgrades. The upcoming(?) TranslatorReader rewrite will
 * presumably invalidate this, among other things.
 *
 * @since 2.1.5
 */
public class SearchMatcher {
	
    private final QueryItem[] queries;
    private final Engine engine;

    /**
     *  Creates a new SearchMatcher.
     *
     *  @param engine The Engine
     *  @param queries A list of queries
     */
    public SearchMatcher( final Engine engine, final QueryItem[] queries ) {
        this.engine = engine;
        this.queries = queries != null ? queries.clone() : null;
    }


    /**
     * Compares the page content, available through the given stream, to the query items of this matcher. Returns a search result
     * object describing the quality of the match.
     *
     * <p>This method would benefit of regexps (1.4) and streaming. FIXME!
     *
     * @param wikiname The name of the page
     * @param pageText The content of the page
     * @return A SearchResult item, or null, there are no queries
     * @throws IOException If reading page content fails
     */
    public com.wikantik.api.search.SearchResult matchPageContent( final String wikiname, final String pageText ) throws IOException {
        if( queries == null ) {
            return null;
        }

        final int[] scores = scoreContentOccurrences( pageText );
        if( scores == null ) {
            // Found something that was forbidden.
            return null;
        }

        final int totalscore = applyNameBonusAndRequiredFilter( wikiname, scores );
        if( totalscore < 0 ) {
            // A 'required' word had no score after the name bonus was applied.
            return null;
        }

        if( totalscore > 0 ) {
            return new SearchResultImpl( wikiname, totalscore );
        }

        return null;
    }

    /**
     * Scans the page content line by line, counting occurrences of each query word.
     *
     * @param pageText The content of the page
     * @return A per-query score array, or {@code null} if a FORBIDDEN word was found
     * @throws IOException If reading page content fails
     */
    private int[] scoreContentOccurrences( final String pageText ) throws IOException {
        final int[] scores = new int[ queries.length ];
        try ( BufferedReader in = new BufferedReader( new StringReader( pageText ) ) ) {
            String line;
            while( ( line = in.readLine() ) != null ) {
                line = line.toLowerCase( Locale.ROOT );

                for( int j = 0; j < queries.length; j++ ) {
                    int index = -1;

                    while( ( index = line.indexOf( queries[j].word, index + 1 ) ) != -1 ) {
                        if( queries[j].type != QueryItem.FORBIDDEN ) {
                            scores[j]++; // Mark, found this word n times
                        } else {
                            // Found something that was forbidden.
                            return null;
                        }
                    }
                }
            }
        }

        return scores;
    }

    /**
     * Applies the page-name match bonus to each query's score, then checks that every
     * REQUIRED word has a non-zero score.
     *
     * @param wikiname The name of the page
     * @param scores The per-query score array produced by {@link #scoreContentOccurrences(String)}
     * @return The total score across all queries, or {@code -1} if a REQUIRED word has no score
     */
    private int applyNameBonusAndRequiredFilter( final String wikiname, final int[] scores ) {
        final String lowerWikiname = wikiname.toLowerCase( Locale.ROOT );
        int totalscore = 0;

        for( int j = 0; j < scores.length; j++ ) {
            // Give five points for each occurrence of the word in the wiki name.
            if( lowerWikiname.contains( queries[ j ].word ) && queries[j].type != QueryItem.FORBIDDEN ) {
                scores[j] += 5;
            }

            //  Filter out pages if the search word is marked 'required' but they have no score.
            if( queries[j].type == QueryItem.REQUIRED && scores[j] == 0 ) {
                return -1;
            }

            //  Count the total score for this page.
            totalscore += scores[j];
        }

        return totalscore;
    }

    /**
     *  A local search result.
     */
    public class SearchResultImpl implements com.wikantik.api.search.SearchResult {

        final int  score;
        final Page page;

        /**
         *  Create a new SearchResult with a given name and a score.
         *
         *  @param name Page Name
         *  @param score A score from 0+
         */
        public SearchResultImpl( final String name, final int score ) {
            this.page  = Wiki.contents().page( engine, name );
            this.score = score;
        }

        /**
         *  Returns Wikipage for this result.
         *  @return WikiPage
         */
        @Override
        public WikiPage getPage() {
            return ( WikiPage )page;
        }

        /**
         *  Returns a score for this match.
         *
         *  @return Score from 0+
         */
        @Override
        public int getScore() {
            return score;
        }

        /**
         *  Returns an empty array.
         *  
         *  @return an empty array
         */
        @Override
        public String[] getContexts() {
            // Unimplemented
            return new String[0];
        }
    }

}
