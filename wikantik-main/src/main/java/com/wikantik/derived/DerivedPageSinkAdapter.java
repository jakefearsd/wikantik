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

import com.wikantik.api.connectors.DerivedPageSink;
import com.wikantik.api.connectors.IngestOutcome;
import com.wikantik.api.connectors.SourceItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.function.Function;

/** Bridges the connector {@link DerivedPageSink} port to the existing {@link DerivedPageIngestionService}. */
public final class DerivedPageSinkAdapter implements DerivedPageSink {

    private static final Logger LOG = LogManager.getLogger( DerivedPageSinkAdapter.class );
    private final DerivedPageIngestionService ingestion;
    private final DerivedPageIngestionService.PageDeleter deleter;
    private final String author;
    private final Function< String, ConnectorConfigService.ContentDefaults > defaultsFor;

    /** Backward-compatible 3-arg form: no per-connector content defaults ({@link ConnectorConfigService.ContentDefaults#EMPTY}). */
    public DerivedPageSinkAdapter( final DerivedPageIngestionService ingestion,
                                   final DerivedPageIngestionService.PageDeleter deleter,
                                   final String author ) {
        this( ingestion, deleter, author, id -> ConnectorConfigService.ContentDefaults.EMPTY );
    }

    public DerivedPageSinkAdapter( final DerivedPageIngestionService ingestion,
                                   final DerivedPageIngestionService.PageDeleter deleter,
                                   final String author,
                                   final Function< String, ConnectorConfigService.ContentDefaults > defaultsFor ) {
        this.ingestion = ingestion;
        this.deleter = deleter;
        this.author = author;
        this.defaultsFor = defaultsFor;
    }

    @Override
    public IngestOutcome ingest( final String connectorId, final SourceItem item ) {
        final var d = defaultsFor.apply( connectorId );
        final String prefix = d.pagePrefix() != null ? d.pagePrefix() : "";
        final String sourceUrl = item.sourceMetadata() != null
            ? Objects.toString( item.sourceMetadata().get( "source_url" ), null ) : null;
        final IngestResult r = ingestion.ingest(
            item.content(), prefix + flatName( item.sourceUri() ), item.contentType(),
            new IngestOptions( false, author, item.sourceUri(), connectorId, sourceUrl,
                d.cluster(), d.tags() ) );
        return new IngestOutcome( r.pageName(), map( r.status() ) );
    }

    @Override
    public void delete( final String pageName ) {
        try {
            deleter.delete( pageName );
        } catch ( final Exception e ) {
            LOG.warn( "Connector sink: delete of page '{}' failed: {}", pageName, e.getMessage() );
        }
    }

    /** Strip the {@code scheme:} prefix and flatten {@code /}→{@code -} so a tree yields unique page names. */
    static String flatName( final String sourceUri ) {
        final int colon = sourceUri.indexOf( ':' );
        final String path = colon >= 0 ? sourceUri.substring( colon + 1 ) : sourceUri;
        return path.replace( '/', '-' );
    }

    private static IngestOutcome.Status map( final IngestResult.Status s ) {
        return switch ( s ) {
            case CREATED   -> IngestOutcome.Status.CREATED;
            case UPDATED   -> IngestOutcome.Status.UPDATED;
            case UNCHANGED -> IngestOutcome.Status.UNCHANGED;
            case FAILED    -> IngestOutcome.Status.FAILED;
        };
    }
}
