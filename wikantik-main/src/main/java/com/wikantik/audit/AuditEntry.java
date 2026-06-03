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
package com.wikantik.audit;

import java.time.Instant;
import java.util.Objects;

/** Immutable canonical audit record. The unit hashed, persisted, and queried. */
public final class AuditEntry {

    private final Instant eventTime;
    private final AuditCategory category;
    private final String eventType;
    private final String actorId;
    private final String actorPrincipal;
    private final String actorType;
    private final String targetType;
    private final String targetId;
    private final String targetLabel;
    private final AuditOutcome outcome;
    private final String sourceIp;
    private final String userAgent;
    private final String correlationId;
    private final String detail; // JSON string, metadata only

    private AuditEntry( Builder b ) {
        this.eventTime      = Objects.requireNonNull( b.eventTime, "eventTime" );
        this.category       = Objects.requireNonNull( b.category, "category" );
        this.eventType      = Objects.requireNonNull( b.eventType, "eventType" );
        this.actorType      = Objects.requireNonNull( b.actorType, "actorType" );
        this.outcome        = Objects.requireNonNull( b.outcome, "outcome" );
        this.actorId        = b.actorId;
        this.actorPrincipal = b.actorPrincipal;
        this.targetType     = b.targetType;
        this.targetId       = b.targetId;
        this.targetLabel    = b.targetLabel;
        this.sourceIp       = b.sourceIp;
        this.userAgent      = b.userAgent;
        this.correlationId  = b.correlationId;
        this.detail         = b.detail;
    }

    private static String nz( String s ) { return s == null ? "" : s; }

    /** Deterministic, versioned, fixed-order serialization used for hashing. */
    public String canonical() {
        return String.join( "|",
            "v1",
            eventTime.toString(),
            category.name(),
            eventType,
            nz( actorId ), nz( actorPrincipal ), actorType,
            nz( targetType ), nz( targetId ), nz( targetLabel ),
            outcome.name(),
            nz( sourceIp ), nz( userAgent ), nz( correlationId ),
            nz( detail ) );
    }

    public Instant eventTime()      { return eventTime; }
    public AuditCategory category() { return category; }
    public String eventType()       { return eventType; }
    public String actorId()         { return actorId; }
    public String actorPrincipal()  { return actorPrincipal; }
    public String actorType()       { return actorType; }
    public String targetType()      { return targetType; }
    public String targetId()        { return targetId; }
    public String targetLabel()     { return targetLabel; }
    public AuditOutcome outcome()   { return outcome; }
    public String sourceIp()        { return sourceIp; }
    public String userAgent()       { return userAgent; }
    public String correlationId()   { return correlationId; }
    public String detail()          { return detail; }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.eventTime = eventTime; b.category = category; b.eventType = eventType;
        b.actorId = actorId; b.actorPrincipal = actorPrincipal; b.actorType = actorType;
        b.targetType = targetType; b.targetId = targetId; b.targetLabel = targetLabel;
        b.outcome = outcome; b.sourceIp = sourceIp; b.userAgent = userAgent;
        b.correlationId = correlationId; b.detail = detail;
        return b;
    }

    public static final class Builder {
        private Instant eventTime;
        private AuditCategory category;
        private String eventType;
        private String actorId, actorPrincipal, actorType;
        private String targetType, targetId, targetLabel;
        private AuditOutcome outcome;
        private String sourceIp, userAgent, correlationId, detail;

        public Builder eventTime( Instant v )      { this.eventTime = v; return this; }
        public Builder category( AuditCategory v ) { this.category = v; return this; }
        public Builder eventType( String v )       { this.eventType = v; return this; }
        public Builder actorId( String v )         { this.actorId = v; return this; }
        public Builder actorPrincipal( String v )  { this.actorPrincipal = v; return this; }
        public Builder actorType( String v )       { this.actorType = v; return this; }
        public Builder targetType( String v )      { this.targetType = v; return this; }
        public Builder targetId( String v )        { this.targetId = v; return this; }
        public Builder targetLabel( String v )     { this.targetLabel = v; return this; }
        public Builder outcome( AuditOutcome v )   { this.outcome = v; return this; }
        public Builder sourceIp( String v )        { this.sourceIp = v; return this; }
        public Builder userAgent( String v )       { this.userAgent = v; return this; }
        public Builder correlationId( String v )   { this.correlationId = v; return this; }
        public Builder detail( String v )          { this.detail = v; return this; }

        public AuditEntry build() { return new AuditEntry( this ); }
    }
}
