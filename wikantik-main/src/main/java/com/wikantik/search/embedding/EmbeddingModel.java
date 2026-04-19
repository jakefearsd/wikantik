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
package com.wikantik.search.embedding;

import java.util.Locale;

/**
 * The candidate embedding models the wiki can experiment with for hybrid
 * retrieval. Each constant carries the model-specific metadata that must match
 * the serving backend exactly — per-kind text prefix, output dimension, and the
 * default Ollama model tag. Prefixes are hardcoded because wiring them through
 * config would make a silent-recall-tanking misconfiguration trivial.
 */
public enum EmbeddingModel {

    NOMIC_EMBED_V1_5(
        "nomic-embed-v1.5",
        "nomic-embed-text:v1.5",
        768,
        "search_query: ",
        "search_document: "
    ),

    BGE_M3(
        "bge-m3",
        "bge-m3:latest",
        1024,
        "",
        ""
    ),

    /**
     * Qwen3 uses a task-instruction prefix on queries only; documents are
     * passed through unchanged. The instruction follows the Qwen3-Embedding
     * model card's retrieval template.
     */
    QWEN3_EMBEDDING_06B(
        "qwen3-embedding-0.6b",
        "qwen3-embedding:0.6b",
        1024,
        "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
        ""
    );

    private final String code;
    private final String defaultOllamaTag;
    private final int dimension;
    private final String queryPrefix;
    private final String documentPrefix;

    EmbeddingModel( final String code,
                    final String defaultOllamaTag,
                    final int dimension,
                    final String queryPrefix,
                    final String documentPrefix ) {
        this.code = code;
        this.defaultOllamaTag = defaultOllamaTag;
        this.dimension = dimension;
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
    }

    /** Stable short identifier used in configuration. */
    public String code() {
        return code;
    }

    /** Default Ollama model tag — overridable per deployment via config. */
    public String defaultOllamaTag() {
        return defaultOllamaTag;
    }

    /** The model's native output dimension before any Matryoshka truncation. */
    public int dimension() {
        return dimension;
    }

    /**
     * Returns the text to prepend to a single input string before sending it
     * to the embedding backend, given the input's role.
     */
    public String prefix( final EmbeddingKind kind ) {
        return switch( kind ) {
            case QUERY -> queryPrefix;
            case DOCUMENT -> documentPrefix;
        };
    }

    /**
     * Resolves a code string (as written in config) back to the enum value.
     * Matching is case-insensitive on the canonical {@link #code()}.
     *
     * @throws IllegalArgumentException if no model matches
     */
    public static EmbeddingModel fromCode( final String code ) {
        if( code == null ) {
            throw new IllegalArgumentException( "embedding model code must not be null" );
        }
        final String normalized = code.trim().toLowerCase( Locale.ROOT );
        for( final EmbeddingModel m : values() ) {
            if( m.code.equals( normalized ) ) {
                return m;
            }
        }
        throw new IllegalArgumentException( "unknown embedding model code: " + code );
    }
}
