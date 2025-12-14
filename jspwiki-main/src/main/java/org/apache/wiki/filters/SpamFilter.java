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
package org.apache.wiki.filters;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.InternalWikiException;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.exceptions.RedirectException;
import org.apache.wiki.api.filters.BasePageFilter;
import org.apache.wiki.api.providers.WikiProvider;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.auth.user.UserProfile;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.ui.EditorManager;
import org.apache.wiki.util.FileUtil;
import org.apache.wiki.util.HttpUtil;
import org.apache.wiki.util.TextUtil;
import org.suigeneris.jrcs.diff.Diff;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.Revision;
import org.suigeneris.jrcs.diff.delta.AddDelta;
import org.suigeneris.jrcs.diff.delta.ChangeDelta;
import org.suigeneris.jrcs.diff.delta.DeleteDelta;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.suigeneris.jrcs.diff.myers.MyersDiff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.PageContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.thauvin.erik.akismet.Akismet;
import net.thauvin.erik.akismet.AkismetComment;


/**
 *  This is Herb, the JSPWiki spamfilter that can also do choke modifications.
 *
 *  Parameters:
 *  <ul>
 *    <li>wordlist - Page name where the spamword regexps are found.  Use [{SET spamwords='regexp list separated with spaces'}] on
 *     that page.  Default is "SpamFilterWordList".
 *    <li>IPlist - Page name where the IP regexps are found.  Use [{SET ips='regexp list separated with spaces'}] on
 *     that page.  Default is "SpamFilterIPList".
 *    <li>maxpagenamelength - Maximum page name length. Default is 100.
 *    <li>blacklist - The name of an attachment containing the list of spam patterns, one per line. Default is
 *        "SpamFilterWordList/blacklist.txt"</li>
 *    <li>errorpage - The page to which the user is redirected.  Has a special variable $msg which states the reason. Default is "RejectedMessage".
 *    <li>pagechangesinminute - How many page changes are allowed/minute.  Default is 5.</li>
 *    <li>similarchanges - How many similar page changes are allowed before the host is banned.  Default is 2.  (since 2.4.72)</li>
 *    <li>bantime - How long an IP address stays on the temporary ban list (default is 60 for 60 minutes).</li>
 *    <li>maxurls - How many URLs can be added to the page before it is considered spam (default is 5)</li>
 *    <li>akismet-apikey - The Akismet API key (see akismet.org)</li>
 *    <li>ignoreauthenticated - If set to "true", all authenticated users are ignored and never caught in SpamFilter</li>
 *    <li>captcha - Sets the captcha technology to use.  Current allowed values are "none". "asirra" was previously supported however that service has been discontinued.</li>
 *    <li>strategy - Sets the filtering strategy to use.  If set to "eager", will stop at the first probable
 *        match, and won't consider any other tests.  This is the default, as it's considerably lighter. If set to "score", will go through all of the tests
 *        and calculates a score for the spam, which is then compared to a filter level value.
 *  </ul>
 *
 *  <p>Please see the default editors/plain.jsp for examples on how the SpamFilter integrates
 *  with the editor system.</p>
 *  
 *  <p>Changes by admin users are ignored in any case.</p>
 *
 *  @since 2.1.112
 */
public class SpamFilter extends BasePageFilter {
	
    private static final String ATTR_SPAMFILTER_SCORE = "spamfilter.score";
    private static final String REASON_REGEXP = "Regexp";
    private static final String REASON_IP_BANNED_TEMPORARILY = "IPBannedTemporarily";
    private static final String REASON_IP_BANNED_PERMANENTLY = "IPBannedPermanently";
    private static final String REASON_BOT_TRAP = "BotTrap";
    private static final String REASON_AKISMET = "Akismet";
    private static final String REASON_TOO_MANY_URLS = "TooManyUrls";
    private static final String REASON_SIMILAR_MODIFICATIONS = "SimilarModifications";
    private static final String REASON_TOO_MANY_MODIFICATIONS = "TooManyModifications";
    private static final String REASON_PAGENAME_TOO_LONG = "PageNameTooLong";
    private static final String REASON_UTF8_TRAP = "UTF8Trap";

    private static final String LISTVAR = "spamwords";
    private static final String LISTIPVAR = "ips";

    private static final Random RANDOM = ThreadLocalRandom.current();

    /** The filter property name for specifying the page which contains the list of spamwords. Value is <tt>{@value}</tt>. */
    public static final String  PROP_WORDLIST              = "wordlist";

    /** The filter property name for specifying the page which contains the list of IPs to ban. Value is <tt>{@value}</tt>. */
    public static final String  PROP_IPLIST                = "IPlist";

