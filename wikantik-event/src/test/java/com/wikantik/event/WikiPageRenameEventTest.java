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

class WikiPageRenameEventTest {

    @Test
    void testOldAndNewPageNames() {
        final WikiPageRenameEvent event = new WikiPageRenameEvent( this, "OldName", "NewName" );
        assertEquals( "OldName", event.getOldPageName() );
        assertEquals( "NewName", event.getNewPageName() );
        assertEquals( "NewName", event.getPageName() );
    }

    @Test
    void testEventNameAndDescription() {
        final WikiPageRenameEvent event = new WikiPageRenameEvent( this, "A", "B" );
        assertEquals( "PAGE_RENAMED", event.eventName() );
        assertEquals( "page renamed event", event.getTypeDescription() );
    }

    @Test
    void testIsValidType() {
        assertTrue( WikiPageRenameEvent.isValidType( WikiPageRenameEvent.PAGE_RENAMED ) );
        assertFalse( WikiPageRenameEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertFalse( WikiPageRenameEvent.isValidType( -1 ) );
    }

    @Test
    void testType() {
        final WikiPageRenameEvent event = new WikiPageRenameEvent( this, "X", "Y" );
        assertEquals( WikiPageRenameEvent.PAGE_RENAMED, event.getType() );
    }
}
