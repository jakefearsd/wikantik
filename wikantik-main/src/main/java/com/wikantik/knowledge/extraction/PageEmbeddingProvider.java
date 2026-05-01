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
package com.wikantik.knowledge.extraction;

import java.util.Optional;

/**
 * Functional seam that yields the page-mean dense embedding used by the
 * extractor pipeline to fetch a per-page dictionary of existing KG nodes.
 * Returns {@link Optional#empty()} when the page has no chunk embeddings or
 * when the lookup failed — callers treat absence as "no dictionary expansion"
 * rather than as an error.
 */
@FunctionalInterface
public interface PageEmbeddingProvider {
    Optional< float[] > meanFor( String pageName );

    /** Default no-op provider — every page yields no embedding. */
    PageEmbeddingProvider EMPTY = name -> Optional.empty();
}
