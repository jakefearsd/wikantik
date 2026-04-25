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
package com.wikantik.api.structure;

/**
 * An enriched {@link Relation} that includes the resolved slugs and titles for
 * source and target pages, plus the depth at which it was reached when
 * returned from a traversal. {@code targetSlug} and {@code targetTitle} may be
 * {@code null} when the target page no longer exists (dangling reference) —
 * callers should surface that as a broken-link warning.
 */
public record RelationEdge(
        String sourceId,
        String sourceSlug,
        String targetId,
        String targetSlug,
        String targetTitle,
        RelationType type,
        int depth
) {
    public RelationEdge {
        if ( sourceId == null || sourceId.isBlank() ) {
            throw new IllegalArgumentException( "sourceId required" );
        }
        if ( targetId == null || targetId.isBlank() ) {
            throw new IllegalArgumentException( "targetId required" );
        }
        if ( type == null ) {
            throw new IllegalArgumentException( "type required" );
        }
        if ( depth < 0 ) {
            throw new IllegalArgumentException( "depth must be >= 0" );
        }
    }
}
