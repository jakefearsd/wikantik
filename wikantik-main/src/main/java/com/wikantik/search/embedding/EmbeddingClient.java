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

/**
 * Narrow single-string embedding seam used by per-page extraction code paths
 * that don't need the batched {@link TextEmbeddingClient} surface. Keeping it
 * a separate functional interface lets tests pass a plain lambda or Mockito
 * mock without faking the full batched API.
 */
@FunctionalInterface
public interface EmbeddingClient {

    /**
     * Embed a single text. Implementations should throw a runtime exception
     * (typically {@link EmbeddingException}) on transport or model failure;
     * callers are expected to log and continue rather than abort.
     */
    float[] embed(String text);
}
