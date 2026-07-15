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
package com.wikantik.derived;

import java.util.List;

/**
 * Options controlling a single {@link DerivedPageIngestionService#ingest} call.
 *
 * @param force       when {@code true} re-ingest even if the source SHA is unchanged.
 * @param author      wiki login name recorded as the page author on save.
 * @param derivedFrom explicit {@code derived_from} provenance; {@code null} means use the filename
 *                    (backward-compatible default). Connectors set this to the source URI so
 *                    provenance is decoupled from the (basename-derived) page name.
 * @param connectorId id of the connector that produced this ingest, if any; stamped verbatim into
 *                    {@link DerivedPage#DERIVED_CONNECTOR}. {@code null} for manual ingests (e.g.
 *                    {@code POST /api/ingest}).
 * @param sourceUrl   a human-followable URL for the source item, if any; stamped into
 *                    {@link DerivedPage#DERIVED_SOURCE_URL}.
 * @param cluster     per-connector content default for the {@code cluster} frontmatter field —
 *                    applied only at page creation, never overwriting later curation (design D10).
 * @param tags        per-connector content default for the {@code tags} frontmatter field — same
 *                    create-only rule as {@code cluster}.
 */
public record IngestOptions( boolean force, String author, String derivedFrom, String connectorId,
        String sourceUrl, String cluster, List< String > tags ) {
    /** Backward-compatible 2-arg form: {@code derivedFrom = null} (provenance falls back to the filename). */
    public IngestOptions( final boolean force, final String author ) {
        this( force, author, null );
    }

    /** Backward-compatible 3-arg form: no connector provenance or content defaults. */
    public IngestOptions( final boolean force, final String author, final String derivedFrom ) {
        this( force, author, derivedFrom, null, null, null, null );
    }
}
