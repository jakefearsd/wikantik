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
 * Output of {@link ContextRetrievalService#listPages(PageListFilter)}. The
 * {@code pages} list never contains chunks or relatedPages — this is browse
 * output, not RAG output. {@code totalMatched} is the full match count,
 * useful for UI pagination; {@code limit} and {@code offset} echo the input.
 */
public record PageList(
    List< RetrievedPage > pages,
    int totalMatched,
    int limit,
    int offset
) {
    public PageList {
        pages = pages == null ? List.of() : List.copyOf( pages );
        if ( totalMatched < 0 ) {
            throw new IllegalArgumentException( "totalMatched must not be negative" );
        }
    }
}
