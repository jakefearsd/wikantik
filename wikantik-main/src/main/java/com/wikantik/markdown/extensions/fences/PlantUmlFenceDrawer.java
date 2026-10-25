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
package com.wikantik.markdown.extensions.fences;

import com.wikantik.plantuml.PlantUmlSandbox;
import com.wikantik.plantuml.PlantUmlService;
import com.wikantik.plantuml.PlantUmlSettings;
import com.wikantik.plantuml.RenderOutcome;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Properties;

/**
 * The {@code plantuml} {@link ServerFenceDrawer}: adapts {@link PlantUmlService} (policy, outcome cache, bounded
 * daemon pool, timeouts, SVG scrub) and reads its settings from the {@code wikantik.fences.plantuml.*} properties at
 * engine startup. With the kill switch off every fence is refused as {@code plantuml-disabled} and PlantUML is never
 * touched; when the PlantUML sandbox cannot be confirmed or the service cannot be built, every fence is refused as a
 * transient {@code render-failed} (logged once, at startup) and page rendering carries on.
 */
public final class PlantUmlFenceDrawer implements ServerFenceDrawer {

    private static final Logger LOG = LogManager.getLogger( PlantUmlFenceDrawer.class );

    /** Kill switch. */
    public static final String PROP_ENABLED = "wikantik.fences.plantuml.enabled";
    private static final String DISABLED = "plantuml-disabled";

    /** What the drawer does with a source. */
    private enum Mode { DRAW, DISABLED, UNAVAILABLE }

    private final Mode mode;
    private final PlantUmlService service;
    private final long timeoutMs;

    private PlantUmlFenceDrawer( final Mode mode, final PlantUmlService service, final long timeoutMs ) {
        this.mode = mode;
        this.service = service;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Builds the drawer from the wiki properties; never throws.
     *
     * @param p the wiki properties
     * @return a drawing drawer, a {@code plantuml-disabled} one (kill switch off) or an unavailable one
     */
    public static PlantUmlFenceDrawer fromProperties( final Properties p ) {
        final PlantUmlSettings settings = settings( p );
        if ( !settings.enabled() ) {
            LOG.info( "PlantUML fences are disabled ({}=false); diagrams show their source", PROP_ENABLED );
            return new PlantUmlFenceDrawer( Mode.DISABLED, null, settings.timeoutMs() );
        }
        try {
            if ( !PlantUmlSandbox.ensure() ) {
                // LOG.error justified: startup-time loss of every PlantUML drawing until an operator fixes the deployment; logged once per engine.
                LOG.error( "The PlantUML sandbox could not be confirmed; every PlantUML fence is refused as render-failed" );
                return new PlantUmlFenceDrawer( Mode.UNAVAILABLE, null, settings.timeoutMs() );
            }
            return new PlantUmlFenceDrawer( Mode.DRAW, new PlantUmlService( settings ), settings.timeoutMs() );
        } catch ( final RuntimeException | LinkageError e ) {
            // LOG.error justified: startup-time loss of every PlantUML drawing until an operator fixes the deployment; logged once per engine.
            LOG.error( "PlantUML could not be initialised; every PlantUML fence is refused as render-failed: {}", e.toString(), e );
            return new PlantUmlFenceDrawer( Mode.UNAVAILABLE, null, settings.timeoutMs() );
        }
    }

    /**
     * Builds the page resolver for server fences from the wiki properties; never throws. When the build fails every
     * PlantUML fence is refused as {@code render-failed} (logged).
     *
     * @param p the wiki properties
     * @return the resolver, with the {@code plantuml} drawer and the per-page limits
     */
    public static ServerFenceResolver resolver( final Properties p ) {
        try {
            return new ServerFenceResolver( Map.of( "plantuml", fromProperties( p ) ), pageMaxDiagrams( p ), pageBudgetMs( p ) );
        } catch ( final RuntimeException | LinkageError e ) {
            // LOG.error justified: startup-time loss of every PlantUML drawing until an operator fixes the deployment; logged once per engine.
            LOG.error( "Server fence drawing could not be set up; PlantUML fences show their source: {}", e.toString(), e );
            return new ServerFenceResolver( Map.of( "plantuml", unavailable() ), 0, 0L );
        }
    }

    /** @return a drawer that refuses every fence as a transient {@code render-failed} */
    public static PlantUmlFenceDrawer unavailable() {
        return new PlantUmlFenceDrawer( Mode.UNAVAILABLE, null, PlantUmlSettings.defaults().timeoutMs() );
    }

    /** @return the render-service settings the properties describe (defaults for absent or malformed values) */
    static PlantUmlSettings settings( final Properties p ) {
        return new PlantUmlSettings(
                TextUtil.getBooleanProperty( p, PROP_ENABLED, true ),
                TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.threads", 2 ),
                TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.queueCapacity", 8 ),
                getLong( p, "wikantik.fences.plantuml.threadStackBytes", 16777216L ),
                getLong( p, "wikantik.fences.plantuml.timeoutMs", 5000L ),
                TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.maxSvgBytes", 2097152 ),
                TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.slowRetryMinutes", 10 ) * 60_000L,
                TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.cacheEntries", 500 ) );
    }

    /** @return the most diagrams one page response draws */
    public static int pageMaxDiagrams( final Properties p ) {
        return TextUtil.getIntegerProperty( p, "wikantik.fences.plantuml.pageMaxDiagrams", 20 );
    }

    /** @return the wall-clock budget one page response shares across its diagrams */
    public static long pageBudgetMs( final Properties p ) {
        return getLong( p, "wikantik.fences.plantuml.pageBudgetMs", 10000L );
    }

    private static long getLong( final Properties p, final String key, final long def ) {
        final String value = TextUtil.getStringProperty( p, key, null );
        if ( value == null || value.isBlank() ) {
            return def;
        }
        try {
            return Long.parseLong( value.trim() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Property {}='{}' is not a whole number; using the default {}", key, value, def );
            return def;
        }
    }

    @Override
    public String language() {
        return "plantuml";
    }

    @Override
    public long defaultBudgetMs() {
        return timeoutMs;
    }

    @Override
    public ServerDrawing draw( final String source, final long budgetMs ) {
        return switch ( mode ) {
            case DISABLED -> ServerDrawing.refused( DISABLED, "PlantUML rendering is disabled", 0, false );
            case UNAVAILABLE -> ServerDrawing.refused( "render-failed", "PlantUML is unavailable on this server", 0, true );
            case DRAW -> adapt( service.render( source, budgetMs ) );
        };
    }

    private static ServerDrawing adapt( final RenderOutcome o ) {
        if ( o.isDrawn() ) {
            return ServerDrawing.drawn( o.drawing().lightSvg(), o.drawing().darkSvg() );
        }
        return ServerDrawing.refused( o.reason(), o.message(), o.line(), o.transientFailure() );
    }
}
