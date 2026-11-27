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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small pure rules whose every branch decides who may do what: autonomy order, attribution, deployment end, flags. */
class PolicyValueRulesTest {

    // ---- Autonomy.strictest

    @Test
    void strictestWithOnlyTheSecondUnsetYieldsTheFirst() {
        assertEquals( Autonomy.AUTONOMOUS, Autonomy.strictest( Autonomy.AUTONOMOUS, null ) );
        assertNull( Autonomy.strictest( null, null ) );
    }

    @ParameterizedTest
    @EnumSource( Autonomy.class )
    void strictestIsTheLowerLevelWhicheverSideItIsOn( final Autonomy a ) {
        for ( final Autonomy b : Autonomy.values() ) {
            final Autonomy expected = a.ordinal() <= b.ordinal() ? a : b;
            assertEquals( expected, Autonomy.strictest( a, b ), a + " vs " + b );
            assertEquals( expected, Autonomy.strictest( b, a ), b + " vs " + a );
        }
        assertSame( a, Autonomy.strictest( a, a ) );
    }

    private static void assertSame( final Object expected, final Object actual ) {
        org.junit.jupiter.api.Assertions.assertSame( expected, actual );
    }

    // ---- ActorRules

    @Test
    void aSystemEventMayNotCarryAnAgentId() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> ActorRules.requireEventAttribution( Actor.Kind.SYSTEM, 7L ) );
        assertEquals( "a system event carries no work_agent id", e.getMessage() );
    }

    @Test
    void aSystemEventWithoutAnAgentIdIsAllowed() {
        ActorRules.requireEventAttribution( Actor.Kind.SYSTEM, null );
    }

    @Test
    void anAgentEventNeedsItsIdAndAPersonsEventMustNotHaveOne() {
        assertEquals( "an agent write must carry its work_agent id", assertThrows( IllegalArgumentException.class,
            () -> ActorRules.requireEventAttribution( Actor.Kind.AGENT, null ) ).getMessage() );
        assertEquals( "a person's write carries no work_agent id", assertThrows( IllegalArgumentException.class,
            () -> ActorRules.requireEventAttribution( Actor.Kind.HUMAN, 3L ) ).getMessage() );
        ActorRules.requireEventAttribution( Actor.Kind.AGENT, 3L );
        ActorRules.requireEventAttribution( Actor.Kind.HUMAN, null );
    }

    @Test
    void onlyTheCodeHostActorMayWriteCodeHostChanges() {
        ActorRules.requireSystem( Actor.system( "github" ) );
        assertThrows( IllegalArgumentException.class, () -> ActorRules.requireSystem( Actor.agent( 1, "bot" ) ) );
        assertThrows( IllegalArgumentException.class,
            () -> ActorRules.requireSystem( Actor.human( "alice", java.util.Set.of(), true ) ) );
    }

    // ---- DeploymentState.ended

    @ParameterizedTest
    @EnumSource( DeploymentState.class )
    void aDeploymentHasEndedExactlyWhenNothingIsLeftToDo( final DeploymentState s ) {
        final boolean ended = s == DeploymentState.VERIFIED || s == DeploymentState.FAILED
            || s == DeploymentState.REJECTED || s == DeploymentState.ROLLED_BACK;
        assertEquals( ended, s.ended(), s.wire() );
    }

    // ---- Change.flagWaiting

    private static Change flagged( final String flag, final Instant ackedAt, final Long supersededBy ) {
        return new Change( 1, 1, 1, "b", "main", "aaa", null, ChangeState.MERGED, null, null, "alice", null, null,
            null, null, null, null, null, Instant.EPOCH, null, null, null, "codehost", null, null, null, null,
            flag, ackedAt == null ? null : "lena", ackedAt, supersededBy );
    }

    @Test
    void aFlagWaitsForALeadOnlyWhileUnacknowledgedAndNotSuperseded() {
        assertTrue( flagged( "unreviewed", null, null ).flagWaiting() );
        assertFalse( flagged( null, null, null ).flagWaiting(), "no flag, nothing waits" );
        assertFalse( flagged( "direct", Instant.EPOCH, null ).flagWaiting(), "acknowledged" );
        assertFalse( flagged( "direct", null, 9L ).flagWaiting(), "superseded rows never wait" );
        assertFalse( flagged( "direct", Instant.EPOCH, 9L ).flagWaiting() );
    }
}
