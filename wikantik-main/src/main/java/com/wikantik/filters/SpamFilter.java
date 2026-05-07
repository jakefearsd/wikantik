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
package com.wikantik.filters;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.render.subsystem.spam.DefaultSpamExternalSignals;
import com.wikantik.render.subsystem.spam.DefaultSpamPatternMatcher;
import com.wikantik.render.subsystem.spam.DefaultSpamPolicy;
import com.wikantik.render.subsystem.spam.DefaultSpamRateLimiter;
import com.wikantik.render.subsystem.spam.SpamChange;
import com.wikantik.render.subsystem.spam.SpamExternalSignals;
import com.wikantik.render.subsystem.spam.SpamPatternMatcher;
import com.wikantik.render.subsystem.spam.SpamPolicy;
import com.wikantik.render.subsystem.spam.SpamLog;
import com.wikantik.render.subsystem.spam.SpamRateLimiter;
import com.wikantik.util.HttpUtil;
import com.wikantik.util.TextUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jakarta.servlet.http.HttpServletRequest;


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
public class SpamFilter implements PageFilter {

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
    public static final String PROP_ALLOWED_GROUPS = "wikantik.filters.spamfilter.allowedgroups";

    /** The filter property name for specifying which captcha technology should be used. Value is <tt>{@value}</tt>. */
    public static final String  PROP_CAPTCHA               = "captcha";

    /** The filter property name for specifying which filter strategy should be used.  Value is <tt>{@value}</tt>. */
    public static final String  PROP_FILTERSTRATEGY        = "strategy";

    /** The string specifying the "eager" strategy. Value is <tt>{@value}</tt>. */
    public static final String  STRATEGY_EAGER             = "eager";

    /** The string specifying the "score" strategy. Value is <tt>{@value}</tt>. */
    public static final String  STRATEGY_SCORE             = "score";

    private static final Logger LOG = LogManager.getLogger( SpamFilter.class );

    /** The HASH_DELAY value is a maximum amount of time that an user can keep
     *  a session open, because after the value has expired, we will invent a new
     *  hash field name.  By default this is {@value} hours, which should be ample
     *  time for someone.
     */
    private static final long HASH_DELAY = 24;

    private static volatile String   hashName;
    private static volatile long     lastUpdate;

    // ---- helpers wired up during initialize ----
    private SpamRateLimiter    rateLimiter;
    private SpamPatternMatcher patternMatcher;
    private SpamExternalSignals externalSignals;
    private SpamPolicy         spamPolicy;

    // ---- per-filter state needed before helpers are built ----
    private PageManager        pageManager;
    private AttachmentManager  attachmentManager;
    private volatile boolean   ignoreAuthenticated;
    private String[]           allowedGroups = new String[0];

    /**
     *  Package-private constructor for unit testing. Allows injection of manager
     *  dependencies without booting a full engine.
     *
     *  @param pageManager the PageManager to use
     *  @param attachmentManager the AttachmentManager to use
     */
    SpamFilter( final PageManager pageManager, final AttachmentManager attachmentManager ) {
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        // Build helpers with default properties so the test constructor is usable immediately.
        buildHelpers( new Properties(), true, "RejectedMessage" );
    }

    /**
     *  Default constructor used by the filter framework.
     */
    public SpamFilter() {
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) {
        this.pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
        this.attachmentManager = PageSubsystemBridge.fromLegacyEngine( engine ).attachments();

        ignoreAuthenticated = TextUtil.getBooleanProperty( properties, PROP_IGNORE_AUTHENTICATED, false );
        allowedGroups = StringUtils.split(
                StringUtils.defaultString( properties.getProperty( PROP_ALLOWED_GROUPS, "" ) ), ',' );

        final String errorPage = properties.getProperty( PROP_ERRORPAGE, "RejectedMessage" );
        final boolean stopAtFirstMatch = STRATEGY_EAGER.equals(
                TextUtil.getStringProperty( properties, PROP_FILTERSTRATEGY, STRATEGY_EAGER ) );

        buildHelpers( properties, stopAtFirstMatch, errorPage );

        LOG.info( "# Spam filter initialized.  Temporary ban time {} mins, max page changes/minute: {}",
                  TextUtil.getIntegerProperty( properties, PROP_BANTIME, 60 ),
                  TextUtil.getIntegerProperty( properties, PROP_PAGECHANGES, 5 ) );
    }

    private void buildHelpers( final Properties properties,
                                final boolean stopAtFirstMatch,
                                final String errorPage ) {
        this.rateLimiter    = new DefaultSpamRateLimiter( properties, stopAtFirstMatch, errorPage );
        this.patternMatcher = new DefaultSpamPatternMatcher(
                pageManager, attachmentManager, properties, stopAtFirstMatch );
        this.externalSignals = new DefaultSpamExternalSignals( properties, stopAtFirstMatch, errorPage );
        this.spamPolicy     = new DefaultSpamPolicy(
                rateLimiter, patternMatcher, externalSignals, stopAtFirstMatch, errorPage );
    }

    /** {@inheritDoc} */
    @Override
    public String preSave( final Context context, final String content ) throws RedirectException {
        rateLimiter.cleanBanList();
        patternMatcher.refreshBlacklists( context );
        final SpamChange change = patternMatcher.getChange( context, content );

        if( !ignoreThisUser( context ) ) {
            spamPolicy.evaluate( context, content, change );
        }

        SpamLog.log( context, SpamLog.ACCEPT, "-", change.toString() );
        return content;
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
            patternMatcher.checkPatternList( context, profile.getEmail() );
            patternMatcher.checkPatternList( context, profile.getFullname() );
            patternMatcher.checkPatternList( context, profile.getLoginName() );
        } catch( final RedirectException e ) {
            LOG.info( "Detected attempt to create a spammer user account (see above for rejection reason)" );
            return false;
        }
        return true;
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
    @SuppressWarnings( "PMD.NonThreadSafeSingleton" ) // hashName is refreshed idempotently; a race only causes a harmless extra ID generation.
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
            hashName = SpamLog.uniqueID().toLowerCase( Locale.ROOT );
            lastUpdate = System.currentTimeMillis();
        }
        return hash != null ? hash : hashName;
    }

    // ---- Accessors for Ckpt 4 wiring ----

    /** Returns the {@link SpamRateLimiter} used by this filter instance. */
    public SpamRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    /** Returns the {@link SpamPatternMatcher} used by this filter instance. */
    public SpamPatternMatcher getPatternMatcher() {
        return patternMatcher;
    }

    /** Returns the {@link SpamExternalSignals} used by this filter instance. */
    public SpamExternalSignals getExternalSignals() {
        return externalSignals;
    }

    /** Returns the {@link SpamPolicy} used by this filter instance. */
    public SpamPolicy getPolicy() {
        return spamPolicy;
    }

    // ---- private helpers ----

    private boolean ignoreThisUser( final Context context ) {
        if( context.hasAdminPermissions() ) {
            return true;
        }
        final List<String> groups = Arrays.asList( allowedGroups );
        if( Arrays.stream( context.getWikiSession().getRoles() ).anyMatch( role -> groups.contains( role.getName() ) ) ) {
            return true;
        }
        return ( ignoreAuthenticated && context.getWikiSession().isAuthenticated() )
                || context.getVariable( "captcha" ) != null;
    }

}
