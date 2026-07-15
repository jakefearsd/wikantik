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

import com.wikantik.api.core.Engine;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.ingest.TikaSourceExtractor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Builds a {@link DerivedPageIngestionService} wired to the real wiki managers. Shared by the
 *  derived-ingest REST resource and the connector runtime so the page-seam wiring lives in one place. */
public final class DerivedIngestionServiceFactory {

    private static final Logger LOG = LogManager.getLogger( DerivedIngestionServiceFactory.class );

    private DerivedIngestionServiceFactory() {}

    public static DerivedPageIngestionService build( final Engine engine, final PageManager pm,
                                                     final AttachmentManager am ) {
        final PageSaveHelper saveHelper = new PageSaveHelper( engine, pm );

        final DerivedPageIngestionService.AttachmentStore attachmentStore = ( pageName, filename, bytes ) -> {
            final var att = Wiki.contents().attachment( engine, pageName, filename );
            am.storeAttachment( att, new ByteArrayInputStream( bytes ) );
        };
        final DerivedPageIngestionService.PageReader pageReader = pageReader( pm );
        final DerivedPageIngestionService.PageWriter pageWriter =
            pageWriter( saveHelper, "derived page — ingested from source document" );
        final DerivedPageIngestionService.PageDeleter pageDeleter = pm::deletePage;

        return new DerivedPageIngestionService( new TikaSourceExtractor(), attachmentStore, pageReader, pageWriter, pageDeleter );
    }

    /** Builds a {@link DerivedPageOrphanStamper} sharing the exact page-read/write mechanism as
     *  {@link #build} — used by {@code ConnectorWiringHelper} so that when an admin deletes a
     *  connector without cascading page deletion, the pages it synced get stamped
     *  {@code derived_orphaned: true} instead of silently going stale. */
    public static Consumer< String > orphanStamper( final Engine engine, final PageManager pm ) {
        final PageSaveHelper saveHelper = new PageSaveHelper( engine, pm );
        final DerivedPageIngestionService.PageWriter pageWriter =
            pageWriter( saveHelper, "connector removed — page orphaned from its source" );
        return new DerivedPageOrphanStamper( pageReader( pm ), bodyReader( pm ), pageWriter, "connector-sync" );
    }

    private static DerivedPageIngestionService.PageReader pageReader( final PageManager pm ) {
        return pageName -> {
            try {
                final String text = pm.getPureText( pageName, WikiProvider.LATEST_VERSION );
                if ( text == null || text.isBlank() ) return Optional.empty();
                return Optional.of( FrontmatterParser.parse( text ).metadata() );
            } catch ( final Exception e ) {
                LOG.warn( "DerivedIngestionServiceFactory: could not read page '{}': {}", pageName, e.getMessage() );
                return Optional.empty();
            }
        };
    }

    /** Reads a page's body with its frontmatter block stripped off — used only by
     *  {@link #orphanStamper}, which rewrites metadata on an existing page without re-extracting
     *  its content (the body must pass through untouched). */
    private static Function< String, String > bodyReader( final PageManager pm ) {
        return pageName -> {
            try {
                final String text = pm.getPureText( pageName, WikiProvider.LATEST_VERSION );
                return text == null ? "" : FrontmatterParser.parse( text ).body();
            } catch ( final Exception e ) {
                LOG.warn( "DerivedIngestionServiceFactory: could not read body of page '{}': {}", pageName, e.getMessage() );
                return "";
            }
        };
    }

    private static DerivedPageIngestionService.PageWriter pageWriter( final PageSaveHelper saveHelper,
            final String changeNote ) {
        return ( pageName, body, metadata, author ) -> {
            final SaveOptions opts = SaveOptions.builder()
                .metadata( metadata ).author( author ).replaceMetadata( true )
                .changeNote( changeNote ).build();
            saveHelper.saveText( pageName, body, opts );
        };
    }
}
