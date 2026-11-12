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
package com.wikantik.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.providers.PageProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The private-cluster rule: a page whose {@code cluster:} memberships include a configured private cluster, or a
 * sub-cluster of one, is hidden from everyone but administrators and the configured viewer groups.
 *
 * <p>{@link DefaultAuthorizationManager} consults it inside the page-permission decision, ahead of the page's own
 * access rules, so every surface that asks for a page permission gets it and a page rule can only narrow it.</p>
 *
 * <p>Membership is read from the page's stored frontmatter (a frontmatter-only parse, never a body parse, never the
 * structural index, which is event-fed and may lag), so a new private page is private from its first save. The answer
 * is cached per page and is valid only for the (version, lastModified) it was read at, as
 * {@code DefaultAclManager}'s parsed-ACL cache is. Settings are read once, when the authorization manager starts.</p>
 */
final class PrivateClusterGate {

    private static final Logger LOG = LogManager.getLogger( PrivateClusterGate.class );

    /** Comma-separated cluster paths whose pages (and sub-clusters) are private. Blank: the feature is off. */
    static final String PROP_PRIVATE_CLUSTERS = "wikantik.access.privateClusters";

    /** Comma-separated group names that may use private pages, in addition to administrators. */
    static final String PROP_VIEWERS = "wikantik.access.privateClusters.viewers";

    /** A cached answer for one page; valid only while the page is at this (version, lastModified). */
    private record CachedMembership( int version, long lastModified, boolean isPrivate ) {}

    private final Set< String > privateClusters;
    private final Set< String > viewerGroups;
    private final PageManager pages;

    private final Cache< String, CachedMembership > cache = Caffeine.newBuilder().maximumSize( 20_000 ).build();

    /**
     * @param privateClusters the private cluster paths; empty turns the rule off
     * @param viewerGroups    the groups allowed in, besides administrators
     * @param pages           where the stored page text is read from
     */
    PrivateClusterGate( final Set< String > privateClusters, final Set< String > viewerGroups, final PageManager pages ) {
        this.privateClusters = Set.copyOf( privateClusters );
        this.viewerGroups = Set.copyOf( viewerGroups );
        this.pages = pages;
    }

    /**
     * The gate the two settings describe.
     *
     * @param p     the wiki properties
     * @param pages where the stored page text is read from
     * @return the gate; disabled when {@value #PROP_PRIVATE_CLUSTERS} is blank
     */
    static PrivateClusterGate fromProperties( final Properties p, final PageManager pages ) {
        return new PrivateClusterGate( commaList( p.getProperty( PROP_PRIVATE_CLUSTERS, "" ) ),
                commaList( p.getProperty( PROP_VIEWERS, "" ) ), pages );
    }

    private static Set< String > commaList( final String value ) {
        final Set< String > out = new LinkedHashSet<>();
        Arrays.stream( value.split( "," ) ).map( String::trim ).filter( s -> !s.isEmpty() ).forEach( out::add );
        return out;
    }

    /** @return whether any private cluster is configured */
    boolean enabled() {
        return !privateClusters.isEmpty();
    }

    /** @return the groups allowed to use private pages, besides administrators */
    Set< String > viewerGroups() {
        return viewerGroups;
    }

    /**
     * True when any cluster membership of the page is, or is a sub-cluster of, a private cluster. An attachment is
     * private when its parent page is. A page whose frontmatter cannot be read is not private (it keeps its own
     * access rules) and is logged.
     *
     * @param page the page, as loaded for the permission decision
     * @return whether the page is hidden by a private cluster
     */
    boolean isPrivate( final Page page ) {
        if ( !enabled() || page == null ) {
            return false;
        }
        if ( page instanceof Attachment att ) {
            final Page parent = pages.getPageWithoutMetadata( att.getParentName(), PageProvider.LATEST_VERSION );
            return parent != null && !( parent instanceof Attachment ) && isPrivate( parent );
        }
        final long lastModified = page.getLastModified() == null ? 0L : page.getLastModified().getTime();
        final CachedMembership hit = cache.getIfPresent( page.getName() );
        if ( hit != null && hit.version() == page.getVersion() && hit.lastModified() == lastModified ) {
            return hit.isPrivate();
        }

        final String text = pages.getPureText( page.getName(), page.getVersion() );
        if ( text == null || text.isEmpty() ) {
            // Not cached: getPureText answers "" for a provider failure it swallowed, and that must not stick.
            return false;
        }
        final boolean isPrivate = isPrivateText( page, text );
        cache.put( page.getName(), new CachedMembership( page.getVersion(), lastModified, isPrivate ) );
        return isPrivate;
    }

    private boolean isPrivateText( final Page page, final String text ) {
        final Map< String, Object > meta;
        try {
            meta = FrontmatterParser.parseStrict( text ).metadata();
        } catch ( final FrontmatterParseException e ) {
            // Cached with the answer, so this is logged once per page version.
            LOG.warn( "The frontmatter of page {} (version {}) cannot be read, so its cluster is unknown; it is not "
                    + "treated as private and keeps only its own access rules: {}", page.getName(), page.getVersion(),
                    e.getMessage() );
            return false;
        }
        final List< String > memberships = ClusterPath.memberships( meta.get( "cluster" ) );
        for ( final String membership : memberships ) {
            for ( final String privateCluster : privateClusters ) {
                if ( ClusterPath.isSelfOrDescendant( membership, privateCluster ) ) {
                    return true;
                }
            }
        }
        return false;
    }
}
