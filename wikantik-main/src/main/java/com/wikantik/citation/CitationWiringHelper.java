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
package com.wikantik.citation;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.filters.FilterManager;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.Properties;

/**
 * Constructs the citation subsystem and registers it on the engine.
 *
 * <p>Gated by {@code wikantik.citations.enabled} (default {@code true}) and
 * by non-null {@code dataSource} + {@code structuralIndex}. The event listener
 * is registered against both PageManager and FilterManager so that its strong
 * reference lives here on the engine (WikiEventManager holds listeners as WEAK
 * references). Named {@code *WiringHelper} per the decomposition convention —
 * calls {@code setManager} only, never {@code getManager}.</p>
 */
public final class CitationWiringHelper {

    private static final Logger LOG = LogManager.getLogger( CitationWiringHelper.class );

    private CitationWiringHelper() {}

    /**
     * Holds the objects that must be retained by the caller to prevent garbage
     * collection (WikiEventManager holds listeners via WEAK references).
     */
    public record Wired(
        CitationRepository     repository,
        CitationSync           sync,
        CitationEventListener  listener
    ) {}

    /**
     * Builds the citation subsystem and registers its components on the engine.
     *
     * @param engine          the wiki engine (setManager-only access)
     * @param props           engine properties
     * @param dataSource      JDBC data source; if null the subsystem is skipped
     * @param pageManager     used for body + version loading
     * @param filterManager   used for event listener registration (post-save events)
     * @param structuralIndex used for canonical-id resolution; if null the subsystem is skipped
     * @param coordinator     ontology rebuild coordinator; may be null (subsystem still wires)
     * @return a {@link Wired} holder with strong references, or {@code null} when disabled/skipped
     */
    public static Wired wireCitations( final WikiEngine engine,
                                       final Properties props,
                                       final DataSource dataSource,
                                       final PageManager pageManager,
                                       final FilterManager filterManager,
                                       final StructuralIndexService structuralIndex,
                                       final OntologyRebuildCoordinator coordinator ) {
        final boolean enabled = Boolean.parseBoolean(
                props.getProperty( "wikantik.citations.enabled", "true" ) );
        if ( !enabled ) {
            LOG.info( "citation subsystem disabled (wikantik.citations.enabled=false)" );
            return null;
        }
        if ( dataSource == null || structuralIndex == null ) {
            LOG.info( "citation subsystem skipped (dataSource={}, structuralIndex={})",
                    dataSource != null ? "present" : "null",
                    structuralIndex != null ? "present" : "null" );
            return null;
        }

        final java.util.function.Function< String, Optional< String > > bodyLoader = slug -> {
            try {
                final String text = pageManager.getPureText( slug, WikiProvider.LATEST_VERSION );
                return text == null ? Optional.empty() : Optional.of( text );
            } catch ( final RuntimeException e ) {
                LOG.warn( "citation bodyLoader failed for slug='{}': {}", slug, e.getMessage(), e );
                return Optional.empty();
            }
        };

        final java.util.function.Function< String, Optional< Integer > > versionLoader = slug -> {
            try {
                final com.wikantik.api.core.Page page = pageManager.getPage( slug );
                return page == null ? Optional.empty() : Optional.of( page.getVersion() );
            } catch ( final RuntimeException e ) {
                LOG.warn( "citation versionLoader failed for slug='{}': {}", slug, e.getMessage(), e );
                return Optional.empty();
            }
        };

        final CitationRepository     repository = new CitationRepository( dataSource );
        final CitationMarkupParser   parser     = new CitationMarkupParser();
        final CitationStalenessGrader grader    = new CitationStalenessGrader(
                structuralIndex, bodyLoader, new MarkdownSectionExtractor() );
        final CitationSync sync = new CitationSync(
                repository, parser, grader, structuralIndex, bodyLoader, versionLoader );

        engine.setManager( CitationRepository.class, repository );
        engine.setManager( CitationSync.class, sync );

        final CitationEventListener listener = new CitationEventListener( sync );
        listener.register( pageManager, filterManager );

        if ( coordinator != null ) {
            coordinator.onRebuildComplete( () -> {
                try {
                    sync.reconcileAll();
                } catch ( final RuntimeException e ) {
                    LOG.warn( "post-rebuild citation reconcileAll failed: {}", e.getMessage(), e );
                }
            } );
        }

        LOG.info( "citation subsystem wired (reconcile-on-rebuild={})", coordinator != null );
        return new Wired( repository, sync, listener );
    }
}
