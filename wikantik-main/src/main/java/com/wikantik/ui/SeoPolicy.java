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
package com.wikantik.ui;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.util.TextUtil;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Search-engine-facing visibility rules, configured by {@code wikantik.seo.noindexClusters} and
 * {@code wikantik.seo.gonePages}. This is not access control: noindexed pages stay readable by people, the REST API and
 * MCP; the rules only tell crawlers what to index and what is permanently gone.
 *
 * <p>Clusters are compared only through {@link ClusterPath}, never by string prefix.</p>
 */
public final class SeoPolicy {

    /** Comma-separated cluster paths whose pages (and sub-clusters) are noindexed. Blank: none. */
    public static final String PROP_NOINDEX_CLUSTERS = "wikantik.seo.noindexClusters";

    /** Comma-separated page slugs that answer 410 Gone when requested and absent. Blank: none. */
    public static final String PROP_GONE_PAGES = "wikantik.seo.gonePages";

    private static final SeoPolicy NONE = new SeoPolicy( Set.of(), Set.of() );

    private final Set< String > noindexClusters;
    private final Set< String > gonePages;

    SeoPolicy( final Set< String > noindexClusters, final Set< String > gonePages ) {
        this.noindexClusters = Set.copyOf( noindexClusters );
        this.gonePages = Set.copyOf( gonePages );
    }

    /** @return a policy that noindexes nothing and gones nothing */
    public static SeoPolicy none() {
        return NONE;
    }

    /**
     * @param p the wiki properties; may be null
     * @return the policy the two settings describe
     */
    public static SeoPolicy fromProperties( final Properties p ) {
        if ( p == null ) {
            return NONE;
        }
        return new SeoPolicy( commaList( TextUtil.getStringProperty( p, PROP_NOINDEX_CLUSTERS, "" ) ),
                commaList( TextUtil.getStringProperty( p, PROP_GONE_PAGES, "" ) ) );
    }

    private static Set< String > commaList( final String value ) {
        final Set< String > out = new LinkedHashSet<>();
        Arrays.stream( value.split( "," ) ).map( String::trim ).filter( s -> !s.isEmpty() ).forEach( out::add );
        return out;
    }

    /** @return whether any cluster is configured as noindex */
    public boolean hasNoindexClusters() {
        return !noindexClusters.isEmpty();
    }

    /**
     * True when the page's primary cluster is, or is a sub-cluster of, a noindexed cluster.
     *
     * @param clusterValue the raw frontmatter {@code cluster:} value (String, List or null)
     * @return whether the page is noindexed
     */
    public boolean isNoindex( final Object clusterValue ) {
        if ( noindexClusters.isEmpty() ) {
            return false;
        }
        final String primary = ClusterPath.primary( clusterValue );
        if ( primary == null ) {
            return false;
        }
        for ( final String listed : noindexClusters ) {
            if ( ClusterPath.isSelfOrDescendant( primary, listed ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param metadata the page's parsed frontmatter; may be null
     * @return whether the page is noindexed
     */
    public boolean isNoindexMetadata( final Map< String, Object > metadata ) {
        return metadata != null && isNoindex( metadata.get( "cluster" ) );
    }

    /**
     * @param slug a requested page slug
     * @return whether the slug is listed as deliberately removed
     */
    public boolean isGone( final String slug ) {
        return slug != null && gonePages.contains( slug );
    }

    /**
     * A short, order-insensitive token for the noindex cluster set, for folding into page validators (ETags) so a
     * copy cached under one policy is never revalidated under another. Empty when no cluster is noindexed, so
     * validators are unchanged for operators who do not use the setting.
     *
     * @return the fingerprint, or {@code ""} when no cluster is noindexed
     */
    public String noindexFingerprint() {
        if ( noindexClusters.isEmpty() ) {
            return "";
        }
        final String joined = String.join( ",", new TreeSet<>( noindexClusters ) );
        return "-n" + Integer.toHexString( joined.hashCode() );
    }

    /**
     * True when the page's primary cluster is configured as noindex. Never reads page text when no cluster is
     * noindexed, so the common (unconfigured) case costs nothing.
     *
     * @param pageManager the page manager used to read the page's frontmatter
     * @param page        the page to test
     * @return whether the page is noindexed
     */
    public boolean isNoindexed( final PageManager pageManager,
                                final Page page ) {
        if ( noindexClusters.isEmpty() ) {
            return false;
        }
        final String text = pageManager.getPureText( page );
        return text != null && !text.isEmpty()
            && isNoindexMetadata( FrontmatterParser.parse( text ).metadata() );
    }
}
