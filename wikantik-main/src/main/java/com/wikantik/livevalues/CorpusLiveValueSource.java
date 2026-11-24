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
package com.wikantik.livevalues;

import com.wikantik.api.Release;
import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Live values derived from the structural index: page, article and cluster counts and the release version.
 *
 * <p>Only pages a guest may view are counted ({@code isPublic}), so a private cluster contributes nothing: it is
 * absent from every count and gets no {@code cluster.<path>.pages} entry, which makes it resolve exactly like a
 * cluster that does not exist. Only clusters declared by a public hub exist; "clusters"/"subclusters" count declared
 * clusters with at least one public non-hub member. Cluster membership is exact (via {@link ClusterPath#memberships}); a hub is not a
 * member of the cluster it declares.</p>
 */
public final class CorpusLiveValueSource implements LiveValueSource {

    private static final String CLUSTER_SUFFIX = ".pages";

    private static final Set< String > DECLARED = Set.of( "articles", "pages", "clusters", "subclusters", "version",
            LiveValueExpander.CLUSTER_PREFIX );

    private final StructuralIndexService index;
    private final SystemPageRegistry systemPages;
    private final Predicate< String > isPublic;

    public CorpusLiveValueSource( final StructuralIndexService index, final SystemPageRegistry systemPages,
                                  final Predicate< String > isPublic ) {
        this.index = index;
        this.systemPages = systemPages;
        this.isPublic = isPublic;
    }

    @Override
    public Map< String, Resolution > values() {
        requireFirstRebuild();
        long pages = 0;
        long articles = 0;
        final Map< String, Long > memberCounts = new HashMap<>();
        final Set< String > declared = new HashSet<>();
        for( final PageDescriptor d : index.sitemap().pages() ) {
            if( !isPublic.test( d.slug() ) ) {
                continue;
            }
            pages++;
            final boolean hub = d.type() == PageType.HUB;
            if( hub ) {
                declared.addAll( d.clusters() );
            } else {
                if( !systemPages.isSystemPage( d.slug() ) ) {
                    articles++;
                }
                for( final String c : d.clusters() ) {
                    memberCounts.merge( c, 1L, Long::sum );
                }
            }
        }
        final Map< String, Resolution > out = new HashMap<>();
        out.put( "pages", new Resolution.Value( pages ) );
        out.put( "articles", new Resolution.Value( articles ) );
        long clusters = 0;
        long subclusters = 0;
        for( final String c : declared ) {
            final long members = memberCounts.getOrDefault( c, 0L );
            out.put( LiveValueExpander.CLUSTER_PREFIX + c + CLUSTER_SUFFIX, new Resolution.Value( members ) );
            if( members > 0 ) {
                if( ClusterPath.parent( c ) == null ) {
                    clusters++;
                } else {
                    subclusters++;
                }
            }
        }
        out.put( "clusters", new Resolution.Value( clusters ) );
        out.put( "subclusters", new Resolution.Value( subclusters ) );
        out.put( "version", new Resolution.Text( Release.getVersionString() ) );
        return out;
    }

    /** The initial rebuild runs on a background thread: an empty projection must not be served as real zeros. */
    private void requireFirstRebuild() {
        final IndexHealth health = index.health();
        if( health != null && health.lastRebuildFinishedAt() == null ) {
            throw new NotReadyException( "structural index has not finished its first rebuild" );
        }
    }

    @Override
    public Set< String > declaredNames() {
        return DECLARED;
    }
}
