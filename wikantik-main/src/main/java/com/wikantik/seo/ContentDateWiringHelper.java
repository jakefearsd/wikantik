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

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Builds the content-date subsystem and registers {@link ContentDateService} on the engine. Skipped (service
 * absent, callers fall back to lastModified) when there is no datasource. Calls {@code setManager} only,
 * never {@code getManager}, per the decomposition convention.
 */
public final class ContentDateWiringHelper {

    private static final Logger LOG = LogManager.getLogger( ContentDateWiringHelper.class );

    private ContentDateWiringHelper() {}

    /** Strong reference holder: WikiEventManager keeps listeners weakly. */
    public record Wired( ContentDateService service, ContentDateEventListener listener ) {}

    /** @return the wired holder, or null when skipped */
    public static Wired wireContentDates( final WikiEngine engine, final Properties props, final DataSource dataSource,
                                          final PageManager pageManager, final FilterManager filterManager,
                                          final PageRenamer pageRenamer ) {
        if( dataSource == null ) {
            LOG.info( "content date tracking skipped (no datasource)" );
            return null;
        }
        final ContentChangeRule rule = new ContentChangeRule(
            intProp( props, "wikantik.seo.contentChange.minPercent", 5 ),
            intProp( props, "wikantik.seo.contentChange.minWords", 100 ) );
        final ContentDateService service =
            new ContentDateService( pageManager, new ContentDateRepository( dataSource ), rule );
        engine.setManager( ContentDateService.class, service );
        final ContentDateEventListener listener = new ContentDateEventListener( service );
        listener.register( pageManager, filterManager, pageRenamer );
        LOG.info( "content date tracking wired (minPercent={}, minWords={})", rule.minPercent(), rule.minWords() );
        return new Wired( service, listener );
    }

    /**
     * The engine's content-date service, for consumers outside the wiring classes.
     *
     * @return the service, or null when none is wired (no datasource configured) — callers fall back to
     *         {@code Page#getLastModified()}
     */
    public static ContentDateService serviceOf( final com.wikantik.api.core.Engine engine ) {
        return engine instanceof WikiEngine we ? we.getContentDateService() : null;
    }

    private static int intProp( final Properties props, final String key, final int dflt ) {
        final String raw = props.getProperty( key );
        if( raw == null || raw.isBlank() ) {
            return dflt;
        }
        try {
            return Integer.parseInt( raw.trim() );
        } catch( final NumberFormatException e ) {
            LOG.warn( "{}='{}' is not an integer; using {}", key, raw, dflt );
            return dflt;
        }
    }
}
