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
package com.wikantik.collab.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The draft summary (docs/MultiEdit.MD section 6.2, {@code GET /draft}): a snapshot of the draft taken under the session
 * lock. {@code status} 200 carries the base page version, the head {@code version} and {@code metaSeq} (so a caller can
 * tell later text and field edits from those summarised), whether the draft differs from its
 * base, its contributors' public names (first-contribution order), the last edit time (null before any), and the base
 * and head bodies and meta for the caller to diff. A refusal ({@link #refused}) carries only {@code status},
 * {@code reason} and {@code detail} (403 {@code evicted} names why).
 */
public record DraftSummary( int status, String reason, String detail, int basePageVersion, int version, long metaSeq,
                            boolean unpublished, List< String > contributors, Instant lastEditAt, String baseBody,
                            String doc, Map< String, Object > baseMeta, Map< String, Object > meta ) {

    public DraftSummary {
        contributors = List.copyOf( Objects.requireNonNull( contributors, "contributors" ) );
        baseMeta = ModelChecks.copyMeta( baseMeta, "baseMeta" );
        meta = ModelChecks.copyMeta( meta, "meta" );
        if( status == 200 ) {
            ModelChecks.nonNegative( basePageVersion, "basePageVersion" );
            ModelChecks.nonNegative( version, "version" );
            if( metaSeq < 0 ) {
                throw new IllegalArgumentException( "metaSeq must not be negative: " + metaSeq );
            }
            Objects.requireNonNull( baseBody, "baseBody" );
            Objects.requireNonNull( doc, "doc" );
        }
    }

    /** A refusal: no draft content, only why. */
    public static DraftSummary refused( final int status, final String reason, final String detail ) {
        if( status == 200 ) {
            throw new IllegalArgumentException( "a refusal is not 200" );
        }
        return new DraftSummary( status, reason, detail, 0, 0, 0L, false, List.of(), null, null, null, Map.of(), Map.of() );
    }
}
