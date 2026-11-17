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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploymentStateTest {

    private static final Set< String > ALLOWED = Set.of( "requested>approved", "approved>running",
        "running>verified", "running>failed", "verified>rolled_back", "failed>rolled_back" );

    @Test
    void transitionTable() {
        for ( final DeploymentState from : DeploymentState.values() ) {
            for ( final DeploymentState to : DeploymentState.values() ) {
                assertEquals( ALLOWED.contains( from.wire() + ">" + to.wire() ), from.mayBecome( to ),
                    from.wire() + " to " + to.wire() );
            }
        }
    }

    @Test
    void onlyApprovedAndRunningAreClearedToRun() {
        for ( final DeploymentState s : DeploymentState.values() ) {
            assertEquals( s == DeploymentState.APPROVED || s == DeploymentState.RUNNING, s.clearedToRun(), s.wire() );
        }
    }

    @Test
    void wireValuesRoundTrip() {
        for ( final DeploymentState s : DeploymentState.values() ) {
            assertEquals( s, DeploymentState.fromWire( s.wire() ) );
        }
        assertEquals( "rolled_back", DeploymentState.ROLLED_BACK.wire() );
    }
}
