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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkInputsAgentTest {

    private static String field( final Runnable r ) {
        return assertThrows( WorkValidationException.class, r::run ).field();
    }

    @Test
    void queueKeyPattern() {
        assertDoesNotThrow( () -> WorkInputs.requireQueueKey( "implementer" ) );
        assertEquals( "key", field( () -> WorkInputs.requireQueueKey( "Impl" ) ) );
        assertEquals( "key", field( () -> WorkInputs.requireQueueKey( "i" ) ) );
        assertEquals( "key", field( () -> WorkInputs.requireQueueKey( "a".repeat( 31 ) ) ) );
        assertDoesNotThrow( () -> WorkInputs.requireQueueKey( "a".repeat( 30 ) ) );
    }

    @Test
    void agentNamePattern() {
        assertDoesNotThrow( () -> WorkInputs.requireAgentName( "codex@docker2" ) );
        assertDoesNotThrow( () -> WorkInputs.requireAgentName( "claude-ws" ) );
        assertEquals( "name", field( () -> WorkInputs.requireAgentName( "Codex" ) ) );
        assertEquals( "name", field( () -> WorkInputs.requireAgentName( "a b" ) ) );
        assertEquals( "name", field( () -> WorkInputs.requireAgentName( "x@" ) ) );
    }

    @Test
    void agentLeadRoleRejected() {
        assertEquals( "role", field( () -> WorkInputs.requireAgentRole( WorkRole.LEAD ) ) );
        assertDoesNotThrow( () -> WorkInputs.requireAgentRole( WorkRole.MEMBER ) );
        assertDoesNotThrow( () -> WorkInputs.requireAgentRole( WorkRole.VIEWER ) );
    }

    @Test
    void inboxIsAReservedProjectKeyInAnyCase() {
        assertEquals( "key", field( () -> WorkInputs.requireUnreservedKey( "INBOX" ) ), "/work/inbox is the inbox" );
        assertEquals( "key", field( () -> WorkInputs.requireUnreservedKey( "inbox" ) ) );
    }

    @Test
    void agentsProjectKeyIsReserved() {
        assertEquals( "key", field( () -> WorkInputs.requireUnreservedKey( "AGENTS" ) ) );
        assertEquals( "key", field( () -> WorkInputs.requireUnreservedKey( "log" ) ), "/work/log is the assignment log" );
    }

    @Test
    void agentPrincipalKindAccepted() {
        assertDoesNotThrow( () -> WorkInputs.requireMember( "9", "agent", WorkRole.MEMBER ) );
    }

    @Test
    void sponsorAccessRemovedIsReserved() {
        org.junit.jupiter.api.Assertions.assertThrows( WorkValidationException.class,
            () -> WorkInputs.requireReleaseNote( "sponsor access removed" ),
            "a person's release note must never pass for the system's reason" );
    }
}
