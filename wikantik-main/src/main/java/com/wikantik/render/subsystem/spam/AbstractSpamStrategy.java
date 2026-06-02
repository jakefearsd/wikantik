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
 * Shared base for the spam-strategy components ({@code DefaultSpam*}). Holds the
 * common {@code stopAtFirstMatch}/{@code errorPage} configuration and the
 * strategy helpers that were previously copy-pasted (verbatim from the legacy
 * SpamFilter) into each implementation: {@link #checkStrategy}, redirect-page
 * resolution, and the {@link SpamLog} delegate. Keeping them here ensures the
 * three components score and redirect identically.
 */
abstract class AbstractSpamStrategy {

    /** Request-scoped cumulative spam score, shared across all strategy components. */
    protected static final String ATTR_SPAMFILTER_SCORE = "spamfilter.score";

    protected final boolean stopAtFirstMatch;
    protected final String  errorPage;

    protected AbstractSpamStrategy( final boolean stopAtFirstMatch, final String errorPage ) {
        this.stopAtFirstMatch = stopAtFirstMatch;
        this.errorPage = errorPage;
    }

    /**
     * Either redirects immediately (when {@code stopAtFirstMatch}) by throwing a
     * {@link RedirectException}, or accumulates the request's spam score for the
     * scoring strategy.
     */
    protected void checkStrategy( final Context context, final String message ) throws RedirectException {
        if( stopAtFirstMatch ) {
            throw new RedirectException( message, getRedirectPage( context ) );
        }
        Integer score = context.getVariable( ATTR_SPAMFILTER_SCORE );
        if( score != null ) {
            score = score + 1;
        } else {
            score = 1;
        }
        context.setVariable( ATTR_SPAMFILTER_SCORE, score );
    }

    protected String getRedirectPage( final Context ctx ) {
        return ctx.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), errorPage );
    }

    protected static String log( final Context ctx, final int type, final String source, final String message ) {
        return SpamLog.log( ctx, type, source, message );
    }
}
