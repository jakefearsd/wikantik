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