    /** The filter property name for specifying the maximum page name length.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_MAX_PAGENAME_LENGTH   = "maxpagenamelength";

    /** The filter property name for the page to which you are directed if Herb rejects your edit.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_ERRORPAGE             = "errorpage";
    
    /** The filter property name for specifying how many changes is any given IP address
     *  allowed to do per minute.  Value is <tt>{@value}</tt>.
     */
    public static final String  PROP_PAGECHANGES           = "pagechangesinminute";
    
    /** The filter property name for specifying how many similar changes are allowed before a host is banned.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_SIMILARCHANGES        = "similarchanges";
    
    /** The filter property name for specifying how long a host is banned.  Value is <tt>{@value}</tt>.*/
    public static final String  PROP_BANTIME               = "bantime";
    
    /** The filter property name for the attachment containing the blacklist.  Value is <tt>{@value}</tt>.*/
    public static final String  PROP_BLACKLIST             = "blacklist";
    
    /** The filter property name for specifying how many URLs can any given edit contain. Value is <tt>{@value}</tt> */
    public static final String  PROP_MAXURLS               = "maxurls";
    
    /** The filter property name for specifying the Akismet API-key.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_AKISMET_API_KEY       = "akismet-apikey";
    
    /** The filter property name for specifying whether authenticated users should be ignored. Value is <tt>{@value}</tt>. */
    public static final String  PROP_IGNORE_AUTHENTICATED  = "ignoreauthenticated";

    /** The filter property name for specifying groups allowed to bypass the spam filter. Value is <tt>{@value}</tt>. */
    public static final String PROP_ALLOWED_GROUPS = "jspwiki.filters.spamfilter.allowedgroups";
    
    /** The filter property name for specifying which captcha technology should be used. Value is <tt>{@value}</tt>. */
    public static final String  PROP_CAPTCHA               = "captcha";
    
    /** The filter property name for specifying which filter strategy should be used.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_FILTERSTRATEGY        = "strategy";

    /** The string specifying the "eager" strategy. Value is <tt>{@value}</tt>. */
    public static final String  STRATEGY_EAGER             = "eager";
    
    /** The string specifying the "score" strategy. Value is <tt>{@value}</tt>. */
    public static final String  STRATEGY_SCORE             = "score";

    private static final String URL_REGEXP = "(http://|https://|mailto:)([A-Za-z0-9_/\\.\\+\\?\\#\\-\\@=&;]+)";

    private String          forbiddenWordsPage = "SpamFilterWordList";
    private String          forbiddenIPsPage   = "SpamFilterIPList";
    private String          pageNameMaxLength  = "100";
    private String          errorPage          = "RejectedMessage";
    private String          blacklist          = "SpamFilterWordList/blacklist.txt";

    private Collection<Pattern> spamPatterns;
    private Collection<Pattern> IPPatterns;

    private Date lastRebuild = new Date( 0L );

    private static final Logger C_SPAMLOG = LogManager.getLogger( "SpamLog" );
    private static final Logger LOG = LogManager.getLogger( SpamFilter.class );

    private final CopyOnWriteArrayList<Host>    temporaryBanList = new CopyOnWriteArrayList<>();

    private int             banTime = 60; // minutes

    private final CopyOnWriteArrayList<Host>    lastModifications = new CopyOnWriteArrayList<>();

    /** How many times a single IP address can change a page per minute? */
    private int             limitSinglePageChanges = 5;

    /** How many times can you add the exact same string to a page? */
    private int             limitSimilarChanges = 2;

    /** How many URLs can be added at maximum. */
    private int             maxUrls = 10;

    private Pattern         urlPattern;
    private Akismet         akismet;

    private String          akismetAPIKey;

    /** The limit at which we consider something to be spam. */
    private final int             scoreLimit = 1;

    /** If set to true, will ignore anyone who is in Authenticated role. */
    private boolean         ignoreAuthenticated;

    /** Groups allowed to bypass the filter */
    private String[]         allowedGroups;

    private boolean         stopAtFirstMatch = true;

    private static String   hashName;
    private static long     lastUpdate;

    /** The HASH_DELAY value is a maximum amount of time that an user can keep
     *  a session open, because after the value has expired, we will invent a new
     *  hash field name.  By default this is {@value} hours, which should be ample
     *  time for someone.
     */
    private static final long HASH_DELAY = 24;


