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
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.util.FileUtil;
import com.wikantik.util.HttpUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.suigeneris.jrcs.diff.Diff;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.Revision;
import org.suigeneris.jrcs.diff.delta.AddDelta;
import org.suigeneris.jrcs.diff.delta.ChangeDelta;
import org.suigeneris.jrcs.diff.delta.DeleteDelta;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.suigeneris.jrcs.diff.myers.MyersDiff;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default implementation of {@link SpamPatternMatcher}.
 *
 * <p>Owns the regex collections ({@code spamPatterns}, {@code IPPatterns}) and
 * their wiki-page-driven reload path, plus the page-name-length check.
 * Moved verbatim from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public class DefaultSpamPatternMatcher implements SpamPatternMatcher {

    private static final Logger LOG = LogManager.getLogger( DefaultSpamPatternMatcher.class );

    private static final String LISTVAR   = "spamwords";
    private static final String LISTIPVAR = "ips";

    private static final String ATTR_SPAMFILTER_SCORE = "spamfilter.score";
    private static final String REASON_REGEXP            = "Regexp";
    private static final String REASON_IP_BANNED_PERMANENTLY = "IPBannedPermanently";
    private static final String REASON_PAGENAME_TOO_LONG = "PageNameTooLong";

    private final PageManager       pageManager;
    private final AttachmentManager attachmentManager;

    private final String  forbiddenWordsPage;
    private final String  forbiddenIPsPage;
    private final String  pageNameMaxLength;
    private final String  errorPage;
    private final String  blacklist;
    private final boolean stopAtFirstMatch;

    private Collection<Pattern> spamPatterns;
    private Collection<Pattern> IPPatterns;
    private Date lastRebuild = new Date( 0L );

    public DefaultSpamPatternMatcher( final PageManager pageManager,
                                      final AttachmentManager attachmentManager,
                                      final Properties props,
                                      final boolean stopAtFirstMatch ) {
        this.pageManager       = pageManager;
        this.attachmentManager = attachmentManager;
        this.forbiddenWordsPage = props.getProperty( "wordlist",        "SpamFilterWordList" );
        this.forbiddenIPsPage   = props.getProperty( "IPlist",          "SpamFilterIPList" );
        this.pageNameMaxLength  = props.getProperty( "maxpagenamelength", "100" );
        this.errorPage          = props.getProperty( "errorpage",       "RejectedMessage" );
        this.blacklist          = props.getProperty( "blacklist",       "SpamFilterWordList/blacklist.txt" );
        this.stopAtFirstMatch   = stopAtFirstMatch;
    }

    @Override
    public void refreshBlacklists( final Context context ) {
        try {
            boolean rebuild = false;

            final Page sourceSpam = pageManager.getPage( forbiddenWordsPage );
            if( sourceSpam != null
                    && ( spamPatterns == null || spamPatterns.isEmpty() || sourceSpam.getLastModified().after( lastRebuild ) ) ) {
                rebuild = true;
            }

            final Attachment att = attachmentManager.getAttachmentInfo( context, blacklist );
            if( att != null
                    && ( spamPatterns == null || spamPatterns.isEmpty() || att.getLastModified().after( lastRebuild ) ) ) {
                rebuild = true;
            }

            final Page sourceIPs = pageManager.getPage( forbiddenIPsPage );
            if( sourceIPs != null
                    && ( IPPatterns == null || IPPatterns.isEmpty() || sourceIPs.getLastModified().after( lastRebuild ) ) ) {
                rebuild = true;
            }

            if( rebuild ) {
                lastRebuild  = new Date();
                spamPatterns = parseWordList( sourceSpam, ( sourceSpam != null ) ? sourceSpam.getAttribute( LISTVAR ) : null );
                LOG.info( "Spam filter reloaded - recognizing {} patterns from page {}", spamPatterns.size(), forbiddenWordsPage );

                IPPatterns = parseWordList( sourceIPs, ( sourceIPs != null ) ? sourceIPs.getAttribute( LISTIPVAR ) : null );
                LOG.info( "IP filter reloaded - recognizing {} patterns from page {}", IPPatterns.size(), forbiddenIPsPage );

                if( att != null ) {
                    try ( InputStream in = attachmentManager.getAttachmentStream( att ) ) {
                        final StringWriter out = new StringWriter();
                        FileUtil.copyContents( new InputStreamReader( in, StandardCharsets.UTF_8 ), out );
                        final Collection<Pattern> blackList = parseBlacklist( out.toString() );
                        LOG.info( "...recognizing additional {} patterns from blacklist {}", blackList.size(), blacklist );
                        spamPatterns.addAll( blackList );
                    }
                }
            }
        } catch( final IOException ex ) {
            LOG.info( "Unable to read attachment data, continuing...", ex );
        } catch( final ProviderException ex ) {
            LOG.info( "Failed to read spam filter attachment, continuing...", ex );
        }
    }

    @Override
    public void checkPatternList( final Context context, final SpamChange change ) throws RedirectException {
        if( spamPatterns == null || context.getPage().getName().equals( forbiddenWordsPage ) ) {
            return;
        }
        String ch = change.toString();
        if( context.getHttpRequest() != null ) {
            ch += HttpUtil.getRemoteAddress( context.getHttpRequest() );
        }
        for( final Pattern pattern : spamPatterns ) {
            if( pattern.matcher( ch ).find() ) {
                final String uid = log( context, 0, REASON_REGEXP + "(" + pattern.pattern() + ")", ch );
                LOG.info( "SPAM:Regexp ({}). Content matches the spam filter '{}'", uid, pattern.pattern() );
                checkStrategy( context, "Herb says '" + pattern.pattern() + "' is a bad spam word and I trust Herb! (Incident code " + uid + ")" );
            }
        }
    }

    @Override
    public void checkPatternList( final Context context, final String text ) throws RedirectException {
        final SpamChange changeRecord = new SpamChange();
        changeRecord.change = text;
        checkPatternList( context, changeRecord );
    }

    @Override
    public void checkIPList( final Context context ) throws RedirectException {
        if( IPPatterns == null || context.getPage().getName().equals( forbiddenIPsPage ) ) {
            return;
        }
        final String remoteIP = HttpUtil.getRemoteAddress( context.getHttpRequest() );
        LOG.info( "Attempting to match remoteIP {} against {} patterns", remoteIP, IPPatterns.size() );
        for( final Pattern pattern : IPPatterns ) {
            LOG.debug( "Attempting to match remoteIP with {}", pattern.pattern() );
            if( pattern.matcher( remoteIP ).find() ) {
                final String uid = log( context, 0, REASON_IP_BANNED_PERMANENTLY + "(" + pattern.pattern() + ")", remoteIP );
                LOG.info( "SPAM:IPBanList ({}). remoteIP matches the IP filter '{}'", uid, pattern.pattern() );
                checkStrategy( context, "Herb says '" + pattern.pattern() + "' is a banned IP and I trust Herb! (Incident code " + uid + ")" );
            }
        }
    }

    @Override
    public void checkPageName( final Context context ) throws RedirectException {
        final Page page = context.getPage();
        final String pageName = page.getName();
        final int maxlength = Integer.parseInt( pageNameMaxLength );
        if( pageName.length() > maxlength ) {
            final String uid = log( context, 0, REASON_PAGENAME_TOO_LONG + "(" + pageNameMaxLength + ")", pageName );
            LOG.info( "SPAM:PageNameTooLong ({}). The length of the page name is too large ({} , limit is {})", uid, pageName.length(), pageNameMaxLength );
            checkStrategy( context, "Herb says '" + pageName + "' is a bad pageName and I trust Herb! (Incident code " + uid + ")" );
        }
    }

    @Override
    public SpamChange getChange( final Context context, final String newText ) {
        final Page page = context.getPage();
        final StringBuffer change = new StringBuffer();
        final SpamChange ch = new SpamChange();
        try {
            final String oldText = pageManager.getPureText( page.getName(), WikiProvider.LATEST_VERSION );
            final String[] first  = Diff.stringToArray( oldText );
            final String[] second = Diff.stringToArray( newText );
            final Revision rev = Diff.diff( first, second, new MyersDiff() );
            if( rev == null || rev.size() == 0 ) {
                return ch;
            }
            for( int i = 0; i < rev.size(); i++ ) {
                final Delta delta = rev.getDelta( i );
                if( delta instanceof AddDelta || delta instanceof ChangeDelta ) {
                    delta.getRevised().toString( change, "", "\r\n" );
                    ch.adds++;
                } else if( delta instanceof DeleteDelta ) {
                    ch.removals++;
                }
            }
        } catch( final DifferentiationFailedException e ) {
            // LOG.error justified: diff engine failure is an unrecoverable internal error for this save attempt
            LOG.error( "Diff failed", e );
        }
        final String changeNote = page.getAttribute( Page.CHANGENOTE );
        if( changeNote != null ) {
            change.append( "\r\n" );
            change.append( changeNote );
        }
        if( page.getAuthor() != null ) {
            change.append( "\r\n" ).append( page.getAuthor() );
        }
        ch.change = change.toString();
        return ch;
    }

    @Override
    public String getForbiddenWordsPage() {
        return forbiddenWordsPage;
    }

    @Override
    public String getErrorPage() {
        return errorPage;
    }

    // ---- private helpers (verbatim from SpamFilter) ----

    private Collection<Pattern> parseWordList( final Page source, final String list ) {
        final var compiledpatterns = new ArrayList<Pattern>();
        if( list != null ) {
            final StringTokenizer tok = new StringTokenizer( list, " \t\n" );
            while( tok.hasMoreTokens() ) {
                final String pattern = tok.nextToken();
                try {
                    compiledpatterns.add( Pattern.compile( pattern ) );
                } catch( final PatternSyntaxException e ) {
                    LOG.debug( "Malformed spam filter pattern {}", pattern );
                    source.setAttribute( "error", "Malformed spam filter pattern " + pattern );
                }
            }
        }
        return compiledpatterns;
    }

    private Collection<Pattern> parseBlacklist( final String list ) {
        final var compiledpatterns = new ArrayList<Pattern>();
        if( list != null ) {
            try ( BufferedReader in = new BufferedReader( new StringReader( list ) ) ) {
                String line;
                while( ( line = in.readLine() ) != null ) {
                    line = line.trim();
                    if( line.isEmpty() ) continue;
                    if( line.startsWith( "#" ) ) continue;
                    int ws = line.indexOf( ' ' );
                    if( ws == -1 ) ws = line.indexOf( '\t' );
                    if( ws != -1 ) line = line.substring( 0, ws );
                    try {
                        compiledpatterns.add( Pattern.compile( line ) );
                    } catch( final PatternSyntaxException e ) {
                        LOG.debug( "Malformed spam filter pattern {}", line );
                    }
                }
            } catch( final IOException e ) {
                LOG.info( "Could not read patterns; returning what I got", e );
            }
        }
        return compiledpatterns;
    }

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
