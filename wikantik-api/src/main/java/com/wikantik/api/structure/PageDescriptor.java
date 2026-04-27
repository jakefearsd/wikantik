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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight descriptor of a wiki page for structural queries — enough to render
 * a compact listing without the full page body. All fields are non-null except
 * {@code cluster}, {@code summary}, and {@code updated}; prefer empty collections
 * over null for list fields.
 *
 * <p>{@code kgInclude} carries the page-level {@code kg_include} frontmatter
 * override ({@code true}/{@code false}). {@link Optional#empty()} means the
 * frontmatter did not specify a value and the inclusion decision falls back to
 * the cluster / global policy.</p>
 */
public record PageDescriptor(
        String canonicalId,
        String slug,
        String title,
        PageType type,
        String cluster,
        List< String > tags,
        String summary,
        Instant updated,
        Optional< Boolean > kgInclude
) {
    public PageDescriptor {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId required" );
        }
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        if ( title == null ) {
            title = slug;
        }
        if ( type == null ) {
            type = PageType.UNKNOWN;
        }
        tags = tags == null ? List.of() : List.copyOf( tags );
        kgInclude = kgInclude == null ? Optional.empty() : kgInclude;
    }
}