    /**
     *  {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) {
        forbiddenWordsPage = properties.getProperty( PROP_WORDLIST, forbiddenWordsPage );
        forbiddenIPsPage = properties.getProperty( PROP_IPLIST, forbiddenIPsPage);
        pageNameMaxLength = properties.getProperty( PROP_MAX_PAGENAME_LENGTH, pageNameMaxLength);
        errorPage = properties.getProperty( PROP_ERRORPAGE, errorPage );
        limitSinglePageChanges = TextUtil.getIntegerProperty( properties, PROP_PAGECHANGES, limitSinglePageChanges );
        
        limitSimilarChanges = TextUtil.getIntegerProperty( properties, PROP_SIMILARCHANGES, limitSimilarChanges );

        maxUrls = TextUtil.getIntegerProperty( properties, PROP_MAXURLS, maxUrls );
        banTime = TextUtil.getIntegerProperty( properties, PROP_BANTIME, banTime );
        blacklist = properties.getProperty( PROP_BLACKLIST, blacklist );

        ignoreAuthenticated = TextUtil.getBooleanProperty( properties, PROP_IGNORE_AUTHENTICATED, ignoreAuthenticated );
        allowedGroups = StringUtils.split( StringUtils.defaultString( properties.getProperty( PROP_ALLOWED_GROUPS, blacklist ) ), ',' );

        urlPattern = Pattern.compile( URL_REGEXP );

        akismetAPIKey = TextUtil.getStringProperty( properties, PROP_AKISMET_API_KEY, akismetAPIKey );
        stopAtFirstMatch = TextUtil.getStringProperty( properties, PROP_FILTERSTRATEGY, STRATEGY_EAGER ).equals( STRATEGY_EAGER );

        LOG.info( "# Spam filter initialized.  Temporary ban time " + banTime +
                  " mins, max page changes/minute: " + limitSinglePageChanges );
    }

    private static final int REJECT = 0;
    private static final int ACCEPT = 1;
    private static final int NOTE   = 2;

    private static String log( final Context ctx, final int type, final String source, String message ) {
        message = TextUtil.replaceString( message, "\r\n", "\\r\\n" );
        message = TextUtil.replaceString( message, "\"", "\\\"" );

        final String uid = getUniqueID();
        final String page   = ctx.getPage().getName();
        final String addr   = ctx.getHttpRequest() != null ? HttpUtil.getRemoteAddress( ctx.getHttpRequest() ) : "-";
        final String reason = switch( type ) {
            case REJECT -> "REJECTED";
            case ACCEPT -> "ACCEPTED";
            case NOTE   -> "NOTE";
            default     -> throw new InternalWikiException( "Illegal type " + type );
        };
        C_SPAMLOG.info( reason + " " + source + " " + uid + " " + addr + " \"" + page + "\" " + message );

        return uid;
    }

    /** {@inheritDoc} */
    @Override
    public String preSave( final Context context, final String content ) throws RedirectException {
        cleanBanList();
        refreshBlacklists( context );
        final Change change = getChange( context, content );

        if( !ignoreThisUser( context ) ) {
            checkBanList( context, change );
            checkSinglePageChange( context, change );
            checkIPList( context );
            checkPatternList( context, change );
            checkPageName( context);
        }

        if( !stopAtFirstMatch ) {
            final Integer score = context.getVariable( ATTR_SPAMFILTER_SCORE );

            if( score != null && score >= scoreLimit ) {
                throw new RedirectException( "Herb says you got too many points", getRedirectPage( context ) );
            }
        }

        log( context, ACCEPT, "-", change.toString() );
        return content;
    }

    private void checkPageName(final Context context ) throws RedirectException {
        final Page page = context.getPage();
        final String pageName = page.getName();
        final int maxlength = Integer.parseInt(pageNameMaxLength);
        if ( pageName.length() > maxlength) {
            //
            //  Spam filter has a match.
            //

            final String uid = log( context, REJECT, REASON_PAGENAME_TOO_LONG + "(" + pageNameMaxLength + ")" , pageName);

            LOG.info("SPAM:PageNameTooLong (" + uid + "). The length of the page name is too large (" + pageName.length() + " , limit is " + pageNameMaxLength + ")");
            checkStrategy( context, "Herb says '" + pageName + "' is a bad pageName and I trust Herb! (Incident code " + uid + ")" );

        }
    }

