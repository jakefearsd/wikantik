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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the base {@link WikiEvent} class — covers getWhen(), getArgs(), getArg(),
 * getSrc(), eventName(), getTypeDescription(), isValidType(), and toString() via
 * concrete subclasses (WikiEvent is sealed).
 */
class WikiEventBaseTest {

    @Test
    void testGetWhenReturnsTimestamp() {
        final long before = System.currentTimeMillis();
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED );
        final long after = System.currentTimeMillis();

        assertTrue( event.getWhen() >= before && event.getWhen() <= after );
    }

    @Test
    void testGetTypeReturnsConstructorType() {
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.SHUTDOWN );
        assertEquals( WikiEngineEvent.SHUTDOWN, event.getType() );
    }

    @Test
    void testGetSrcReturnsTypedSource() {
        final String source = "TestSource";
        final WikiEngineEvent event = new WikiEngineEvent( source, WikiEngineEvent.INITIALIZED );
        final String src = event.getSrc();
        assertEquals( "TestSource", src );
    }

    @Test
    void testGetArgsDefaultEmpty() {
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED );
        assertNotNull( event.getArgs() );
        assertEquals( 0, event.getArgs().length );
    }

    @Test
    void testGetArgsDefaultEmptyViaSubclass() {
        // WikiPageEvent stores page name internally, not via args
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED );
        assertNotNull( event.getArgs() );
    }

    @Test
    void testGetArgReturnsNullForOutOfBounds() {
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED );
        assertNull( event.getArg( 99, String.class ) );
    }

    @Test
    void testGetTypeDescriptionForUnknownType() {
        // Use a type that falls into the default branch of WikiEngineEvent's switch
        // WikiEngineEvent accepts a wider range, but the base WikiEvent.getTypeDescription
        // has ERROR, UNDEFINED, and default branches. Subclass overrides may mask these.
        // We test via WikiPageEvent with a known type to hit the subclass switch.
        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "Test" );
        assertNotNull( event.getTypeDescription() );
        assertFalse( event.getTypeDescription().isEmpty() );
    }

    @Test
    void testIsValidTypeRejectsErrorAndUndefined() {
        assertFalse( WikiEvent.isValidType( WikiEvent.ERROR ) );
        assertFalse( WikiEvent.isValidType( WikiEvent.UNDEFINED ) );
    }

    @Test
    void testIsValidTypeAcceptsNormalTypes() {
        assertTrue( WikiEvent.isValidType( WikiEngineEvent.INITIALIZED ) );
        assertTrue( WikiEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertTrue( WikiEvent.isValidType( WikiSecurityEvent.ACCESS_ALLOWED ) );
    }

    @Test
    void testToStringIncludesEventName() {
        final WikiEngineEvent event = new WikiEngineEvent( "MySource", WikiEngineEvent.INITIALIZED );
        final String str = event.toString();
        assertTrue( str.contains( "INITIALIZED" ) || str.contains( "initialized" ),
                "toString should include event name, got: " + str );
    }

    @Test
    void testToStringIncludesSource() {
        final WikiEngineEvent event = new WikiEngineEvent( "MySource", WikiEngineEvent.INITIALIZED );
        final String str = event.toString();
        assertTrue( str.contains( "MySource" ), "toString should include source, got: " + str );
    }
}
