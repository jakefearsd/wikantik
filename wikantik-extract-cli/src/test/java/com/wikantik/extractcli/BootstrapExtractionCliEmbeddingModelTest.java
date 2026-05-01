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
package com.wikantik.extractcli;

import com.wikantik.search.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The CLI's {@code --node-embedding-model} flag accepts either an Ollama-style
 * tag (e.g. {@code qwen3-embedding:0.6b}, {@code bge-m3:latest}) or a bare
 * model code (e.g. {@code qwen3-embedding-0.6b}). The previous parser only
 * understood {@code :latest} suffixes and silently fell back to BGE_M3 metadata
 * for everything else — which would have applied bge-m3's empty query prefix
 * to a qwen3 embedder, silently tanking retrieval quality. These cases pin the
 * tag-or-code resolver to the right enum so the prefix and dimension match.
 */
class BootstrapExtractionCliEmbeddingModelTest {

    @Test
    void resolvesQwen3TagForm() {
        assertSame( EmbeddingModel.QWEN3_EMBEDDING_06B,
            BootstrapExtractionCli.resolveEmbeddingModel( "qwen3-embedding:0.6b" ) );
    }

    @Test
    void resolvesQwen3CodeForm() {
        assertSame( EmbeddingModel.QWEN3_EMBEDDING_06B,
            BootstrapExtractionCli.resolveEmbeddingModel( "qwen3-embedding-0.6b" ) );
    }

    @Test
    void resolvesBgeM3TagFormForBackwardCompatibility() {
        // The flag still honours bge-m3:latest so existing scripts and the
        // embedding-model registry tests don't regress.
        assertSame( EmbeddingModel.BGE_M3,
            BootstrapExtractionCli.resolveEmbeddingModel( "bge-m3:latest" ) );
    }

    @Test
    void resolvesBgeM3CodeForm() {
        assertSame( EmbeddingModel.BGE_M3,
            BootstrapExtractionCli.resolveEmbeddingModel( "bge-m3" ) );
    }

    @Test
    void unknownTagFallsBackToQwen3() {
        // Best-effort warm-up: degrade with a warning rather than abort the
        // whole extractor run.
        assertSame( EmbeddingModel.QWEN3_EMBEDDING_06B,
            BootstrapExtractionCli.resolveEmbeddingModel( "no-such-model:tag" ) );
    }

    @Test
    void cliDefaultPointsAtQwen3() {
        // Belt-and-braces: if someone retro-bumps the default in Args, this
        // test fires before they ship it.
        final BootstrapExtractionCli.Args a = new BootstrapExtractionCli.Args();
        assertEquals( "qwen3-embedding:0.6b", a.nodeEmbeddingModel );
    }
}
