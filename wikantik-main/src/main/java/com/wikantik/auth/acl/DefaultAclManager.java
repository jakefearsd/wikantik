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
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
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
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.util.comparators.PrincipalComparator;

import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.regex.Matcher;
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
     */
    public static final Pattern ACL_PATTERN = Pattern.compile( ACL_REGEX );

    /**
     * Vanilla CommonMark parser used solely to locate Markdown code spans and
     * code blocks when scanning for ACL directives. A bare parse (no rendering,
     * no custom extensions) is far cheaper than a full page render and is all we
     * need to tell a real {@code [{ALLOW …}]} rule apart from one shown as an
     * example inside backticks or a fenced block. Flexmark {@link Parser}
     * instances are immutable and safe to reuse across threads.
     */
    private static final Parser CODE_REGION_PARSER = Parser.builder().build();

    /** Parsed-ACL cache surviving Page-instance churn. Keyed by page name; entry valid only for a matching (version, lastModified). */
    private record CachedAcl( int version, long lastModified, Acl acl ) {}

    private final Cache< String, CachedAcl > aclCache =
        Caffeine.newBuilder().maximumSize( 20_000 ).build();

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) {
        auth = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        this.engine = engine;
    }

    /** {@inheritDoc} */
    @Override
    public Acl parseAcl( final Page page, final String ruleLine ) throws WikiSecurityException {
        Acl acl = page.getAcl();
        if (acl == null) {
            acl = Wiki.acls().acl();
        }

        try {
            final StringTokenizer fieldToks = new StringTokenizer(ruleLine);
            fieldToks.nextToken();
            final String actions = fieldToks.nextToken();

            while( fieldToks.hasMoreTokens() ) {
                final String principalName = fieldToks.nextToken(",").trim();
                final Principal principal = auth.resolvePrincipal(principalName);
                final AclEntry oldEntry = acl.getAclEntry(principal);

                if( oldEntry != null ) {
                    LOG.debug( "Adding to old acl list: {}, {}", principal, actions );
                    oldEntry.addPermission( PermissionFactory.getPagePermission( page, actions ) );
                } else {
                    LOG.debug( "Adding new acl entry for {}", actions );
                    final AclEntry entry = Wiki.acls().entry();
                    entry.setPrincipal( principal );
                    entry.addPermission( PermissionFactory.getPagePermission( page, actions ) );

                    acl.addEntry( entry );
                }
            }

            page.setAcl( acl );
            LOG.debug( acl.toString() );
        } catch( final NoSuchElementException nsee ) {
            LOG.warn( "Invalid access rule: {} - defaults will be used.", ruleLine );
            throw new WikiSecurityException( "Invalid access rule: " + ruleLine, nsee );
        } catch( final IllegalArgumentException iae ) {
            throw new WikiSecurityException("Invalid permission type: " + ruleLine, iae);
        }

        return acl;
    }


    /** {@inheritDoc} */
    @Override
    public Acl getPermissions( final Page page ) {
        //  Does the page already have cached ACLs?
        Acl acl = page.getAcl();
        LOG.debug( "page={}\n{}", page.getName(), acl );
        if( acl != null ) {
            return acl;
        }

        //  If null, try the parent.
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

        //  Extract ACLs directly from page text using regex - much faster than full page render
        acl = extractAclFromPageText( page );
        page.setAcl( acl );
        aclCache.put( page.getName(), new CachedAcl( page.getVersion(), lastModified, acl ) );
        return acl;
    }

    /**
     * Extracts ACL rules directly from page text using regex pattern matching.
     * This is a lightweight alternative to full page rendering for ACL extraction.
     *
     * @param page the page to extract ACLs from
     * @return the parsed Acl, or an empty Acl if no rules found or page text unavailable
     */
    private Acl extractAclFromPageText( final Page page ) {
        Acl acl = Wiki.acls().acl();

        try {
            final String pageText = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPureText( page );
            if( pageText == null || pageText.isEmpty() ) {
                return acl;
            }

            for( final String ruleLine : aclDirectives( pageText ) ) {
                try {
                    acl = parseAcl( page, ruleLine );
                } catch( final WikiSecurityException e ) {
                    LOG.warn( "Invalid ACL rule in page {}: {}", page.getName(), ruleLine );
                }
            }
        } catch( final Exception e ) {
            LOG.error( "Error extracting ACL from page {}: {}", page.getName(), e.getMessage() );
        }

        return acl;
    }

    /**
     * Returns the <em>enforceable</em> ACL directives in {@code pageText}: every
     * {@code [{ALLOW …}]} match that is NOT inside a Markdown code span or code
     * block. The identical syntax shown inside backticks or a fenced/indented
     * block is documentation (e.g. a page that explains how ACLs work) and is
     * skipped, so such a page no longer accidentally restricts itself.
     *
     * <p>The scanner fails closed toward enforcement: a directive in ordinary
     * page text is always returned, so a genuine restriction is never silently
     * dropped. Order of appearance is preserved.
     *
     * @param pageText the raw page source (may be {@code null})
     * @return the matched directive strings outside code, in document order
     */
    static List< String > aclDirectives( final String pageText ) {
        if( pageText == null || pageText.isEmpty() ) {
            return List.of();
        }
        final List< int[] > codeRanges = codeRegions( pageText );
        final List< String > directives = new ArrayList<>();
        final Matcher matcher = ACL_PATTERN.matcher( pageText );
        while( matcher.find() ) {
            if( !isInsideCode( codeRanges, matcher.start() ) ) {
                directives.add( matcher.group() );
            }
        }
        return directives;
    }

    /**
     * Computes the source-offset ranges {@code [start, end)} of every inline
     * code span and code block in {@code text}, by parsing it as CommonMark and
     * collecting {@link Code}, {@link FencedCodeBlock} and
     * {@link IndentedCodeBlock} node spans. Unhandled node types are descended
     * into automatically by {@link NodeVisitor}, so code nested anywhere in the
     * document is captured.
     */
    private static List< int[] > codeRegions( final String text ) {
        final List< int[] > ranges = new ArrayList<>();
        final Document doc = CODE_REGION_PARSER.parse( text );
        final NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>( Code.class,              n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( FencedCodeBlock.class,   n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( IndentedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) )
        );
        visitor.visit( doc );
        return ranges;
    }

    /** True if {@code offset} falls within any of the {@code [start, end)} code ranges. */
    private static boolean isInsideCode( final List< int[] > ranges, final int offset ) {
        for( final int[] range : ranges ) {
            if( offset >= range[ 0 ] && offset < range[ 1 ] ) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void setPermissions( final Page page, final Acl acl ) throws WikiSecurityException {
        final PageManager pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();

        // Forcibly expire any page locks
        final PageLock lock = pageManager.getCurrentLock( page );
        if( lock != null ) {
            pageManager.unlockPage( lock );
        }

        // Remove all of the existing ACLs.
        final String pageText = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPureText( page );
        final Matcher matcher = DefaultAclManager.ACL_PATTERN.matcher( pageText );
        final String cleansedText = matcher.replaceAll("" );
        final String newText = DefaultAclManager.printAcl( page.getAcl() ) + cleansedText;
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
        final Map< String, List< Principal > > permissionPrincipals = new TreeMap<>();
        final Enumeration< AclEntry > entries = acl.aclEntries();
        while( entries.hasMoreElements() ) {
            final AclEntry entry = entries.nextElement();
            final Principal principal = entry.getPrincipal();
            final Enumeration< Permission > permissions = entry.permissions();
            while( permissions.hasMoreElements() ) {
                final Permission permission = permissions.nextElement();
                List< Principal > principals = permissionPrincipals.get( permission.getActions() );
                if (principals == null) {
                    principals = new ArrayList<>();
                    final String action = permission.getActions();
                    if( action.indexOf(',') != -1 ) {
                        throw new IllegalStateException("AclEntry permission cannot have multiple targets.");
                    }
                    permissionPrincipals.put( action, principals );
                }
                principals.add( principal );
            }
        }

        // Now, iterate through each permission in the map and generate an ACL string
        final StringBuilder aclText = new StringBuilder();
        for( final Map.Entry< String, List< Principal > > entry : permissionPrincipals.entrySet() ) {
            final String action = entry.getKey();
            final List< Principal > principals = entry.getValue();
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
