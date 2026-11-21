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
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AuditReadPolicyTest {

    @Test
    void auditsWhenFrontmatterOptsIn() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> Map.of( "audit_reads", true ),   // frontmatter lookup
            page -> "some-cluster",                  // cluster lookup
            Set.of() );                              // no audited clusters
        assertTrue( p.shouldAudit( "PHIPage" ) );
    }

    @Test
    void auditsWhenClusterIsFlagged() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> Map.of(),
            page -> "regulated",
            Set.of( "regulated" ) );
        assertTrue( p.shouldAudit( "AnyPage" ) );
    }

    @Test
    void doesNotAuditByDefault() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> Map.of(),
            page -> "ordinary",
            Set.of( "regulated" ) );
        assertFalse( p.shouldAudit( "AnyPage" ) );
    }

    @Test
    void frontmatterStringTrueAlsoOptsIn() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> Map.of( "audit_reads", "true" ),
            page -> null, Set.of() );
        assertTrue( p.shouldAudit( "P" ) );
    }

    @Test
    void knownFrontmatterIsUsedWithoutLookingThePageUpAgain() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> { throw new AssertionError( "the page's frontmatter was already parsed by the caller" ); },
            page -> "ordinary", Set.of( "regulated" ) );
        assertTrue( p.shouldAudit( "P", Map.of( "audit_reads", true ) ) );
        assertFalse( p.shouldAudit( "P", Map.of() ) );
    }

    @Test
    void noKnownFrontmatterFallsBackToTheLookup() {
        AuditReadPolicy p = new AuditReadPolicy(
            page -> Map.of( "audit_reads", true ), page -> null, Set.of() );
        assertTrue( p.shouldAudit( "P", null ) );
    }
}
