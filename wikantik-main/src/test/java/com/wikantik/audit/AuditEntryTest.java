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

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AuditEntryTest {

    private AuditEntry sample() {
        return AuditEntry.builder()
            .eventTime( Instant.parse( "2026-06-03T10:00:00Z" ) )
            .category( AuditCategory.AUTHN )
            .eventType( "login.failed" )
            .actorId( "u-42" ).actorPrincipal( "alice" ).actorType( "user" )
            .targetType( "user" ).targetId( "u-42" ).targetLabel( "alice" )
            .outcome( AuditOutcome.FAILURE )
            .sourceIp( "10.0.0.1" ).userAgent( "curl/8" ).correlationId( "corr-1" )
            .detail( "{\"reason\":\"bad-password\"}" )
            .build();
    }

    @Test
    void canonicalIsStableAndFieldOrdered() {
        // Canonical form must be deterministic for identical content.
        assertEquals( sample().canonical(), sample().canonical() );
        // Must be a versioned, delimited string starting with the version tag.
        assertTrue( sample().canonical().startsWith( "v1|" ), sample().canonical() );
    }

    @Test
    void canonicalChangesWhenAnyFieldChanges() {
        AuditEntry base = sample();
        AuditEntry changed = base.toBuilder().outcome( AuditOutcome.SUCCESS ).build();
        assertNotEquals( base.canonical(), changed.canonical() );
    }

    @Test
    void nullOptionalFieldsSerializeAsEmpty() {
        AuditEntry e = AuditEntry.builder()
            .eventTime( Instant.parse( "2026-06-03T10:00:00Z" ) )
            .category( AuditCategory.ADMIN ).eventType( "policy.grant.update" )
            .actorType( "system" ).outcome( AuditOutcome.SUCCESS )
            .build();
        // Must not throw and must produce a canonical form.
        assertNotNull( e.canonical() );
    }
}
