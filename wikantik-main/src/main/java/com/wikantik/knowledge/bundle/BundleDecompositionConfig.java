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
package com.wikantik.knowledge.bundle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Configuration for the (default-off) LLM query-decomposition planner used by
 * the RAG context bundle. All knobs live under {@link #PREFIX}; defaults keep
 * a fresh deploy a no-op ({@code enabled=false}, {@link PassthroughQueryPlanner}
 * wired) until an operator opts in.
 *
 * @param enabled        master switch; when {@code false} the passthrough planner is wired
 * @param model          Ollama tag used for decomposition (e.g. {@code "gemma4-assist:latest"})
 * @param baseUrl        base URL for Ollama (e.g. {@code "http://inference.jakefear.com:11434"})
 * @param timeoutMs      per-call decomposition timeout
 * @param maxSubqueries  cap on the number of sub-queries returned
 * @param rrfK           reciprocal-rank-fusion constant used when merging sub-query result sets
 */
public record BundleDecompositionConfig(
    boolean enabled,
    String model,
    String baseUrl,
    long timeoutMs,
    int maxSubqueries,
    double rrfK
) {

    private static final Logger LOG = LogManager.getLogger( BundleDecompositionConfig.class );

    public static final String PREFIX = "wikantik.bundle.decomposition.";

    public static BundleDecompositionConfig fromProperties( final Properties props ) {
        return new BundleDecompositionConfig(
            getBoolean( props, "enabled", false ),
            getString( props, "model", "gemma4-assist:latest" ),
            getString( props, "base_url", "http://inference.jakefear.com:11434" ),
            getLong( props, "timeout_ms", 4_000L ),
            getInt( props, "max_subqueries", 4 ),
            getDouble( props, "rrf_k", 60 )
        );
    }

    private static String getString( final Properties p, final String suffix, final String def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static long getLong( final Properties p, final String suffix, final long def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Long.parseLong( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, PREFIX, suffix, def, e.getMessage() );
            return def;
        }
    }

    private static int getInt( final Properties p, final String suffix, final int def ) {
        return (int) getLong( p, suffix, def );
    }

    private static double getDouble( final Properties p, final String suffix, final double def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Double.parseDouble( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, PREFIX, suffix, def, e.getMessage() );
            return def;
        }
    }

    private static boolean getBoolean( final Properties p, final String suffix, final boolean def ) {
        final String v = p == null ? null : p.getProperty( PREFIX + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        final String trimmed = v.trim();
        if( "true".equalsIgnoreCase( trimmed ) || "1".equals( trimmed ) || "yes".equalsIgnoreCase( trimmed ) ) {
            return true;
        }
        if( "false".equalsIgnoreCase( trimmed ) || "0".equals( trimmed ) || "no".equalsIgnoreCase( trimmed ) ) {
            return false;
        }
        LOG.info( "Ignoring non-boolean value '{}' for property {}{} — using default {}",
            v, PREFIX, suffix, def );
        return def;
    }
}
