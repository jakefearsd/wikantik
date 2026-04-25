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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.RecentChange;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Wraps {@link PageManager#getVersionHistory(String)} and projects the most
 * recent N entries into {@link RecentChange}. Tolerates a null or throwing
 * provider — both produce an empty list (the for-agent projection then
 * surfaces this on its {@code missingFields}).
 */
public final class RecentChangesAdapter {

    private static final Logger LOG = LogManager.getLogger( RecentChangesAdapter.class );

    private final PageManager pageManager;

    public RecentChangesAdapter( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    public List< RecentChange > recentChanges( final String pageName, final int limit ) {
        if ( pageName == null || pageName.isBlank() ) {
            return List.of();
        }
        final List< ? extends Page > history;
        try {
            history = pageManager.getVersionHistory( pageName );
        } catch ( final Exception e ) {
            LOG.warn( "getVersionHistory({}) threw — returning empty recent_changes: {}",
                    pageName, e.getMessage() );
            return List.of();
        }
        if ( history == null || history.isEmpty() ) {
            return List.of();
        }
        return history.stream()
                .sorted( Comparator.comparingInt( Page::getVersion ).reversed() )
                .limit( Math.max( 1, limit ) )
                .map( RecentChangesAdapter::toRecentChange )
                .toList();
    }

    private static RecentChange toRecentChange( final Page p ) {
        final Date d = p.getLastModified();
        final Instant at = d == null ? null : d.toInstant();
        final Object note = p.getAttribute( Page.CHANGENOTE );
        return new RecentChange(
                p.getVersion(),
                at,
                p.getAuthor(),
                note == null ? null : note.toString() );
    }
}
