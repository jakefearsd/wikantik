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
package com.wikantik.importer;

import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineWikiSnapshotTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void existingPageIsExactThenCaseInsensitive() throws Exception {
        engine.saveText( "TestPage", "x" );
        final PageManager pm = engine.getManager( PageManager.class );
        final WikiSnapshot s = EngineWikiSnapshot.capture( pm,
            CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry(), null );
        assertEquals( "TestPage", s.existingPage( "TestPage" ).orElseThrow() );
        assertEquals( "TestPage", s.existingPage( "testpage" ).orElseThrow() );
        assertTrue( s.existingPage( "Nope" ).isEmpty() );
        assertTrue( s.hubPage( "any" ).isEmpty() );
    }

    @Test
    void systemPagesAreRecognised() throws Exception {
        final PageManager pm = engine.getManager( PageManager.class );
        final WikiSnapshot s = EngineWikiSnapshot.capture( pm,
            CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry(), null );
        assertTrue( s.isSystemPage( "About" ) );
        assertFalse( s.isSystemPage( "TestPage" ) );
        assertFalse( EngineWikiSnapshot.capture( pm, null, null ).isSystemPage( "About" ) );
    }
}
