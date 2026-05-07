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
package com.wikantik.render.subsystem.spam;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.exceptions.RedirectException;

/**
 * Default implementation of {@link SpamPolicy}.
 *
 * <p>Aggregates the three collaborators ({@link SpamRateLimiter},
 * {@link SpamPatternMatcher}, {@link SpamExternalSignals}) according to the
 * configured strategy (eager-stop or score accumulation).
 * Extracted from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public class DefaultSpamPolicy implements SpamPolicy {

    private static final String ATTR_SPAMFILTER_SCORE = "spamfilter.score";
    private static final int    SCORE_LIMIT           = 1;

    private final SpamRateLimiter      rateLimiter;
    private final SpamPatternMatcher   patternMatcher;
    private final SpamExternalSignals  externalSignals;
    private final boolean              stopAtFirstMatch;
    private final String               errorPage;

    public DefaultSpamPolicy( final SpamRateLimiter rateLimiter,
                               final SpamPatternMatcher patternMatcher,
                               final SpamExternalSignals externalSignals,
                               final boolean stopAtFirstMatch,
                               final String errorPage ) {
        this.rateLimiter      = rateLimiter;
        this.patternMatcher   = patternMatcher;
        this.externalSignals  = externalSignals;
        this.stopAtFirstMatch = stopAtFirstMatch;
        this.errorPage        = errorPage;
    }

    @Override
    public void evaluate( final Context context, final String content, final SpamChange change )
            throws RedirectException {
        rateLimiter.checkBanList( context, change );
        rateLimiter.checkSinglePageChange( context, change );
        externalSignals.checkBotTrap( context, change );
        externalSignals.checkUTF8( context, change );
        externalSignals.checkAkismet( context, change );
        patternMatcher.checkIPList( context );
        patternMatcher.checkPatternList( context, change );
        patternMatcher.checkPageName( context );

        if( !stopAtFirstMatch ) {
            final Integer score = context.getVariable( ATTR_SPAMFILTER_SCORE );
            if( score != null && score >= SCORE_LIMIT ) {
                throw new RedirectException(
                        "Herb says you got too many points",
                        context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), errorPage ) );
            }
        }
    }
}
