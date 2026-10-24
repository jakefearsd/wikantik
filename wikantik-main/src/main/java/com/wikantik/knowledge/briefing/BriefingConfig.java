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
package com.wikantik.knowledge.briefing;

import com.wikantik.util.TextUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Property helpers for the session-start context briefing feature. Keys are namespaced
 * under {@link #PREFIX}. Integer parsing mirrors {@code BundleServiceWiring.sectionsPerPageFrom}:
 * blank/invalid/non-positive values fall back to the default with a {@code WARN} log.
 */
public final class BriefingConfig {

    private static final Logger LOG = LogManager.getLogger( BriefingConfig.class );

    private BriefingConfig() {}

    public static final String PREFIX = "wikantik.briefing.";
    public static final int DEFAULT_BUDGET = 6000;
    public static final int MAX_BUDGET = 24000;

    /** Read-amplification caps on caller input — a briefing surface is public/anonymous, and the
     *  assembler reads a full page body per pin and per cluster member. Excess is truncated with a
     *  warning rather than fanning out to thousands of disk reads per request. */
    public static final int MAX_PINS = 25;
    public static final int MAX_CLUSTERS = 10;
    public static final int MAX_CLUSTER_MEMBERS = 200;

    /** When the remaining token budget drops below this floor, cluster members are emitted as
     *  pointers straight from their structural descriptor (title/summary already present) instead of
     *  reading their bodies — nothing more would fit anyway. */
    public static final int POINTER_ONLY_FLOOR_TOKENS = 50;

    /**
     * The tunables one {@link DefaultBriefingAssemblyService} runs with.
     *
     * @param defaultBudget   token budget when the request names none
     * @param maxBudget       ceiling a requested budget is clamped to
     * @param sectionsPerPage most task-relevant sections one page may contribute
     * @param candidatePool   sections requested from the bundle before partitioning and capping
     * @param citedEnabled    false drops the cited tier (pages outside the clusters that members link to)
     * @param citedMax        most cited pages per request
     * @param elsewhereMax    most out-of-scope pointers per request
     * @param pointersMax     most cluster and cited pointers ("available on request") per request; pins are never capped
     */
    public record BriefingLimits( int defaultBudget, int maxBudget, int sectionsPerPage, int candidatePool,
                                  boolean citedEnabled, int citedMax, int elsewhereMax, int pointersMax ) {

        /** The shipped defaults: 6000/24000 tokens, 4 sections per page from a pool of 36, 12 cited, 8 elsewhere, 30 pointers. */
        public static BriefingLimits defaults() {
            return new BriefingLimits( DEFAULT_BUDGET, MAX_BUDGET, 4, 36, true, 12, 8, 30 );
        }
    }

    /** {@code wikantik.briefing.enabled}, default {@code true}. */
    public static boolean enabled( final Properties props ) {
        return TextUtil.getBooleanProperty( props, PREFIX + "enabled", true );
    }

    /** {@code wikantik.briefing.default_budget}, default {@link #DEFAULT_BUDGET}. */
    public static int defaultBudget( final Properties props ) {
        return intProperty( props, PREFIX + "default_budget", DEFAULT_BUDGET );
    }

    /** {@code wikantik.briefing.max_budget}, default {@link #MAX_BUDGET}. */
    public static int maxBudget( final Properties props ) {
        return intProperty( props, PREFIX + "max_budget", MAX_BUDGET );
    }

    private static int intProperty( final Properties props, final String key, final int def ) {
        if ( props == null ) return def;
        final String raw = props.getProperty( key );
        if ( raw == null || raw.isBlank() ) return def;
        try {
            final int v = Integer.parseInt( raw.trim() );
            return v > 0 ? v : def;
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Invalid {} '{}'; using {}", key, raw, def );
            return def;
        }
    }
}
