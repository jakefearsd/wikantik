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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * One chunk of wiki content surfaced as context for a page in a retrieval
 * result. {@code headingPath} is the section breadcrumb (e.g. {@code
 * ["Retrieval Experiment Harness", "7. Model selection", "Decision rationale"]}).
 * {@code chunkScore} is the underlying similarity / BM25 score; its absolute
 * magnitude is retriever-dependent so callers should treat it as ordinal.
 * {@code matchedTerms} is a best-effort list of query terms that lit up on
 * this chunk — may be empty.
 */
public record RetrievedChunk(
    List< String > headingPath,
    String text,
    double chunkScore,
    List< String > matchedTerms
) {
    public RetrievedChunk {
        if ( headingPath == null ) {
            throw new IllegalArgumentException( "headingPath must not be null (use empty list)" );
        }
        if ( text == null ) {
            throw new IllegalArgumentException( "text must not be null" );
        }
        headingPath = List.copyOf( headingPath );
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf( matchedTerms );
    }
}
