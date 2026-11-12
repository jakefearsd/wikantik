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

import java.time.Instant;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.seo.ContentDateService;
import com.wikantik.seo.ContentDateWiringHelper;

/**
 * Per-request snapshot of stored content-changed dates for the sitemap, falling back to a page's file
 * modification time when no stored date exists (or the service is absent or failing).
 */
final class SitemapContentDates {

    private static final Logger LOG = LogManager.getLogger( SitemapContentDates.class );

    private final Map< String, Instant > stored;

    private SitemapContentDates( final Map< String, Instant > stored ) {
        this.stored = stored;
    }

    /** Fetches every stored content date once; empty (all fallbacks) when the service is absent or fails. */
    static SitemapContentDates load( final Engine engine ) {
        try {
            final ContentDateService service = ContentDateWiringHelper.serviceOf( engine );
            return new SitemapContentDates( service == null ? Map.of() : service.allContentDates() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Content dates unavailable for sitemap, using file modification times: {}", e.getMessage(), e );
            return new SitemapContentDates( Map.of() );
        }
    }

    /** The stored content-changed instant for the page, else its last-modified instant, else null. */
    Instant changedInstant( final Page page ) {
        final Instant date = stored.get( page.getName() );
        if ( date != null ) {
            return date;
        }
        return page.getLastModified() == null ? null : page.getLastModified().toInstant();
    }
}
