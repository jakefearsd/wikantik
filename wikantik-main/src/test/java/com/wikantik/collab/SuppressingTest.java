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
package com.wikantik.collab;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuppressingTest {

    @Test
    void aFailingCleanupIsAttachedToTheOriginalNotThrown() {
        final StackOverflowError original = new StackOverflowError( "filter" );
        final OutOfMemoryError second = new OutOfMemoryError( "abandon" );

        assertDoesNotThrow( () -> Suppressing.run( original, () -> { throw second; } ) );

        assertArrayEquals( new Throwable[] { second }, original.getSuppressed() );
    }

    @Test
    void aCleanCleanupRunsAndAddsNothing() {
        final StackOverflowError original = new StackOverflowError( "filter" );
        final AtomicBoolean ran = new AtomicBoolean();

        Suppressing.run( original, () -> ran.set( true ) );

        assertTrue( ran.get() );
        assertEquals( 0, original.getSuppressed().length );
    }
}
