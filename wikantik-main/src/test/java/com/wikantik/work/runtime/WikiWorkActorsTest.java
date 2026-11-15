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
package com.wikantik.work.runtime;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.work.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiWorkActorsTest {

    private TestEngine engine;
    private GroupManager groups;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        groups = engine.getManager( GroupManager.class );
        admin = engine.adminSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            groups.removeGroup( "WorkCrew" );
        } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
            // the group was never created by this test
        }
        engine.stop();
    }

    private void setMembers( final String members ) throws Exception {
        final Group group = groups.parseGroup( "WorkCrew", members, true );
        groups.setGroup( admin, group );
    }

    @Test
    void anonymousIsNull() {
        assertNull( WikiWorkActors.actorFor( engine, com.wikantik.api.spi.Wiki.session().guest( engine ) ) );
        assertNull( WikiWorkActors.actorFor( engine, null ) );
    }

    @Test
    void loginAndKindAreResolved() throws Exception {
        final Actor actor = WikiWorkActors.actorFor( engine, engine.janneSession() );
        assertEquals( "janne", actor.login() );
        assertEquals( Actor.Kind.HUMAN, actor.kind() );
    }

    @Test
    void groupsReflectMembershipChangeImmediately() throws Exception {
        final Session janne = engine.janneSession();
        assertFalse( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ) );

        setMembers( "janne" );
        assertTrue( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ),
            "added to the group: the next call sees it" );

        setMembers( "someoneElse" );
        assertFalse( WikiWorkActors.actorFor( engine, janne ).groups().contains( "WorkCrew" ),
            "removed from the group: the next call sees it" );
    }

    @Test
    void adminFlagFollowsAllPermission() throws Exception {
        assertTrue( WikiWorkActors.actorFor( engine, admin ).admin() );
        assertFalse( WikiWorkActors.actorFor( engine, engine.janneSession() ).admin() );
    }
}
