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
package com.wikantik.derived;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stamps {@code derived_orphaned: true} onto a derived page's frontmatter when its connector is
 * removed without a cascade delete (design D3, {@code ConnectorConfigService#delete}) — the page
 * is kept, but flagged as no longer tracked by any connector. The body is passed through
 * unchanged; only the metadata is touched.
 *
 * <p>Refuses (WARN, no write) for a page that is absent or is not itself a derived page
 * ({@link DerivedPage#isDerived}) — stamping is only meaningful for connector-created pages, and
 * must never silently create or repurpose a human-authored one.
 */
public final class DerivedPageOrphanStamper implements Consumer< String > {

    private static final Logger LOG = LogManager.getLogger( DerivedPageOrphanStamper.class );

    private final DerivedPageIngestionService.PageReader reader;
    private final Function< String, String > bodyReader;
    private final DerivedPageIngestionService.PageWriter writer;
    private final String author;

    public DerivedPageOrphanStamper( final DerivedPageIngestionService.PageReader reader,
            final Function< String, String > bodyReader, final DerivedPageIngestionService.PageWriter writer,
            final String author ) {
        this.reader = reader;
        this.bodyReader = bodyReader;
        this.writer = writer;
        this.author = author;
    }

    @Override
    public void accept( final String pageName ) {
        final Optional< Map< String, Object > > existing = reader.readMetadata( pageName );
        if ( existing.isEmpty() ) {
            LOG.warn( "orphan stamp skipped: page '{}' does not exist", pageName );
            return;
        }
        if ( !DerivedPage.isDerived( existing.get() ) ) {
            LOG.warn( "orphan stamp skipped: page '{}' is not a derived page", pageName );
            return;
        }
        final Map< String, Object > metadata = new HashMap<>( existing.get() );
        metadata.put( DerivedPage.DERIVED_ORPHANED, Boolean.TRUE );
        try {
            writer.write( pageName, bodyReader.apply( pageName ), metadata, author );
        } catch ( final Exception e ) {
            LOG.warn( "orphan stamp failed for page '{}': {}", pageName, e.getMessage() );
        }
    }
}
