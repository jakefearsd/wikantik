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
import com.wikantik.util.HttpUtil;
import com.wikantik.util.TextUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link SpamRateLimiter}.
 *
 * <p>Owns the in-memory temporary ban list, the per-IP page-changes-per-minute
 * window, and the similar-modifications counter. Moved verbatim from
 * {@code SpamFilter} in Phase 6 Checkpoint 3 of the wikantik-main subsystem
 * decomposition.</p>
 */
public class DefaultSpamRateLimiter implements SpamRateLimiter {

    private static final Logger LOG = LogManager.getLogger( DefaultSpamRateLimiter.class );

    private static final String ATTR_SPAMFILTER_SCORE = "spamfilter.score";
    private static final String REASON_IP_BANNED_TEMPORARILY = "IPBannedTemporarily";
    private static final String REASON_SIMILAR_MODIFICATIONS = "SimilarModifications";
    private static final String REASON_TOO_MANY_MODIFICATIONS = "TooManyModifications";
    private static final String REASON_TOO_MANY_URLS = "TooManyUrls";

    private static final String URL_REGEXP = "(http://|https://|mailto:)([A-Za-z0-9_/\\.\\+\\?\\#\\-\\@=&;]+)";

    private final CopyOnWriteArrayList<SpamHost> temporaryBanList   = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SpamHost> lastModifications   = new CopyOnWriteArrayList<>();

    private final int    banTime;
    private final int    limitSinglePageChanges;
    private final int    limitSimilarChanges;
    private final int    maxUrls;
    private final boolean stopAtFirstMatch;
    private final String  errorPage;

    private final Pattern urlPattern;

    public DefaultSpamRateLimiter( final Properties props,
                                   final boolean stopAtFirstMatch,
                                   final String errorPage ) {
        this.banTime               = TextUtil.getIntegerProperty( props, "bantime",             60 );
        this.limitSinglePageChanges = TextUtil.getIntegerProperty( props, "pagechangesinminute",  5 );
        this.limitSimilarChanges    = TextUtil.getIntegerProperty( props, "similarchanges",       2 );
        this.maxUrls                = TextUtil.getIntegerProperty( props, "maxurls",             10 );
        this.stopAtFirstMatch       = stopAtFirstMatch;
        this.errorPage              = errorPage;
        this.urlPattern             = Pattern.compile( URL_REGEXP );
    }

    @Override
    public synchronized void cleanBanList() {
        final long now = System.currentTimeMillis();
        for( final Iterator<SpamHost> i = temporaryBanList.iterator(); i.hasNext(); ) {
            final SpamHost host = i.next();
            if( host.releaseTime() < now ) {
                LOG.debug( "Removed host {} from temporary ban list (expired)", host.address() );
                i.remove();
            }
        }
    }

    @Override
    public void checkBanList( final Context context, final SpamChange change ) throws RedirectException {
        final HttpServletRequest req = context.getHttpRequest();
        if( req != null ) {
            final String remote = HttpUtil.getRemoteAddress( req );
            final long now = System.currentTimeMillis();
            for( final SpamHost host : temporaryBanList ) {
                if( host.address().equals( remote ) ) {
                    final long timeleft = ( host.releaseTime() - now ) / 1000L;
                    log( context, 0, REASON_IP_BANNED_TEMPORARILY, change.change );
                    checkStrategy( context,
                            "You have been temporarily banned from modifying this wiki. (" + timeleft + " seconds of ban left)" );
                }
            }
        }
    }

    @Override
    public synchronized void checkSinglePageChange( final Context context, final SpamChange change )
            throws RedirectException {
        final HttpServletRequest req = context.getHttpRequest();
        if( req != null ) {
            final String addr = HttpUtil.getRemoteAddress( req );
            int hostCounter   = 0;
            int changeCounter = 0;

            LOG.debug( "Change is {}", change.change );

            final long time = System.currentTimeMillis() - 60 * 1000L; // 1 minute

            for( final Iterator<SpamHost> i = lastModifications.iterator(); i.hasNext(); ) {
                final SpamHost host = i.next();
                if( host.addedTime() < time ) {
                    LOG.debug( "Removed host {} from modification queue (expired)", host.address() );
                    i.remove();
                    continue;
                }
                if( host.address().equals( addr ) ) {
                    hostCounter++;
                }
                if( host.change() != null && host.change().equals( change ) ) {
                    changeCounter++;
                }
            }

            if( hostCounter >= limitSinglePageChanges ) {
                final SpamHost host = new SpamHost( addr, null, banTime );
                temporaryBanList.add( host );
                final String uid = log( context, 0, REASON_TOO_MANY_MODIFICATIONS, change.change );
                LOG.info( "SPAM:TooManyModifications ({}). Added host {} to temporary ban list for doing too many modifications/minute", uid, addr );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code " + uid + ")" );
            }

            if( changeCounter >= limitSimilarChanges ) {
                final SpamHost host = new SpamHost( addr, null, banTime );
                temporaryBanList.add( host );
                final String uid = log( context, 0, REASON_SIMILAR_MODIFICATIONS, change.change );
                LOG.info( "SPAM:SimilarModifications ({}). Added host {} to temporary ban list for doing too many similar modifications", uid, addr );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code " + uid + ")" );
            }

            // Calculate the number of links in the addition.
            final String  tstChange  = change.toString();
            int urlCounter = 0;
            final Matcher urlMatcher = urlPattern.matcher( tstChange );
            while( urlMatcher.find() ) {
                urlCounter++;
            }

            if( urlCounter > maxUrls ) {
                final SpamHost host = new SpamHost( addr, null, banTime );
                temporaryBanList.add( host );
                final String uid = log( context, 0, REASON_TOO_MANY_URLS, change.toString() );
                LOG.info( "SPAM:TooManyUrls ({}). Added host {} to temporary ban list for adding too many URLs", uid, addr );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code " + uid + ")" );
            }

            // NOTE: checkBotTrap, checkUTF8, checkAkismet are called by DefaultSpamPolicy.evaluate after this method.
            lastModifications.add( new SpamHost( addr, change, banTime ) );
        }
    }

    @Override
    public void recordModification( final String addr, final SpamChange change ) {
        lastModifications.add( new SpamHost( addr, change, banTime ) );
    }

    // ---- internal helpers (verbatim from SpamFilter) ----

    private void checkStrategy( final Context context, final String message ) throws RedirectException {
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

    private String getRedirectPage( final Context ctx ) {
        return ctx.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), errorPage );
    }

    private static String log( final Context ctx, final int type, final String source, final String message ) {
        return SpamLog.log( ctx, type, source, message );
    }
}
