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

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Ceiling control for LLM operations. Models allowed inference modes:
 * <ul>
 *   <li>{@link #FULL} — both embeddings and chat inference allowed (default)</li>
 *   <li>{@link #EMBEDDINGS_ONLY} — embeddings (retrieval) only, no chat inference</li>
 *   <li>{@link #NONE} — LLM operations entirely disabled</li>
 * </ul>
 */
public enum GenAiMode {

    /** Full LLM support: embeddings and chat inference both enabled. */
    FULL,

    /** Embeddings only: retrieval/search enabled, chat inference disabled. */
    EMBEDDINGS_ONLY,

    /** All LLM operations disabled. */
    NONE;

    private static final Logger LOG = LogManager.getLogger( GenAiMode.class );

    /** Configuration property name: {@code wikantik.genai.mode}. */
    public static final String PROP = "wikantik.genai.mode";

    /**
     * Parses a {@code Properties} object for the {@link #PROP} key and returns
     * the corresponding {@link GenAiMode}. If the property is absent, blank,
     * or unrecognized, logs a warning and returns {@link #FULL} (fail-open
     * behavior; an operator wanting enforcement can inspect the warning log).
     *
     * @param props configuration properties
     * @return the parsed mode, or {@link #FULL} if unrecognized/absent
     */
    public static GenAiMode fromProperties( Properties props ) {
        String value = props.getProperty( PROP );

        if ( value == null || value.isBlank() ) {
            // Property absent or blank; default to FULL
            return FULL;
        }

        String normalized = value.trim().toLowerCase();

        switch ( normalized ) {
            case "full":
                return FULL;
            case "embeddings-only":
                return EMBEDDINGS_ONLY;
            case "none":
                return NONE;
            default:
                LOG.warn( "Unrecognized {} value '{}'; defaulting to FULL", PROP, value );
                return FULL;
        }
    }

    /**
     * Returns {@code true} only if this mode allows chat inference.
     *
     * @return true for {@link #FULL}, false for others
     */
    public boolean allowsChatInference() {
        return this == FULL;
    }

    /**
     * Returns {@code true} if this mode allows embeddings (retrieval).
     *
     * @return true for {@link #FULL} and {@link #EMBEDDINGS_ONLY}, false for {@link #NONE}
     */
    public boolean allowsEmbeddings() {
        return this == FULL || this == EMBEDDINGS_ONLY;
    }
}
