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
package com.wikantik.comments;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.function.Function;

/**
 * Post-save filter that guarantees every saved page has a {@code page_owners}
 * row by calling {@link PageOwnerService#getOwner(String)} after each
 * successful save. {@code getOwner} is find-or-create, so this filter is
 * effectively a side-effecting "ensure exists" hook.
 *
 * <p>The slug → canonical_id resolution is supplied as a {@link Function}
 * seam so tests can drive the filter without standing up a structural
 * index. In production the seam is bound to
 * {@code StructuralIndexService::resolveCanonicalIdFromSlug}.</p>
 *
 * <p>Gated by {@link #PROP_ENFORCEMENT_ENABLED} (default {@code true}).
 * Failures are logged at {@code warn} and swallowed — ownership tracking
 * must never block a save.</p>
 */
public class PageOwnershipSaveFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( PageOwnershipSaveFilter.class );

    /** Master flag; default {@code true}. */
    public static final String PROP_ENFORCEMENT_ENABLED =
            "wikantik.page_ownership.enforcement.enabled";

    private final PageOwnerService pageOwners;
    private final Function< String, Optional< String > > slugToCanonicalId;
    private final boolean enabled;

    public PageOwnershipSaveFilter( final PageOwnerService pageOwners,
                                    final Function< String, Optional< String > > slugToCanonicalId,
                                    final boolean enabled ) {
        this.pageOwners = pageOwners;
        this.slugToCanonicalId = slugToCanonicalId;
        this.enabled = enabled;
        LOG.info( "PageOwnershipSaveFilter: enforcement {}",
                  enabled ? "enabled" : "disabled" );
    }

    @Override
    public void postSave( final Context context, final String content ) throws FilterException {
        if ( !enabled || pageOwners == null || slugToCanonicalId == null ) {
            return;
        }
        try {
            if ( context == null || context.getPage() == null ) {
                return;
            }
            final String slug = context.getPage().getName();
            if ( slug == null || slug.isBlank() ) {
                return;
            }
            final Optional< String > canonicalId = slugToCanonicalId.apply( slug );
            if ( canonicalId.isEmpty() ) {
                LOG.debug( "PageOwnershipSaveFilter: no canonical_id for slug '{}', skipping",
                           slug );
                return;
            }
            pageOwners.getOwner( canonicalId.get() );
        } catch ( final Exception e ) {
            LOG.warn( "PageOwnershipSaveFilter.postSave failed: {}", e.getMessage(), e );
            // Swallow: ownership tracking must never block a save.
        }
    }
}
