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
 * A finding from the rebuild pass that an admin should see and resolve. Phase 4
 * surfaces these via {@code /api/admin/structural-conflicts}; Phase 1 produced
 * them silently via the {@code unclaimed_canonical_ids} health gauge.
 *
 * @param slug         current slug of the affected page (always set)
 * @param canonicalId  authored canonical_id when present; {@code null} when missing
 * @param kind         what's wrong — see {@link Kind}
 * @param detail       human-readable explanation (for logs and admin UIs)
 */
public record StructuralConflict(
        String slug,
        String canonicalId,
        Kind kind,
        String detail
) {
    public enum Kind {
        /** Page lacks a {@code canonical_id} in frontmatter and was indexed under a synthesised ID. */
        MISSING_CANONICAL_ID,
        /** Page declares a relation that the validator rejected. */
        RELATION_ISSUE
    }

    public StructuralConflict {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug required" );
        }
        if ( kind == null ) {
            throw new IllegalArgumentException( "kind required" );
        }
        detail = detail == null ? "" : detail;
    }
}
