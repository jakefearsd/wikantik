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
package com.wikantik.auth.acl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.util.comparators.PrincipalComparator;

import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Default implementation that parses Acls from wiki page markup.
 *
 * @since 2.3
 */
public class DefaultAclManager implements AclManager {

    private static final Logger LOG = LogManager.getLogger(DefaultAclManager.class);

    private AuthorizationManager auth;
    private Engine engine;
    private static final String PERM_REGEX = "("
                                              + PagePermission.COMMENT_ACTION + "|"
                                              + PagePermission.DELETE_ACTION  + "|"
                                              + PagePermission.EDIT_ACTION    + "|"
                                              + PagePermission.MODIFY_ACTION  + "|"
                                              + PagePermission.RENAME_ACTION  + "|"
                                              + PagePermission.UPLOAD_ACTION  + "|"
                                              + PagePermission.VIEW_ACTION    +
                                             ")";
    private static final String ACL_REGEX = "\\[\\{\\s*ALLOW\\s+" + PERM_REGEX + "\\s*(.*?)\\s*\\}\\]";

    /**
     * Identifies ACL strings in wiki text; the first group is the action (view, edit) and
     * the second is the list of Principals separated by commas. The overall match is
     * the ACL string from [{ to }].
     *
     * <p>Kept for callers that match against it. Nothing in this class enforces through it any more: its adjacent
     * unbounded quantifiers are cubic on a run of whitespace, so the text a user controls is scanned by
     * {@code AclDirectiveScanner}, which finds exactly the same matches in linear time.
     */
    public static final Pattern ACL_PATTERN = Pattern.compile( ACL_REGEX );

    /** The {@code [{ } ]} wrapper around a directive — see {@link #ruleLineOf}. */
    private static final Pattern DIRECTIVE_BRACES = Pattern.compile( "^\\[\\{|\\}\\]$" );

    /**
     * The principal a locked page's ACL grants everything to. No session holds it — it is no role, group or login, and
     * its name holds a NUL, which no login can — so only {@code AllPermission} (the Admin group) gets past such an ACL.
     */
    public static final Principal LOCKED_PRINCIPAL = new LockedPrincipal();

    /** Every page action, for the locked ACL's one entry. */
    private static final String ALL_PAGE_ACTIONS = String.join( ",", PagePermission.COMMENT_ACTION, PagePermission.DELETE_ACTION,
            PagePermission.EDIT_ACTION, PagePermission.MODIFY_ACTION, PagePermission.RENAME_ACTION, PagePermission.UPLOAD_ACTION,
            PagePermission.VIEW_ACTION );

    /** Parsed-ACL cache surviving Page-instance churn. Keyed by page name; entry valid only for a matching (version, lastModified). */
    private record CachedAcl( int version, long lastModified, Acl acl ) {}

    private final Cache< String, CachedAcl > aclCache =
        Caffeine.newBuilder().maximumSize( 20_000 ).build();

