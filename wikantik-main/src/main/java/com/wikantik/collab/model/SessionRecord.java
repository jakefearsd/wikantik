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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable state of one page's collaboration session. {@code status} is {@code "active"} or {@code "frozen"}. */
public record SessionRecord( UUID id, String pageName, String status, int basePageVersion, String baseBody,
                             Map< String, Object > baseMeta, int headVersion, long metaSeq, boolean hasUnpublished,
                             Integer pendingPublishVersion, Integer pendingPublishBaseVersion, String createdBy,
                             Instant createdAt, Instant lastEditAt ) {

    public static final String ACTIVE = "active";
    public static final String FROZEN = "frozen";

    public SessionRecord {
        Objects.requireNonNull( id, "id" );
        ModelChecks.nonBlank( pageName, "pageName" );
        Objects.requireNonNull( status, "status" );
        if( !ACTIVE.equals( status ) && !FROZEN.equals( status ) ) {
            throw new IllegalArgumentException( "status must be active or frozen: " + status );
        }
        ModelChecks.nonNegative( basePageVersion, "basePageVersion" );
        Objects.requireNonNull( baseBody, "baseBody" );
        baseMeta = ModelChecks.copyMeta( baseMeta, "baseMeta" );
        ModelChecks.nonNegative( headVersion, "headVersion" );
        ModelChecks.nonNegative( metaSeq, "metaSeq" );
        if( ( pendingPublishVersion == null ) != ( pendingPublishBaseVersion == null ) ) {
            throw new IllegalArgumentException( "pendingPublishVersion and pendingPublishBaseVersion must be both null or both set" );
        }
        if( pendingPublishVersion != null ) {
            ModelChecks.nonNegative( pendingPublishVersion, "pendingPublishVersion" );
            ModelChecks.nonNegative( pendingPublishBaseVersion, "pendingPublishBaseVersion" );
        }
        ModelChecks.nonBlank( createdBy, "createdBy" );
        Objects.requireNonNull( createdAt, "createdAt" );
        Objects.requireNonNull( lastEditAt, "lastEditAt" );
    }

    public SessionRecord withHead( final int newHeadVersion ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, newHeadVersion, metaSeq,
            hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion, createdBy, createdAt, lastEditAt );
    }

    public SessionRecord withPending( final Integer version, final Integer baseVersion ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, headVersion, metaSeq,
            hasUnpublished, version, baseVersion, createdBy, createdAt, lastEditAt );
    }

    public SessionRecord withBase( final int version, final String body, final Map< String, Object > meta ) {
        return new SessionRecord( id, pageName, status, version, body, meta, headVersion, metaSeq,
            hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion, createdBy, createdAt, lastEditAt );
    }
}
