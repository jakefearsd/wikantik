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
import java.util.UUID;

/**
 * One mention of a KG node in a content chunk — the curator-facing projection
 * of {@code chunk_entity_mentions} joined to {@code kg_content_chunks}. Used by
 * the admin Edge Explorer to render the surrounding passage so a curator can
 * disambiguate the node (especially acronyms / initialisms) without leaving
 * the page.
 *
 * <p>{@code headingPath} is the section breadcrumb on the host page;
 * {@code confidence} is the extractor's mention-level confidence and
 * {@code extractor} names which extractor produced the mention.</p>
 */
public record NodeMention(
    UUID chunkId,
    String pageName,
    int chunkIndex,
    List< String > headingPath,
    String text,
    double confidence,
    String extractor
) {
    public NodeMention {
        if ( chunkId == null ) {
            throw new IllegalArgumentException( "chunkId must not be null" );
        }
        if ( pageName == null ) {
            throw new IllegalArgumentException( "pageName must not be null" );
        }
        if ( text == null ) {
            throw new IllegalArgumentException( "text must not be null" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}
