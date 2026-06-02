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
import net.thauvin.erik.akismet.Akismet;
import net.thauvin.erik.akismet.AkismetComment;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Default implementation of {@link SpamExternalSignals}.
 *
 * <p>Owns the Akismet client (lazy-initialised on first use) and the two
 * request-field traps (bot-trap + UTF-8 sentinel).
 * Moved verbatim from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public class DefaultSpamExternalSignals extends AbstractSpamStrategy implements SpamExternalSignals {

    private static final Logger LOG = LogManager.getLogger( DefaultSpamExternalSignals.class );

    private static final String REASON_BOT_TRAP  = "BotTrap";
    private static final String REASON_UTF8_TRAP = "UTF8Trap";
    private static final String REASON_AKISMET   = "Akismet";

    private String  akismetAPIKey;
    private Akismet akismet;

    public DefaultSpamExternalSignals( final Properties props,
                                       final boolean stopAtFirstMatch,
                                       final String errorPage ) {
        super( stopAtFirstMatch, errorPage );
        this.akismetAPIKey  = TextUtil.getStringProperty( props, "akismet-apikey", null );
    }

    @Override
    public void checkBotTrap( final Context context, final SpamChange change ) throws RedirectException {
        final HttpServletRequest request = context.getHttpRequest();
        if( request != null ) {
            final String unspam = request.getParameter( "submit_auth" );
            if( unspam != null && !unspam.isEmpty() ) {
                final String uid = log( context, 0, REASON_BOT_TRAP, change.toString() );
                LOG.info( "SPAM:BotTrap ({}).  Wildly behaving bot detected.", uid );
                checkStrategy( context, "Spamming attempt detected. (Incident code " + uid + ")" );
            }
        }
    }

    @Override
    public void checkUTF8( final Context context, final SpamChange change ) throws RedirectException {
        final HttpServletRequest request = context.getHttpRequest();
        if( request != null ) {
            final String utf8field = request.getParameter( "encodingcheck" );
            if( utf8field != null && !"ぁ".equals( utf8field ) ) {
                final String uid = log( context, 0, REASON_UTF8_TRAP, change.toString() );
                LOG.info( "SPAM:UTF8Trap ({}).  Wildly posting dumb bot detected.", uid );
                checkStrategy( context, "Spamming attempt detected. (Incident code " + uid + ")" );
            }
        }
    }

    @Override
    public void checkAkismet( final Context context, final SpamChange change ) throws RedirectException {
        if( akismetAPIKey != null ) {
            if( akismet == null ) {
                LOG.info( "Initializing Akismet spam protection." );
                String fullPageUrl = context.getHttpRequest().getRequestURL().toString();
                String fragment = context.getEngine().getBaseURL();
                fullPageUrl = fullPageUrl.substring( 0, fullPageUrl.indexOf( fragment ) + fragment.length() );
                akismet = new Akismet( akismetAPIKey, fullPageUrl );
                if( !akismet.verifyKey() ) {
                    // LOG.error justified: Akismet API key is permanently invalid; operator must fix config before spam protection works
                    LOG.error( "Akismet API key cannot be verified.  Please check your config." );
                    akismetAPIKey = null;
                    akismet = null;
                }
            }

            final HttpServletRequest req = context.getHttpRequest();

            // Akismet will mark all empty statements as spam, so we'll just ignore them.
            if( change.adds == 0 && change.removals > 0 ) {
                return;
            }

            if( req != null && akismet != null ) {
                LOG.debug( "Calling Akismet to check for spam..." );
                final StopWatch sw = new StopWatch();
                sw.start();

                final String ipAddress          = HttpUtil.getRemoteAddress( req );
                final String userAgent          = req.getHeader( "User-Agent" );
                final String referrer           = req.getHeader( "Referer" );
                final String permalink          = context.getViewURL( context.getPage().getName() );
                final String commentType        = context.getRequestContext().equals( ContextEnum.PAGE_COMMENT.getRequestContext() ) ? "comment" : "edit";
                final String commentAuthor      = context.getCurrentUser().getName();
                final String commentAuthorEmail = null;
                final String commentAuthorURL   = null;

                final AkismetComment comment = new AkismetComment( ipAddress, userAgent );
                comment.setAuthor( commentAuthor );
                comment.setAuthorEmail( commentAuthorEmail );
                comment.setAuthorUrl( commentAuthorURL );
                comment.setContent( change.toString() );
                comment.setPermalink( permalink );
                comment.setReferrer( referrer );
                comment.setType( commentType );

                final boolean isSpam = akismet.checkComment( comment );
                sw.stop();
                LOG.debug( "Akismet request done in: {}", sw );

                if( isSpam ) {
                    final String uid = log( context, 0, REASON_AKISMET, change.toString() );
                    LOG.info( "SPAM:Akismet ({}). Akismet thinks this change is spam; added host to temporary ban list.", uid );
                    checkStrategy( context, "Akismet tells Herb you're a spammer, Herb trusts Akismet, and I trust Herb! (Incident code " + uid + ")" );
                }
            }
        }
    }

    // SpamLog/checkStrategy/getRedirectPage helpers are inherited from AbstractSpamStrategy.
}
