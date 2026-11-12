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
package com.wikantik.seo;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains each page's content-changed date, which moves only on substantial body edits (see
 * {@link ContentChangeRule}). The stored baseline stays put across small edits, so they accumulate until
 * together they cross the threshold. Maintenance failures are logged and never propagate to the save.
 */
public class ContentDateService {

    private static final Logger LOG = LogManager.getLogger( ContentDateService.class );

    private final PageManager pageManager;
    private final ContentDateRepository repository;
    private final ContentChangeRule rule;

    ContentDateService( final PageManager pageManager, final ContentDateRepository repository,
                        final ContentChangeRule rule ) {
        this.pageManager = pageManager;
        this.repository = repository;
        this.rule = rule;
    }

    /** Called after a save. */
    public void onPageSaved( final String pageName ) {
        try {
            final Optional< ContentDate > row = repository.find( pageName );
            if( row.isEmpty() ) {
                recompute( pageName );
                return;
            }
            final Page page = pageManager.getPage( pageName );
            if( page == null || page.getVersion() == row.get().baselineVersion() ) {
                return;
            }
            final String baseline = pageManager.getPureText( pageName, row.get().baselineVersion() );
            final String current = pageManager.getPureText( pageName, WikiProvider.LATEST_VERSION );
            if( baseline == null || baseline.isBlank() || rule.isSubstantial( baseline, current ) ) {
                repository.upsert( new ContentDate( pageName, modified( page ), page.getVersion() ) );
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "content date update failed for page '{}': {}", pageName, e.getMessage(), e );
        }
    }

    /**
     * Rebuilds the date from the full history, oldest to newest.
     *
     * @return the stored date, or null when the page cannot be found
     */
    public ContentDate recompute( final String pageName ) {
        final List< Page > history = pageManager.getVersionHistory( pageName );
        ContentDate result = null;
        String baselineText = null;
        if( history != null && !history.isEmpty() ) {
            for( final Page v : history.stream().sorted( Comparator.comparingInt( Page::getVersion ) ).toList() ) {
                final String text = pageManager.getPureText( pageName, v.getVersion() );
                if( result == null
                    || baselineText == null || baselineText.isBlank() || rule.isSubstantial( baselineText, text ) ) {
                    result = new ContentDate( pageName, modified( v ), v.getVersion() );
                    baselineText = text;
                }
            }
        } else {
            final Page page = pageManager.getPage( pageName );
            if( page == null ) {
                return null;
            }
            result = new ContentDate( pageName, modified( page ), page.getVersion() );
        }
        repository.upsert( result );
        return result;
    }

    public void onPageDeleted( final String pageName ) {
        try {
            repository.delete( pageName );
        } catch( final RuntimeException e ) {
            LOG.warn( "content date delete failed for page '{}': {}", pageName, e.getMessage(), e );
        }
    }

    public void onPageRenamed( final String from, final String to ) {
        try {
            repository.rename( from, to );
        } catch( final RuntimeException e ) {
            LOG.warn( "content date rename failed for '{}' -> '{}': {}", from, to, e.getMessage(), e );
        }
    }

    /** Stored date, else the page's lastModified, else null. */
    public Instant contentDate( final Page page ) {
        try {
            final Optional< ContentDate > row = repository.find( page.getName() );
            if( row.isPresent() ) {
                return row.get().contentChangedAt();
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "content date lookup failed for page '{}', using lastModified: {}", page.getName(), e.getMessage(), e );
        }
        return page.getLastModified() == null ? null : page.getLastModified().toInstant();
    }

    /** @return every stored date by page name; empty when the store is unavailable */
    public Map< String, Instant > allContentDates() {
        try {
            return repository.findAll();
        } catch( final RuntimeException e ) {
            LOG.warn( "content date listing failed, callers fall back to lastModified: {}", e.getMessage(), e );
            return Map.of();
        }
    }

    private static Instant modified( final Page page ) {
        return page.getLastModified() == null ? Instant.now() : page.getLastModified().toInstant();
    }
}
