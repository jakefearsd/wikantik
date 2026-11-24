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

import com.wikantik.api.core.Engine;
import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.api.pagegraph.VerificationCounts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reader-facing view of the structural index: every {@link PageDescriptor} it returns carries its
 * {@code summary} with {@code {{wiki:name}}} tokens expanded. Descriptors without a token are returned
 * as the same instance, so the common case allocates nothing. Everything that is not a descriptor
 * (snapshot, health, conflicts, verification, id resolution, rebuild) delegates untouched.
 *
 * <p>Write paths, validators, drift checks and {@link DefaultLiveValues} itself must keep the raw
 * index: use {@link #unwrap} where only the decorated view is in hand.</p>
 *
 * <p>The {@link LiveValues} service is resolved on every call, because this view may be built before
 * the service is registered; {@code null} (absent or disabled) means pass-through.</p>
 */
public final class LiveValuesStructuralIndexService implements StructuralIndexService {

    private static final Logger LOG = LogManager.getLogger( LiveValuesStructuralIndexService.class );

    private final StructuralIndexService delegate;
    private final Supplier< LiveValues > liveValues;

    public LiveValuesStructuralIndexService( final StructuralIndexService delegate, final Supplier< LiveValues > liveValues ) {
        this.delegate = java.util.Objects.requireNonNull( delegate, "delegate" );
        this.liveValues = java.util.Objects.requireNonNull( liveValues, "liveValues" );
    }

    /** Wraps {@code raw} for reader surfaces, looking {@link LiveValues} up on {@code engine} per call; {@code null} stays {@code null}. */
    public static StructuralIndexService decorate( final StructuralIndexService raw, final Engine engine ) {
        if( raw == null || raw instanceof LiveValuesStructuralIndexService ) {
            return raw;
        }
        return new LiveValuesStructuralIndexService( raw, () -> LiveValuesLookup.of( engine ) );
    }

    /** The undecorated index behind {@code index}, for write paths and audits; {@code index} itself when not decorated. */
    public static StructuralIndexService unwrap( final StructuralIndexService index ) {
        return index instanceof LiveValuesStructuralIndexService d ? d.delegate : index;
    }

    private PageDescriptor expand( final PageDescriptor d ) {
        if( d == null || !LiveValueExpander.hasToken( d.summary() ) ) {
            return d;
        }
        final LiveValues lv = liveValues.get();
        if( lv == null ) {
            return d;
        }
        try {
            final String summary = lv.expandText( d.summary() );
            if( summary == null || summary.equals( d.summary() ) ) {
                return d;
            }
            return new PageDescriptor( d.canonicalId(), d.slug(), d.title(), d.type(), d.cluster(), d.clusters(),
                d.tags(), summary, d.updated(), d.derived(), d.inlineTags() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Live values: could not expand the summary of '{}'; serving it verbatim: {}", d.slug(), e.getMessage(), e );
            return d;
        }
    }

    private List< PageDescriptor > expand( final List< PageDescriptor > in ) {
        List< PageDescriptor > out = null;
        for( int i = 0; i < in.size(); i++ ) {
            final PageDescriptor before = in.get( i );
            final PageDescriptor after = expand( before );
            if( after != before && out == null ) {
                out = new ArrayList<>( in );
            }
            if( out != null ) {
                out.set( i, after );
            }
        }
        return out == null ? in : out;
    }

    @Override
    public List< ClusterSummary > listClusters() {
        return delegate.listClusters().stream().map( c -> {
            final PageDescriptor hub = expand( c.hubPage() );
            return hub == c.hubPage() ? c : new ClusterSummary( c.name(), hub, c.articleCount(), c.updatedAt() );
        } ).toList();
    }

    @Override
    public Optional< ClusterDetails > getCluster( final String name ) {
        return delegate.getCluster( name ).map( c -> {
            final PageDescriptor hub = expand( c.hubPage() );
            final List< PageDescriptor > articles = expand( c.articles() );
            return hub == c.hubPage() && articles == c.articles()
                ? c : new ClusterDetails( c.name(), hub, articles, c.tagDistribution(), c.updatedAt() );
        } );
    }

    @Override
    public List< TagSummary > listTags( final int minPages ) {
        return delegate.listTags( minPages );
    }

    @Override
    public List< TagSummary > listTags( final int minPages, final Predicate< PageDescriptor > visible ) {
        return delegate.listTags( minPages, visible );
    }

    @Override
    public List< PageDescriptor > listPagesByType( final PageType type ) {
        return expand( delegate.listPagesByType( type ) );
    }

    @Override
    public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
        return expand( delegate.listPagesByFilter( filter ) );
    }

    @Override
    public Sitemap sitemap() {
        final Sitemap sm = delegate.sitemap();
        final List< PageDescriptor > pages = expand( sm.pages() );
        return pages == sm.pages() ? sm : new Sitemap( pages, sm.count(), sm.generatedAt() );
    }

    @Override
    public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
        return delegate.getByCanonicalId( canonicalId ).map( this::expand );
    }

    @Override
    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return delegate.resolveSlugFromCanonicalId( canonicalId );
    }

    @Override
    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return delegate.resolveCanonicalIdFromSlug( slug );
    }

    @Override
    public void rebuild() {
        delegate.rebuild();
    }

    @Override
    public IndexHealth health() {
        return delegate.health();
    }

    @Override
    public List< StructuralConflict > conflicts() {
        return delegate.conflicts();
    }

    @Override
    public Optional< Verification > verificationOf( final String canonicalId ) {
        return delegate.verificationOf( canonicalId );
    }

    @Override
    public VerificationCounts verificationCounts() {
        return delegate.verificationCounts();
    }

    @Override
    public Optional< PageTitleLookup > titleLookup() {
        return delegate.titleLookup();
    }

    @Override
    public StructuralProjectionSnapshot snapshot() {
        return delegate.snapshot();
    }
}
