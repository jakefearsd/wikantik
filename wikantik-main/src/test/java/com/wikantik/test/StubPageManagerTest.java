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
package com.wikantik.test;

import com.wikantik.api.core.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that StubPageManager correctly implements the PageManager contract
 * for use in unit tests that don't need a full WikiEngine.
 */
class StubPageManagerTest {

    private StubPageManager pm;

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
    }

    @Test
    void testSaveAndGetPage() {
        pm.savePage( "TestPage", "# Hello\nWorld." );

        final Page page = pm.getPage( "TestPage" );
        assertNotNull( page );
        assertEquals( "TestPage", page.getName() );
    }

    @Test
    void testGetPureText() {
        pm.savePage( "TestPage", "Body content here." );
        assertEquals( "Body content here.", pm.getPureText( "TestPage", -1 ) );
    }

    @Test
    void testPageExists() throws Exception {
        assertFalse( pm.pageExists( "Missing" ) );
        pm.savePage( "Exists", "content" );
        assertTrue( pm.pageExists( "Exists" ) );
    }

    @Test
    void testGetAllPages() throws Exception {
        pm.savePage( "Page1", "a" );
        pm.savePage( "Page2", "b" );
        assertEquals( 2, pm.getAllPages().size() );
    }

    @Test
    void testDeletePage() {
        pm.savePage( "ToDelete", "content" );
        assertTrue( pm.pageExists( "ToDelete" ) );
        pm.deletePage( "ToDelete" );
        assertFalse( pm.pageExists( "ToDelete" ) );
    }

    @Test
    void testNonexistentPageReturnsNull() {
        assertNull( pm.getPage( "DoesNotExist" ) );
        assertNull( pm.getPageText( "DoesNotExist", -1 ) );
    }

    @Test
    void testTotalPageCount() {
        assertEquals( 0, pm.getTotalPageCount() );
        pm.savePage( "A", "a" );
        pm.savePage( "B", "b" );
        assertEquals( 2, pm.getTotalPageCount() );
    }

    @Test
    void testPageAttributes() {
        pm.savePage( "AttrPage", "content" );
        final Page page = pm.getPage( "AttrPage" );
        page.setAttribute( "type", "article" );
        assertEquals( "article", page.getAttribute( "type" ) );
    }

    @Test
    void testPageClone() {
        pm.savePage( "ClonePage", "content" );
        final Page original = pm.getPage( "ClonePage" );
        original.setAttribute( "key", "value" );
        final Page clone = original.clone();
        assertEquals( "ClonePage", clone.getName() );
        assertEquals( "value", clone.getAttribute( "key" ) );
    }
}
