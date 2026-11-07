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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.auth.Users;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real engine, real ACL and policy wiring: no mocked AuthorizationManager. */
class WikiCollabPermissionsAclTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( "AclLocked" );
        engine.deleteTestPage( "AclOpen" );
        engine.stop();
    }

    @Test
    void realAclDeniesEditToAnOrdinaryUserAndAnAclFreePageAllowsIt() throws Exception {
        engine.saveText( "AclLocked", "[{ALLOW edit SomeOtherUser}]\nlocked" );
        engine.saveText( "AclOpen", "open" );
        final Session user = WikiSessionTest.authenticatedSession( engine, Users.BOB, Users.BOB_PASS );
        final WikiCollabPermissions permissions = new WikiCollabPermissions( engine );

        assertFalse( permissions.canEdit( user, "AclLocked" ) );
        assertTrue( permissions.canEdit( user, "AclOpen" ) );
    }
}
