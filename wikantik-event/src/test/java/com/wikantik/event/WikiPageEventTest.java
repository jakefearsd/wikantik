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

class WikiPageEventTest {

    private static final Object SOURCE = new Object();

    static Stream<Arguments> pageEventTypes() {
        return Stream.of(
                Arguments.of( WikiPageEvent.PAGE_LOCK,            "PAGE_LOCK",            "page lock event" ),
                Arguments.of( WikiPageEvent.PAGE_UNLOCK,          "PAGE_UNLOCK",          "page unlock event" ),
                Arguments.of( WikiPageEvent.PRE_TRANSLATE_BEGIN,  "PRE_TRANSLATE_BEGIN",  "begin page pre-translate events" ),
                Arguments.of( WikiPageEvent.PRE_TRANSLATE,        "PRE_TRANSLATE",        "page pre-translate event" ),
                Arguments.of( WikiPageEvent.PRE_TRANSLATE_END,    "PRE_TRANSLATE_END",    "end of page pre-translate events" ),
                Arguments.of( WikiPageEvent.POST_TRANSLATE_BEGIN, "POST_TRANSLATE_BEGIN", "begin page post-translate events" ),
                Arguments.of( WikiPageEvent.POST_TRANSLATE,       "POST_TRANSLATE",       "page post-translate event" ),
                Arguments.of( WikiPageEvent.POST_TRANSLATE_END,   "POST_TRANSLATE_END",   "end of page post-translate events" ),
                Arguments.of( WikiPageEvent.PRE_SAVE_BEGIN,       "PRE_SAVE_BEGIN",       "begin page pre-save events" ),
                Arguments.of( WikiPageEvent.PRE_SAVE,             "PRE_SAVE",             "page pre-save event" ),
                Arguments.of( WikiPageEvent.PRE_SAVE_END,         "PRE_SAVE_END",         "end of page pre-save events" ),
                Arguments.of( WikiPageEvent.POST_SAVE_BEGIN,      "POST_SAVE_BEGIN",      "begin page post-save events" ),
                Arguments.of( WikiPageEvent.POST_SAVE,            "POST_SAVE",            "page post-save event" ),
                Arguments.of( WikiPageEvent.POST_SAVE_END,        "POST_SAVE_END",        "end of page post-save events" ),
                Arguments.of( WikiPageEvent.PAGE_REQUESTED,       "PAGE_REQUESTED",       "page requested event" ),
                Arguments.of( WikiPageEvent.PAGE_DELIVERED,       "PAGE_DELIVERED",       "page delivered event" ),
                Arguments.of( WikiPageEvent.PAGE_DELETE_REQUEST,  "PAGE_DELETE_REQUEST",  "page delete request event" ),
                Arguments.of( WikiPageEvent.PAGE_DELETED,         "PAGE_DELETED",         "page deleted event" )
        );
    }

    @ParameterizedTest( name = "eventName({0}) = {1}" )
    @MethodSource( "pageEventTypes" )
    void testEventName( final int type, final String expectedName, final String expectedDesc ) {
        final WikiPageEvent event = new WikiPageEvent( SOURCE, type, "TestPage" );
        assertEquals( expectedName, event.eventName() );
    }

    @ParameterizedTest( name = "getTypeDescription({0}) = {2}" )
    @MethodSource( "pageEventTypes" )
    void testGetTypeDescription( final int type, final String expectedName, final String expectedDesc ) {
        final WikiPageEvent event = new WikiPageEvent( SOURCE, type, "TestPage" );
        assertEquals( expectedDesc, event.getTypeDescription() );
    }

    @Test
    void testIsValidType() {
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_DELETED ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.POST_SAVE ) );
        assertFalse( WikiPageEvent.isValidType( -1 ) );
        assertFalse( WikiPageEvent.isValidType( 0 ) );
    }

    @Test
    void testGetPageName() {
        final WikiPageEvent event = new WikiPageEvent( SOURCE, WikiPageEvent.PAGE_REQUESTED, "MyPage" );
        assertEquals( "MyPage", event.getPageName() );
    }

    @Test
    void testToString() {
        final WikiPageEvent event = new WikiPageEvent( SOURCE, WikiPageEvent.PAGE_LOCK, "LockedPage" );
        final String str = event.toString();
        assertNotNull( str );
        // toString includes the event type description
        assertTrue( str.contains( "PAGE_LOCK" ) || str.contains( "page lock" ),
                "toString should include event info, got: " + str );
    }
}
