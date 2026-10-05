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
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedProfileNamesTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void builtInAndAdminRoleNamesAreReservedCaseInsensitively() {
        for ( final String name : new String[] { "Admin", "admin", " Admin ", "A dmin", "All", "Anonymous", "Asserted", "AUTHENTICATED" } ) {
            assertTrue( ReservedProfileNames.isReserved( engine, name ), name );
        }
    }

    @Test
    void existingGroupNameIsReserved() throws Exception {
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group group = groups.parseGroup( "QaReviewers", "", true );
        groups.setGroup( WikiSessionTest.adminSession( engine ), group );
        try {
            assertTrue( ReservedProfileNames.isReserved( engine, "QaReviewers" ) );
            assertTrue( ReservedProfileNames.isReserved( engine, "qareviewers" ) );
            assertTrue( ReservedProfileNames.isReserved( engine, "Qa Reviewers" ) );
        } finally {
            groups.removeGroup( "QaReviewers" );
        }
    }

    @Test
    void ordinaryNamesAreNotReserved() {
        assertFalse( ReservedProfileNames.isReserved( engine, "Jane Doe" ) );
        assertFalse( ReservedProfileNames.isReserved( engine, "Administrator Jones" ) );
        assertFalse( ReservedProfileNames.isReserved( engine, null ) );
        assertFalse( ReservedProfileNames.isReserved( engine, "  " ) );
    }
}
