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

import com.wikantik.api.config.GenAiMode;
import com.wikantik.util.PrefixedProperties;
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
 * @param fusion         sub-query fusion strategy: {@code "rrf"} (default) or {@code "roundrobin"}
 *                       (see {@link SubQueryFusion} — round-robin reserves per-sub-query position
 *                       so the minority side of a comparative query is not crowded out)
 */
public record BundleDecompositionConfig(
    boolean enabled,
    String model,
    String baseUrl,
    long timeoutMs,
    int maxSubqueries,
    double rrfK,
    String fusion
) {

    private static final Logger LOG = LogManager.getLogger( BundleDecompositionConfig.class );

    public static final String PREFIX = "wikantik.bundle.decomposition.";

    public static BundleDecompositionConfig fromProperties( final Properties props ) {
        final PrefixedProperties r = new PrefixedProperties( props, PREFIX );
        // wikantik.genai.mode ceiling: the decomposition planner issues chat
        // inference calls, so it must be forced disabled whenever the mode
        // disallows chat inference, regardless of the decomposition.enabled flag.
        final boolean rawEnabled = r.getBoolean( "enabled", false );
        final GenAiMode mode = props == null ? GenAiMode.FULL : GenAiMode.fromProperties( props );
        final boolean enabled = rawEnabled && mode.allowsChatInference();
        if ( rawEnabled && !enabled ) {
            LOG.warn( "{}={} disallows chat inference; forcing bundle query-decomposition disabled",
                GenAiMode.PROP, mode );
        }

        return new BundleDecompositionConfig(
            enabled,
            r.getString( "model", "gemma4-assist:latest" ),
            r.getString( "base_url", "http://inference.jakefear.com:11434" ),
            r.getLong( "timeout_ms", 4_000L ),
            r.getInt( "max_subqueries", 4 ),
            r.getDouble( "rrf_k", 60 ),
            r.getString( "fusion", "rrf" )
        );
    }
}
