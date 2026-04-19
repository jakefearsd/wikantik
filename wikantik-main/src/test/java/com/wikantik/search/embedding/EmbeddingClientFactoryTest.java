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

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientFactoryTest {

    @Test
    void disabledFlagReturnsEmpty() {
        final EmbeddingConfig c = new EmbeddingConfig(
            false, "ollama", "http://localhost:11434", null,
            EmbeddingModel.NOMIC_EMBED_V1_5, null, 30_000, 32 );
        final Optional< TextEmbeddingClient > client = EmbeddingClientFactory.create( c, HttpClient.newHttpClient() );
        assertFalse( client.isPresent() );
    }

    @Test
    void enabledFlagReturnsOllamaClient() {
        final EmbeddingConfig c = new EmbeddingConfig(
            true, "ollama", "http://localhost:11434", null,
            EmbeddingModel.BGE_M3, null, 30_000, 32 );
        final Optional< TextEmbeddingClient > client = EmbeddingClientFactory.create( c, HttpClient.newHttpClient() );
        assertTrue( client.isPresent() );
        assertInstanceOf( OllamaEmbeddingClient.class, client.get() );
        assertEquals( "bge-m3", client.get().modelName() );
        assertEquals( 1024, client.get().dimension() );
    }

    @Test
    void unsupportedBackendIsRejected() {
        final EmbeddingConfig c = new EmbeddingConfig(
            true, "tei", "http://localhost:8001", null,
            EmbeddingModel.BGE_M3, null, 30_000, 32 );
        assertThrows( IllegalArgumentException.class,
            () -> EmbeddingClientFactory.create( c, HttpClient.newHttpClient() ) );
    }
}
