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
package com.wikantik.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WikiEngineEventTest {

    private static final Object SOURCE = new Object();

    static Stream<Arguments> engineEventTypes() {
        return Stream.of(
                Arguments.of( WikiEngineEvent.INITIALIZING, "INITIALIZING", "wiki engine initializing" ),
                Arguments.of( WikiEngineEvent.INITIALIZED, "INITIALIZED", "wiki engine initialized" ),
                Arguments.of( WikiEngineEvent.SHUTDOWN, "SHUTDOWN", "wiki engine shutting down" )
        );
    }

    @ParameterizedTest( name = "eventName({0}) = {1}" )
    @MethodSource( "engineEventTypes" )
    void testEventName( final int type, final String expectedName, final String expectedDesc ) {
        final WikiEngineEvent event = new WikiEngineEvent( SOURCE, type );
        assertEquals( expectedName, event.eventName() );
    }

    @ParameterizedTest( name = "getTypeDescription({0}) = {2}" )
    @MethodSource( "engineEventTypes" )
    void testGetTypeDescription( final int type, final String expectedName, final String expectedDesc ) {
        final WikiEngineEvent event = new WikiEngineEvent( SOURCE, type );
        assertEquals( expectedDesc, event.getTypeDescription() );
    }

    @Test
    void testIsValidType() {
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.INITIALIZING ) );
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.INITIALIZED ) );
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.SHUTDOWN ) );
        // Note: isValidType uses a range check in the parent class, so many ints are "valid"
    }

    @Test
    void testToString() {
        final WikiEngineEvent event = new WikiEngineEvent( SOURCE, WikiEngineEvent.INITIALIZED );
        assertNotNull( event.toString() );
    }
}
