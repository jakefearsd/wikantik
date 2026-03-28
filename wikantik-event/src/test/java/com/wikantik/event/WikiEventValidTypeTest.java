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
 * Verifies that subclass-specific {@code isValidType()} static methods return
 * correct results for their own event types and reject types belonging to other
 * event families. This tests that the intentional static method hiding works as
 * expected when called through the correct class reference.
 */
class WikiEventValidTypeTest {

    @Test
    void parentRejectsErrorAndUndefined() {
        assertFalse( WikiEvent.isValidType( WikiEvent.ERROR ) );
        assertFalse( WikiEvent.isValidType( WikiEvent.UNDEFINED ) );
    }

    @Test
    void parentAcceptsOtherTypes() {
        assertTrue( WikiEvent.isValidType( WikiEngineEvent.INITIALIZED ) );
        assertTrue( WikiEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
    }

    @Test
    void engineEventAcceptsOwnTypes() {
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.INITIALIZING ) );
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.INITIALIZED ) );
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.SHUTDOWN ) );
        assertTrue( WikiEngineEvent.isValidType( WikiEngineEvent.STOPPED ) );
    }

    @Test
    void engineEventRejectsPageTypes() {
        assertFalse( WikiEngineEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertFalse( WikiEngineEvent.isValidType( WikiPageEvent.POST_SAVE ) );
    }

    @Test
    void pageEventAcceptsOwnTypes() {
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_UNLOCK ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PRE_TRANSLATE_BEGIN ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.POST_SAVE_END ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_REQUESTED ) );
        assertTrue( WikiPageEvent.isValidType( WikiPageEvent.PAGE_DELETED ) );
    }

    @Test
    void pageEventRejectsEngineTypes() {
        assertFalse( WikiPageEvent.isValidType( WikiEngineEvent.INITIALIZING ) );
        assertFalse( WikiPageEvent.isValidType( WikiEngineEvent.STOPPED ) );
    }

    @Test
    void renameEventAcceptsOwnType() {
        assertTrue( WikiPageRenameEvent.isValidType( WikiPageRenameEvent.PAGE_RENAMED ) );
    }

    @Test
    void renameEventRejectsOtherTypes() {
        assertFalse( WikiPageRenameEvent.isValidType( WikiPageEvent.PAGE_LOCK ) );
        assertFalse( WikiPageRenameEvent.isValidType( WikiEngineEvent.INITIALIZED ) );
    }

}
