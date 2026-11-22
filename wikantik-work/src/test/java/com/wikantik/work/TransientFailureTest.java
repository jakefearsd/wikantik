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

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransientFailureTest {

    @Test
    void failuresThatGoAwayAreTransient() {
        assertTrue( TransientFailure.is( new WorkUnavailableException( "down" ) ) );
        assertTrue( TransientFailure.is( new SQLTransientConnectionException( "pool" ) ) );
        assertTrue( TransientFailure.is( new SQLException( "reset", "08006" ) ) );
        assertTrue( TransientFailure.is( new SQLException( "deadlock", "40P01" ) ) );
        assertTrue( TransientFailure.is( new SQLException( "cancelled", "57014" ) ) );
        assertTrue( TransientFailure.is( new IllegalStateException( "wrapped", new WorkUnavailableException( "down" ) ) ) );
    }

    @Test
    void failuresThatRepeatAreNot() {
        assertFalse( TransientFailure.is( new IllegalStateException( "bad data" ) ) );
        assertFalse( TransientFailure.is( new NullPointerException() ) );
        assertFalse( TransientFailure.is( new SQLException( "bad value", "22P02" ) ) );
        assertFalse( TransientFailure.is( new SQLException( "unique", "23505" ) ) );
    }

    @Test
    void failingToGetAConnectionIsTransientNotPoison() {
        assertTrue( TransientFailure.is( new SQLException( "Cannot get a connection, pool error Timeout waiting for idle object",
            new java.util.NoSuchElementException() ) ) );
        assertTrue( TransientFailure.is( new SQLException( "no state" ) ), "an SQLException with no SQLState is not proven deterministic" );
        assertTrue( TransientFailure.is( new IllegalStateException( "wrapped", new SQLException( "no state" ) ) ) );
        assertTrue( TransientFailure.is( new SQLException( "io", new java.io.IOException( "reset" ) ) ) );
        assertTrue( TransientFailure.is( new RuntimeException( new java.util.concurrent.TimeoutException( "slow" ) ) ) );
        assertTrue( TransientFailure.is( new SQLException( "server", "XX000" ) ), "only a whitelist of data errors is poison" );
    }
}