    /** Page versions whose lock has been logged ({@code name@version}). */
    private final Cache< String, Boolean > lockWarned =
        Caffeine.newBuilder().maximumSize( 20_000 ).build();

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) {
        auth = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        this.engine = engine;
    }

    /** {@inheritDoc} */
    @Override
    public Acl parseAcl( final Page page, final Acl into, final String ruleLine ) throws WikiSecurityException {
        final Permission permission = checkRuleLine( page, ruleLine );
        final StringTokenizer fieldToks = new StringTokenizer( ruleLine );
        fieldToks.nextToken();
        fieldToks.nextToken();
        while( fieldToks.hasMoreTokens() ) {
            final String principalName = fieldToks.nextToken( "," ).trim();
            final Principal principal = auth.resolvePrincipal( principalName );
            final AclEntry oldEntry = into.getAclEntry( principal );
            if( oldEntry != null ) {
                LOG.debug( "Adding to old acl list: {}, {}", principal, permission );
                oldEntry.addPermission( permission );
            } else {
                LOG.debug( "Adding new acl entry for {}", permission );
                final AclEntry entry = Wiki.acls().entry();
                entry.setPrincipal( principal );
                entry.addPermission( permission );
                into.addEntry( entry );
            }
        }
        return into;
    }

    /**
     * Whether {@link #parseAcl} accepts the shape of {@code ruleLine}: a keyword, then an action list naming page
     * actions (the principals are not resolved). The render path uses it to show an error in place of a rule the ACL
     * parser would refuse.
     *
     * @param page     the page the rule belongs to
     * @param ruleLine the rule line ({@code ALLOW view Alice})
     * @return the rule's permission on {@code page}
     * @throws WikiSecurityException with {@link #parseAcl}'s message when the line is refused
     */
    public static Permission checkRuleLine( final Page page, final String ruleLine ) throws WikiSecurityException {
        final StringTokenizer fieldToks = new StringTokenizer( ruleLine );
        final String actions;
        try {
            fieldToks.nextToken();
            actions = fieldToks.nextToken();
        } catch( final NoSuchElementException nsee ) {
            LOG.warn( "Invalid access rule: {} - defaults will be used.", ruleLine );
            throw new WikiSecurityException( "Invalid access rule: " + ruleLine, nsee );
        }
        try {
            return PermissionFactory.getPagePermission( page, actions );
        } catch( final IllegalArgumentException iae ) {
            throw new WikiSecurityException( "Invalid permission type: " + ruleLine, iae );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Built from {@link AccessRules#read} of the page text, cached by (name, version, lastModified) as an immutable
     * ACL. The page's own ACL is never consulted (a render or a caller may have set anything there); it is set to the
     * result, for callers that read it afterwards.
     */
    @Override
    public Acl getPermissions( final Page page ) {
        if( page instanceof Attachment att ) {
            final Page parent = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( att.getParentName() );
            return getPermissions( parent );
        }

        //  Parsed-ACL cache keyed by (name, version, lastModified) survives the Page-instance
        //  churn caused by the 60s page-cache TTL, so bulk viewability filtering doesn't have
        //  to re-read and re-scan every page body on every cache recycle.
        final long lastModified = page.getLastModified() == null ? 0L : page.getLastModified().getTime();
        final CachedAcl hit = aclCache.getIfPresent( page.getName() );
        if( hit != null && hit.version() == page.getVersion() && hit.lastModified() == lastModified ) {
            page.setAcl( hit.acl() );
            return hit.acl();
        }

        final Acl acl;
        try {
            acl = aclOf( page, PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPageText( page.getName(), page.getVersion() ) );
        } catch( final ProviderException | RuntimeException e ) {
            // Fail closed, and not cached: the next check reads (and resolves) again.
            LOG.warn( "The access rules of page {} could not be read (text or principals); it is locked for now: {}",
                    page.getName(), e.toString() );
            return lockedAcl( page );
        }
        aclCache.put( page.getName(), new CachedAcl( page.getVersion(), lastModified, acl ) );
        page.setAcl( acl );
        return acl;
    }

    /**
     * The immutable ACL a page text gives: open when it has no text or no rules, a lock when its rules cannot be read.
     * A failure to read them at all (a principal that cannot be resolved, say) is thrown, for the caller to lock without
     * caching.
     */
    Acl aclOf( final Page page, final String pageText ) {
        if( pageText == null || pageText.isEmpty() ) {
            return new ReadOnlyAcl( Wiki.acls().acl() );
        }
        return aclOf( page, pageText, AccessRules.read( pageText ) );
    }

    /** The immutable ACL {@code reading} gives; a rule line the ACL parser refuses locks the page. */
    Acl aclOf( final Page page, final String pageText, final AccessRules.Reading reading ) {
        if( reading instanceof AccessRules.Locked locked ) {
            warnLocked( page, locked );
            return lockedAcl( page );
        }
        final AccessRules.Rules rules = ( AccessRules.Rules ) reading;
        final Acl acl = Wiki.acls().acl();
        for( int i = 0; i < rules.ruleLines().size(); i++ ) {
            final String ruleLine = rules.ruleLines().get( i );
            try {
                parseAcl( page, acl, ruleLine );
            } catch( final WikiSecurityException e ) {
                final AccessRules.Span span = rules.spans().get( i );
                warnLocked( page, new AccessRules.Locked( List.of( new AccessRules.Reason( AccessRules.Shape.BAD_PERMISSION,
                        AccessRules.lineOf( pageText, span.start() ), ruleLine ) ), rules.spans() ) );
                return lockedAcl( page );
            }
        }
        return new ReadOnlyAcl( acl );
    }

    /** Logs a lock once per page version, not on every check. */
    private void warnLocked( final Page page, final AccessRules.Locked locked ) {
        if( lockWarned.asMap().putIfAbsent( page.getName() + "@" + page.getVersion(), Boolean.TRUE ) == null ) {
            LOG.warn( "The access rules of page {} (version {}) cannot be read; only administrators may use it until they are "
                    + "fixed: {}", page.getName(), page.getVersion(), locked.reasons() );
        }
    }

    /** An ACL that grants every page permission to {@link #LOCKED_PRINCIPAL} alone: only {@code AllPermission} gets in. */
    private static Acl lockedAcl( final Page page ) {
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( LOCKED_PRINCIPAL );
        entry.addPermission( PermissionFactory.getPagePermission( page, ALL_PAGE_ACTIONS ) );
        acl.addEntry( entry );
        return new ReadOnlyAcl( acl );
    }

    /**
     * Converts a matched ACL directive ({@code [{ALLOW view Alice,Bob}]}) into the
     * brace-free rule line {@link #parseAcl} expects ({@code ALLOW view Alice,Bob}).
     *
     * <p>Without it, {@code parseAcl}'s {@link StringTokenizer} folds
     * the trailing {@code "}]"} into the <em>last</em> principal name, yielding an
     * entry ({@code "Alice}]"}) that can never match a real principal — a non-empty
     * ACL that grants nobody.
     *
     * @param directive a full {@code [{ALLOW …}]} match
     * @return the rule line without the surrounding braces
     */
    static String ruleLineOf( final String directive ) {
        return DIRECTIVE_BRACES.matcher( directive.trim() ).replaceAll( "" ).trim();
    }

    /**
     * The directives the text scan of 2.4.57–2.5.5 enforced in {@code pageText}: every one-line {@code [{ALLOW …}]}
     * match that is NOT inside a Markdown code span or code block. No longer what is enforced ({@link AccessRules} is);
     * kept because {@link AclDirectiveText} hides at least these, and the access-rule audit reports the old reading.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the matched directive strings outside code, in document order
     * @see EnforcedAclDirectives
     */
    static List< String > aclDirectives( final String pageText ) {
        return EnforcedAclDirectives.directives( pageText );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes {@code acl} at the top of the page text in place of the spans {@link AccessRules#read} finds. Refused for
     * a page whose rules cannot be read (rewriting it would drop rules nobody can tell the meaning of) and for a locked
     * ACL.
     */
    @Override
    public void setPermissions( final Page page, final Acl acl ) throws WikiSecurityException {
        if( acl.getAclEntry( LOCKED_PRINCIPAL ) != null ) {
            throw new WikiSecurityException( "Refusing to write the ACL of a locked page " + page.getName() );
        }
        final PageManager pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
        final String pageText;
        try {
            pageText = pageManager.getPageText( page.getName(), page.getVersion() );
        } catch( final ProviderException e ) {
            throw new WikiSecurityException( "Could not read page " + page.getName() + " to rewrite its ACL: " + e.getMessage(), e );
        }
        final String text = pageText == null ? "" : pageText;
        final AccessRules.Reading reading = AccessRules.read( text );
        if( reading instanceof AccessRules.Locked locked ) {
            throw new WikiSecurityException( "The access rules of page " + page.getName() + " cannot be read, so they are not "
                    + "rewritten: " + locked.reasons() );
        }
        final String newText = AccessRules.withRules( AccessRules.without( text, reading.spans() ), DefaultAclManager.printAcl( acl ) );
        try {
            pageManager.putPageText( page, newText );
        } catch( final ProviderException e ) {
            throw new WikiSecurityException( "Could not set Acl. Reason: ProviderExcpetion " + e.getMessage(), e );
        }
    }

    /**
     * Generates an ACL string for inclusion in a wiki page, based on a supplied Acl object. All of the permissions in this Acl are
     * assumed to apply to the same page scope. The names of the pages are ignored; only the actions and principals matter.
     *
     * @param acl the ACL
     * @return the ACL string
     */
    protected static String printAcl( final Acl acl ) {
        // Extract the ACL entries into a Map with keys == permissions, values == principals
        final Map< String, Set< Principal > > permissionPrincipals = new TreeMap<>();
        final Enumeration< AclEntry > entries = acl.aclEntries();
        while( entries.hasMoreElements() ) {
            final AclEntry entry = entries.nextElement();
            final Principal principal = entry.getPrincipal();
            final Enumeration< Permission > permissions = entry.permissions();
            while( permissions.hasMoreElements() ) {
                // A rule may name several actions ("edit,view"); each is printed as a rule of its own.
                for( final String action : permissions.nextElement().getActions().split( "," ) ) {
                    permissionPrincipals.computeIfAbsent( action, a -> new LinkedHashSet<>() ).add( principal );
                }
            }
        }

        // Now, iterate through each permission in the map and generate an ACL string
        final StringBuilder aclText = new StringBuilder();
        for( final Map.Entry< String, Set< Principal > > entry : permissionPrincipals.entrySet() ) {
            final String action = entry.getKey();
            final List< Principal > principals = new ArrayList<>( entry.getValue() );
            principals.sort( new PrincipalComparator() );
            aclText.append( "[{ALLOW " ).append( action ).append(' ');
            for( int i = 0; i < principals.size(); i++ ) {
                final Principal principal = principals.get( i );
                aclText.append( principal.getName() );
                if( i < ( principals.size() - 1 ) ) {
                    aclText.append(',');
                }
            }
            aclText.append( "}]\n" );
        }
        return aclText.toString();
    }

}
