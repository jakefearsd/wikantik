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
package com.wikantik.api.config;

import java.util.Locale;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Ceiling control for model-backed operations. Wikantik performs no chat inference (the last
 * chat-LLM paths were removed in 2026-11), so the only model use is embeddings:
 * <ul>
 *   <li>{@link #FULL} — embeddings allowed (default)</li>
 *   <li>{@link #NONE} — all model-backed operations disabled</li>
 * </ul>
 * The legacy value {@code embeddings-only} is still accepted and parses to {@link #FULL}.
 */
public enum GenAiMode {

    /** Embeddings (retrieval/search) enabled. */
    FULL,

    /** All model-backed operations disabled. */
    NONE;

    private static final Logger LOG = LogManager.getLogger( GenAiMode.class );

    /** Configuration property name: {@code wikantik.genai.mode}. */
    public static final String PROP = "wikantik.genai.mode";

    /**
     * Parses a {@code Properties} object for the {@link #PROP} key and returns
     * the corresponding {@link GenAiMode}. An entirely absent property returns
     * {@link #FULL} silently — that is the normal state of existing deployments.
     * A property that is set but blank or unrecognized also returns {@link #FULL}
     * (fail-open; never fail closed on a typo), but logs a warning naming the bad
     * value so an operator who wanted enforcement can see it.
     *
     * @param props configuration properties
     * @return the parsed mode, or {@link #FULL} if absent/blank/unrecognized
     */
    public static GenAiMode fromProperties( Properties props ) {
        String value = props.getProperty( PROP );

        if ( value == null ) {
            // Property absent: the normal state of existing deployments — stay silent
            return FULL;
        }

        if ( value.isBlank() ) {
            LOG.warn( "Property {} is set but blank; defaulting to FULL", PROP );
            return FULL;
        }

        String normalized = value.trim().toLowerCase( Locale.ROOT );

        switch ( normalized ) {
            case "full":
                return FULL;
            case "embeddings-only":
                LOG.info( "{}=embeddings-only is a legacy alias and is now equivalent to full", PROP );
                return FULL;
            case "none":
                return NONE;
            default:
                LOG.warn( "Unrecognized {} value '{}'; defaulting to FULL", PROP, value );
                return FULL;
        }
    }

    /**
     * Returns {@code true} if this mode allows embeddings (retrieval).
     *
     * @return true for {@link #FULL}, false for {@link #NONE}
     */
    public boolean allowsEmbeddings() {
        return this == FULL;
    }
}