    private void checkStrategy(final Context context, final String message ) throws RedirectException {
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
    
    /**
     *  Parses a list of patterns and returns a Collection of compiled Pattern objects.
     *
     * @param source page containing the list of patterns.
     * @param list list of patterns.
     * @return A Collection of the Patterns that were found from the lists.
     */
    private Collection< Pattern > parseWordList( final Page source, final String list ) {
        final var compiledpatterns = new ArrayList< Pattern >();

        if( list != null ) {
            final StringTokenizer tok = new StringTokenizer( list, " \t\n" );

            while( tok.hasMoreTokens() ) {
                final String pattern = tok.nextToken();

                try {
                    compiledpatterns.add( Pattern.compile( pattern ) );
                } catch( final PatternSyntaxException e ) {
                    LOG.debug( "Malformed spam filter pattern " + pattern );
                    source.setAttribute("error", "Malformed spam filter pattern " + pattern);
                }
            }
        }

        return compiledpatterns;
    }

    /**
     *  Takes a MT-Blacklist -formatted blacklist and returns a list of compiled Pattern objects.
     *
     *  @param list list of patterns.
     *  @return The parsed blacklist patterns.
     */
    private Collection< Pattern > parseBlacklist( final String list ) {
        final var compiledpatterns = new ArrayList< Pattern >();

        if( list != null ) {
            try {
                final BufferedReader in = new BufferedReader( new StringReader(list) );
                String line;
                while( (line = in.readLine() ) != null ) {
                    line = line.trim();
                    if( line.isEmpty() ) continue; // Empty line
                    if( line.startsWith("#") ) continue; // It's a comment

                    int ws = line.indexOf( ' ' );
                    if( ws == -1 ) ws = line.indexOf( '\t' );
                    if( ws != -1 ) line = line.substring( 0, ws );

                    try {
                        compiledpatterns.add( Pattern.compile( line ) );
                    } catch( final PatternSyntaxException e ) {
                        LOG.debug( "Malformed spam filter pattern " + line );
                    }
                }
            } catch( final IOException e ) {
                LOG.info( "Could not read patterns; returning what I got" , e );
            }
        }

        return compiledpatterns;
    }

    /**
     * Takes a single page change and performs a load of tests on the content change. An admin can modify anything.
     *
     * @param context page Context
     * @param newChange page change
     * @throws RedirectException spam filter rejects the page change.
     */
    private synchronized void checkSinglePageChange(final Context context, final Change change )
    		throws RedirectException {
        final HttpServletRequest req = context.getHttpRequest();

        if( req != null ) {
            final String addr = HttpUtil.getRemoteAddress( req );
            int hostCounter = 0;
            int changeCounter = 0;

            LOG.debug( "Change is " + change.change );

            final long time = System.currentTimeMillis() - 60*1000L; // 1 minute

            for( final Iterator< Host > i = lastModifications.iterator(); i.hasNext(); ) {
                final Host host = i.next();

                //  Check if this item is invalid
                if( host.addedTime() < time ) {
                    LOG.debug( "Removed host " + host.address() + " from modification queue (expired)" );
                    i.remove();
                    continue;
                }

                // Check if this IP address has been seen before
                if( host.address().equals( addr ) ) {
                    hostCounter++;
                }

                //  Check, if this change has been seen before
                if( host.change() != null && host.change().equals( change ) ) {
                    changeCounter++;
                }
            }

            //  Now, let's check against the limits.
            if( hostCounter >= limitSinglePageChanges ) {
                final Host host = new Host( addr, null, banTime );
                temporaryBanList.add( host );

                final String uid = log( context, REJECT, REASON_TOO_MANY_MODIFICATIONS, change.change );
                LOG.info( "SPAM:TooManyModifications (" + uid + "). Added host " + addr + " to temporary ban list for doing too many modifications/minute" );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code " + uid + ")" );
            }

            if( changeCounter >= limitSimilarChanges ) {
                final Host host = new Host( addr, null, banTime );
                temporaryBanList.add( host );

                final String uid = log( context, REJECT, REASON_SIMILAR_MODIFICATIONS, change.change );
                LOG.info( "SPAM:SimilarModifications (" + uid + "). Added host " + addr + " to temporary ban list for doing too many similar modifications" );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code "+uid+")");
            }

            //  Calculate the number of links in the addition.
            final String tstChange  = change.toString();
            int urlCounter = 0;
            final Matcher urlMatcher = urlPattern.matcher( tstChange );
            while( urlMatcher.find() ) {
                urlCounter++;
            }

            if( urlCounter > maxUrls ) {
                final Host host = new Host( addr, null, banTime );
                temporaryBanList.add( host );

                final String uid = log( context, REJECT, REASON_TOO_MANY_URLS, change.toString() );
                LOG.info( "SPAM:TooManyUrls (" + uid + "). Added host " + addr + " to temporary ban list for adding too many URLs" );
                checkStrategy( context, "Herb says you look like a spammer, and I trust Herb! (Incident code " + uid + ")" );
            }

            //  Check bot trap
            checkBotTrap( context, change );

            //  Check UTF-8 mangling
            checkUTF8( context, change );

            //  Do Akismet check.  This is good to be the last, because this is the most expensive operation.
            checkAkismet( context, change );

            lastModifications.add( new Host( addr, change, banTime ) );
        }
    }


    /**
     *  Checks against the akismet system.
     *
     * @param context page Context
     * @throws RedirectException spam filter rejects the page change.
     */
    private void checkAkismet( final Context context, final Change change ) throws RedirectException {
        if( akismetAPIKey != null ) {
            if( akismet == null ) {
                LOG.info( "Initializing Akismet spam protection." );
                String fullPageUrl = context.getHttpRequest().getRequestURL().toString();
                String fragment = context.getEngine().getBaseURL();
                fullPageUrl = fullPageUrl.substring(0, fullPageUrl.indexOf(fragment) + fragment.length());
                akismet = new Akismet( akismetAPIKey, fullPageUrl );

                if( !akismet.verifyKey() ) {
                    LOG.error( "Akismet API key cannot be verified.  Please check your config." );
                    akismetAPIKey = null;
                    akismet = null;
                }
            }

            final HttpServletRequest req = context.getHttpRequest();

            //  Akismet will mark all empty statements as spam, so we'll just ignore them.
            if( change.adds == 0 && change.removals > 0 ) {
                return;
            }
            
            if( req != null && akismet != null ) {
                LOG.debug( "Calling Akismet to check for spam..." );

                final StopWatch sw = new StopWatch();
                sw.start();

                final String ipAddress     = HttpUtil.getRemoteAddress( req );
                final String userAgent     = req.getHeader( "User-Agent" );
                final String referrer      = req.getHeader( "Referer");
                final String permalink     = context.getViewURL( context.getPage().getName() );
                final String commentType   = context.getRequestContext().equals( ContextEnum.PAGE_COMMENT.getRequestContext() ) ? "comment" : "edit";
                final String commentAuthor = context.getCurrentUser().getName();
                final String commentAuthorEmail = null;
                final String commentAuthorURL   = null;
                AkismetComment comment = new AkismetComment(ipAddress, userAgent);
                comment.setAuthor(commentAuthor);
                comment.setAuthorEmail(commentAuthorEmail);
                comment.setAuthorUrl(commentAuthorURL);
                comment.setContent(change.toString());
                comment.setPermalink(permalink);
                comment.setReferrer(referrer);
                comment.setType(commentType);
                
                final boolean isSpam = akismet.checkComment(comment);

                sw.stop();
                LOG.debug( "Akismet request done in: " + sw );

                if( isSpam ) {
                    // Host host = new Host( ipAddress, null );
                    // temporaryBanList.add( host );

                    final String uid = log( context, REJECT, REASON_AKISMET, change.toString() );
                    LOG.info( "SPAM:Akismet (" + uid + "). Akismet thinks this change is spam; added host to temporary ban list." );
                    checkStrategy( context, "Akismet tells Herb you're a spammer, Herb trusts Akismet, and I trust Herb! (Incident code " + uid + ")" );
                }
            }
        }
    }

    /**
     * Returns a static string which can be used to detect spambots which just wildly fill in all the fields.
     *
     * @return A string
     */
    public static String getBotFieldName() {
        return "submit_auth";
    }

    /**
     * This checks whether an invisible field is available in the request, and whether it's contents are suspected spam.
     *
     * @param context page Context
     * @param newChange page change
     * @throws RedirectException spam filter rejects the page change.
     */
    private void checkBotTrap( final Context context, final Change change ) throws RedirectException {
        final HttpServletRequest request = context.getHttpRequest();
        if( request != null ) {
            final String unspam = request.getParameter( getBotFieldName() );
            if( unspam != null && !unspam.isEmpty() ) {
                final String uid = log( context, REJECT, REASON_BOT_TRAP, change.toString() );

                LOG.info( "SPAM:BotTrap (" + uid + ").  Wildly behaving bot detected." );
                checkStrategy( context, "Spamming attempt detected. (Incident code " + uid + ")" );
            }
        }
    }

    private void checkUTF8( final Context context, final Change change ) throws RedirectException {
        final HttpServletRequest request = context.getHttpRequest();
        if( request != null ) {
            final String utf8field = request.getParameter( "encodingcheck" );
            if( utf8field != null && !utf8field.equals( "\u3041" ) ) {
                final String uid = log( context, REJECT, REASON_UTF8_TRAP, change.toString() );

                LOG.info( "SPAM:UTF8Trap (" + uid + ").  Wildly posting dumb bot detected." );
                checkStrategy( context, "Spamming attempt detected. (Incident code " + uid + ")" );
            }
        }
    }

    /** Goes through the ban list and cleans away any host which has expired from it. */
    private synchronized void cleanBanList() {
        final long now = System.currentTimeMillis();
        for( final Iterator< Host > i = temporaryBanList.iterator(); i.hasNext(); ) {
            final Host host = i.next();

            if( host.releaseTime() < now ) {
                LOG.debug( "Removed host " + host.address() + " from temporary ban list (expired)" );
                i.remove();
            }
        }
    }

    /**
     *  Checks the ban list if the IP address of the changer is already on it.
     *
     *  @param context page context
     *  @throws RedirectException spam filter rejects the page change.
     */
    private void checkBanList( final Context context, final Change change ) throws RedirectException {
        final HttpServletRequest req = context.getHttpRequest();

        if( req != null ) {
            final String remote = HttpUtil.getRemoteAddress(req);
            final long now = System.currentTimeMillis();

            for( final Host host : temporaryBanList ) {
                if( host.address().equals( remote ) ) {
                    final long timeleft = ( host.releaseTime() - now ) / 1000L;

                    log( context, REJECT, REASON_IP_BANNED_TEMPORARILY, change.change );
                    checkStrategy( context,
                            "You have been temporarily banned from modifying this wiki. (" + timeleft + " seconds of ban left)" );
                }
            }
        }
    }

    /**
     *  If the spam filter notices changes in the black list page, it will refresh them automatically.
     *
     *  @param context associated WikiContext
     */
    private void refreshBlacklists( final Context context ) {
        try {
            boolean rebuild = false;

            //  Rebuild, if the spam words page, the attachment or the IP ban page has changed since.
            final Page sourceSpam = context.getEngine().getManager( PageManager.class ).getPage( forbiddenWordsPage );
            if( sourceSpam != null ) {
                if( spamPatterns == null || spamPatterns.isEmpty() || sourceSpam.getLastModified().after( lastRebuild ) ) {
                    rebuild = true;
                }
            }

            final Attachment att = context.getEngine().getManager( AttachmentManager.class ).getAttachmentInfo( context, blacklist );
            if( att != null ) {
                if( spamPatterns == null || spamPatterns.isEmpty() || att.getLastModified().after( lastRebuild ) ) {
                    rebuild = true;
                }
            }

            final Page sourceIPs = context.getEngine().getManager( PageManager.class ).getPage( forbiddenIPsPage );
            if( sourceIPs != null ) {
                if( IPPatterns == null || IPPatterns.isEmpty() || sourceIPs.getLastModified().after( lastRebuild ) ) {
                    rebuild = true;
                }
            }

            //  Do the actual rebuilding.  For simplicity's sake, we always rebuild the complete filter list regardless of what changed.
            if( rebuild ) {
                lastRebuild = new Date();
                spamPatterns = parseWordList( sourceSpam, ( sourceSpam != null ) ? sourceSpam.getAttribute( LISTVAR ) : null );

                LOG.info( "Spam filter reloaded - recognizing " + spamPatterns.size() + " patterns from page " + forbiddenWordsPage );

                IPPatterns = parseWordList( sourceIPs,  ( sourceIPs != null ) ? sourceIPs.getAttribute( LISTIPVAR ) : null );
                LOG.info( "IP filter reloaded - recognizing " + IPPatterns.size() + " patterns from page " + forbiddenIPsPage );

                if( att != null ) {
                    final InputStream in = context.getEngine().getManager( AttachmentManager.class ).getAttachmentStream(att);
                    final StringWriter out = new StringWriter();
                    FileUtil.copyContents( new InputStreamReader( in, StandardCharsets.UTF_8 ), out );
                    final Collection< Pattern > blackList = parseBlacklist( out.toString() );
                    LOG.info( "...recognizing additional " + blackList.size() + " patterns from blacklist " + blacklist );
                    spamPatterns.addAll( blackList );
                }
            }
        } catch( final IOException ex ) {
            LOG.info( "Unable to read attachment data, continuing...", ex );
        } catch( final ProviderException ex ) {
            LOG.info( "Failed to read spam filter attachment, continuing...", ex );
        }
    }

    /**
     * Does a check against a known pattern list.
     *
     * @param context page Context
     * @param newChange page change
     * @throws RedirectException spam filter rejects the page change.
     */
    private void checkPatternList( final Context context, final Change change ) throws RedirectException {
        // If we have no spam patterns defined, or we're trying to save the page containing the patterns, just return.
        if( spamPatterns == null || context.getPage().getName().equals( forbiddenWordsPage ) ) {
            return;
        }

        String ch = change.toString();
        if( context.getHttpRequest() != null ) {
            ch += HttpUtil.getRemoteAddress( context.getHttpRequest() );
        }

        for( final Pattern p : spamPatterns ) {
            // LOG.debug("Attempting to match page contents with "+p.pattern());

            if( p.matcher( ch ).find() ) {
                //  Spam filter has a match.
                final String uid = log( context, REJECT, REASON_REGEXP + "(" + p.pattern() + ")", ch );

                LOG.info( "SPAM:Regexp (" + uid + "). Content matches the spam filter '" + p.pattern() + "'" );
                checkStrategy( context, "Herb says '" + p.pattern() + "' is a bad spam word and I trust Herb! (Incident code " + uid + ")" );
            }
        }
    }


    /**
     *  Does a check against a pattern list of IPs.
     *
     *  @param context page context
     *  @throws RedirectException spam filter rejects the page change.
     */
    private void checkIPList( final Context context ) throws RedirectException {
        //  If we have no IP patterns defined, or we're trying to save the page containing the IP patterns, just return.
        if( IPPatterns == null || context.getPage().getName().equals( forbiddenIPsPage ) ) {
            return;
        }

        final String remoteIP = HttpUtil.getRemoteAddress( context.getHttpRequest() );
        LOG.info("Attempting to match remoteIP " + remoteIP + " against " + IPPatterns.size() + " patterns");

        for( final Pattern p : IPPatterns ) {
             LOG.debug("Attempting to match remoteIP with " + p.pattern());

            if( p.matcher( remoteIP ).find() ) {

                //  IP filter has a match.
                //
                final String uid = log( context, REJECT, REASON_IP_BANNED_PERMANENTLY + "(" + p.pattern() + ")", remoteIP );

                LOG.info( "SPAM:IPBanList (" + uid + "). remoteIP matches the IP filter '" + p.pattern() + "'" );
                checkStrategy( context, "Herb says '" + p.pattern() + "' is a banned IP and I trust Herb! (Incident code " + uid + ")" );
            }
        }
    }

    private void checkPatternList( final Context context, final String change ) throws RedirectException {
        final Change c = new Change();
        c.change = change;
        checkPatternList( context, c );
    }
 
    /**
     *  Creates a simple text string describing the added content.
     *
     *  @param context page context
     *  @param newText added content
     *  @return Empty string, if there is no change.
     */
    private static Change getChange( final Context context, final String newText ) {
        final Page page = context.getPage();
        final StringBuffer change = new StringBuffer();
        final Engine engine = context.getEngine();
        // Get current page version

        final Change ch = new Change();
        
        try {
            final String oldText = engine.getManager( PageManager.class ).getPureText( page.getName(), WikiProvider.LATEST_VERSION );
            final String[] first  = Diff.stringToArray( oldText );
            final String[] second = Diff.stringToArray( newText );
            final Revision rev = Diff.diff( first, second, new MyersDiff() );

            if( rev == null || rev.size() == 0 ) {
                return ch;
            }
            
            for( int i = 0; i < rev.size(); i++ ) {
                final Delta d = rev.getDelta( i );

                if( d instanceof AddDelta || d instanceof ChangeDelta ) {
                    d.getRevised().toString( change, "", "\r\n" );
                    ch.adds++;
                } else if( d instanceof DeleteDelta ) {
                    ch.removals++;
                }
            }
        } catch( final DifferentiationFailedException e ) {
            LOG.error( "Diff failed", e );
        }

        //  Don't forget to include the change note, too
        final String changeNote = page.getAttribute( Page.CHANGENOTE );
        if( changeNote != null ) {
            change.append( "\r\n" );
            change.append( changeNote );
        }

        //  And author as well
        if( page.getAuthor() != null ) {
            change.append( "\r\n" ).append( page.getAuthor() );
        }

        ch.change = change.toString();
        return ch;
    }

    /**
     * Returns true, if this user should be ignored.  For example, admin users.
     *
     * @param context page context
     * @return True, if this user should be ignored.
     */
    private boolean ignoreThisUser( final Context context ) {
        if( context.hasAdminPermissions() ) {
            return true;
        }

        final List< String > groups = Arrays.asList( allowedGroups );
        if( Arrays.stream( context.getWikiSession().getRoles() ).anyMatch( role -> groups.contains( role.getName() ) ) ) {
            return true;
        }

        if( ignoreAuthenticated && context.getWikiSession().isAuthenticated() ) {
            return true;
        }

        return context.getVariable("captcha") != null;
    }

    /**
     *  Returns a random string of six uppercase characters.
     *
     *  @return A random string
     */
    private static String getUniqueID() {
        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < 6; i++ ) {
            final char x = ( char )( 'A' + RANDOM.nextInt( 26 ) );
            sb.append( x );
        }

        return sb.toString();
    }

    /**
     *  Returns a page to which we shall redirect, based on the current value of the "captcha" parameter.
     *
     *  @param ctx WikiContext
     *  @return An URL to redirect to
     */
    private String getRedirectPage( final Context ctx ) {

        return ctx.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), errorPage );
    }

    /**
     *  Checks whether the UserProfile matches certain checks.
     *
     *  @param profile The profile to check
     *  @param context The WikiContext
     *  @return False, if this userprofile is suspect and should not be allowed to be added.
     *  @since 2.6.1
     */
    public boolean isValidUserProfile( final Context context, final UserProfile profile ) {
        try {
            checkPatternList( context, profile.getEmail() );
            checkPatternList( context, profile.getFullname() );
            checkPatternList( context, profile.getLoginName() );
        } catch( final RedirectException e ) {
            LOG.info("Detected attempt to create a spammer user account (see above for rejection reason)");
            return false;
        }

        return true;
    }

    /**
     *  This method is used to calculate an unique code when submitting the page to detect edit conflicts.  
     *  It currently incorporates the last-modified date of the page, and the IP address of the submitter.
     *
     *  @param page The WikiPage under edit
     *  @param request The HTTP Request
     *  @since 2.6
     *  @return A hash value for this page and session
     */
    public static String getSpamHash( final Page page, final HttpServletRequest request ) {
        long lastModified = 0;

        if( page.getLastModified() != null ) {
            lastModified = page.getLastModified().getTime();
        }
        final long remote = HttpUtil.getRemoteAddress( request ).hashCode();

        return Long.toString( lastModified ^ remote );
    }

    /**
     *  Returns the name of the hash field to be used in this request. The value is unique per session, and once 
     *  the session has expired, you cannot edit anymore.
     *
     *  @param request The page request
     *  @return The name to be used in the hash field
     *  @since  2.6
     */
    public static String getHashFieldName( final HttpServletRequest request ) {
        String hash = null;

        if( request.getSession() != null ) {
            hash = ( String )request.getSession().getAttribute( "_hash" );

            if( hash == null ) {
                hash = hashName;
                request.getSession().setAttribute( "_hash", hash );
            }
        }

        if( hashName == null || lastUpdate < ( System.currentTimeMillis() - HASH_DELAY * 60 * 60 * 1000 ) ) {
            hashName = getUniqueID().toLowerCase();
            lastUpdate = System.currentTimeMillis();
        }

        return hash != null ? hash : hashName;
    }


    /**
     *  This method checks if the hash value is still valid, i.e. if it exists at all. This can occur in two cases: 
     *  either this is a spam bot which is not adaptive, or it is someone who has been editing one page for too long, 
     *  and their session has expired.
     *  <p>
     *  This method puts a redirect to the http response field to page "SessionExpired" and logs the incident in 
     *  the spam log (it may or may not be spam, but it's rather likely that it is).
     *
     *  @param context The WikiContext
     *  @param pageContext The JSP PageContext.
     *  @return True, if hash is okay.  False, if hash is not okay, and you need to redirect.
     *  @throws IOException If redirection fails
     *  @since 2.6
     */
    public static boolean checkHash( final Context context, final PageContext pageContext ) throws IOException {
        final String hashName = getHashFieldName( (HttpServletRequest)pageContext.getRequest() );
        if( pageContext.getRequest().getParameter(hashName) == null ) {
            if( pageContext.getAttribute( hashName ) == null ) {
                final Change change = getChange( context, EditorManager.getEditedText( pageContext ) );
                log( context, REJECT, "MissingHash", change.change );

                final String redirect = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(),"SessionExpired" );
                ( ( HttpServletResponse )pageContext.getResponse() ).sendRedirect( redirect );
                return false;
            }
        }

        return true;
    }

    /**
     * This helper method adds all the input fields to your editor that the SpamFilter requires
     * to check for spam.  This <i>must</i> be in your editor form if you intend to use the SpamFilter.
     *  
     * @param pageContext The PageContext
     * @return A HTML string which contains input fields for the SpamFilter.
     */
    public static String insertInputFields( final PageContext pageContext ) {
        final Context ctx = Context.findContext( pageContext );
        final Engine engine = ctx.getEngine();
        final StringBuilder sb = new StringBuilder();
        if( engine.getContentEncoding().equals( StandardCharsets.UTF_8 ) ) {
            sb.append( "<input name='encodingcheck' type='hidden' value='\u3041' />\n" );
        }

        return sb.toString();
    }
    
    /**
     *  A local class for storing host information.
     */
    private record Host( String address, Change change, long addedTime, long releaseTime ) {

        Host( final String address, final Change change, final int banTimeMinutes ) {
            this( address, change, System.currentTimeMillis(),
                  System.currentTimeMillis() + banTimeMinutes * 60 * 1000L );
        }
    }
    
    private static class Change {
    	
        public String change;
        public int    adds;
        public int    removals;

        @Override
        public String toString() {
            return change;
        }

        @Override
        public boolean equals( final Object o ) {
            if( o instanceof Change c ) {
                return change.equals( c.change );
            }
            return false;
        }

        @Override
        public int hashCode() {
            return change.hashCode() + 17;
        }
        
    }

}
